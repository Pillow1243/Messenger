-- Security hardening: caller-scoped social-graph helpers (audit finding S-2).
--
-- The problem
-- -----------
-- `mm_is_member`, `mm_role`, `mm_is_admin`, `mm_can_post`, `mm_shares_conversation`,
-- `mm_is_blocked` and `mm_is_call_participant` are SECURITY DEFINER helpers that back the RLS
-- policies. PostgreSQL grants EXECUTE on new functions to PUBLIC, and migration
-- 20261112035022 revoked it from `anon` only, so every *signed-in* user could call them through
-- PostgREST (`POST /rest/v1/rpc/mm_shares_conversation {"p_user_a":..., "p_user_b":...}`) with
-- arbitrary identifiers and learn facts about other people:
--
--   * `mm_shares_conversation(a, b)` - do two *other* users share any conversation?
--   * `mm_is_member(conv, user)` / `mm_role` / `mm_is_admin` - is user X in conversation Y, and
--     with which role? (a group/channel membership oracle)
--   * `mm_is_blocked(a, b)` - has user A blocked user B?
--   * `mm_is_call_participant(call, user)` - is user X on call Y?
--
-- That is a social-graph enumeration primitive: with a list of public channel ids or uuids it
-- turns into "who talks to whom" without ever touching a message. It must not survive.
--
-- The fix (two independent layers)
-- ---------------------------------
-- 1. The *implementations* move to `mm_private`, a schema that is deliberately **not** in
--    PostgREST's exposed schema list (`db-schemas = public`, see supabase/config.toml), and no
--    API role holds USAGE on it or EXECUTE on its functions. Because the public helpers below are
--    SECURITY DEFINER, they keep working with zero grants on `mm_private`; a role that cannot
--    reach the schema cannot enumerate anything through it.
-- 2. The remaining `public.mm_*` wrappers are **caller-scoped**: outside the caller's own rows
--    they answer `false`/`null` instead of the truth. Legitimate uses keep working because every
--    policy and every SECURITY DEFINER RPC passes `auth.uid()` (or asks about a co-member of a
--    conversation the caller is in), which is exactly the set of questions a client may ask.
--
-- Nothing is dropped and no policy is weakened: RLS keeps its full expressive power, the answers
-- a client can obtain shrink to its own relationships, and the internal helpers used by the RPCs
-- remain unrestricted where it matters (they run as the definer, not as the caller).
--
-- Rollback: `drop schema mm_private cascade;` and restore the previous bodies from
-- 20261111120000/20261111120200. No table, column or row is touched by this migration.

-- ------------------------------------------------------------------ private implementation
create schema if not exists mm_private;

comment on schema mm_private is
  'Messenger internal policy helpers. NOT exposed through PostgREST: only SECURITY DEFINER '
  'functions in public may call these, so they can never be enumerated by an API client.';

revoke all on schema mm_private from public;
do $revoke_roles$
declare
  r text;
begin
  foreach r in array array['anon', 'authenticated', 'service_role'] loop
    if exists (select 1 from pg_roles where rolname = r) then
      execute format('revoke all on schema mm_private from %I', r);
    end if;
  end loop;
end
$revoke_roles$;

create or replace function mm_private.member_exists(p_conversation uuid, p_user uuid)
returns boolean language sql stable security definer set search_path = public, pg_temp as $$
  select exists (
    select 1 from public.mm_conversation_members m
    where m.conversation_id = p_conversation and m.user_id = p_user
  );
$$;

create or replace function mm_private.member_role(p_conversation uuid, p_user uuid)
returns text language sql stable security definer set search_path = public, pg_temp as $$
  select m.role from public.mm_conversation_members m
  where m.conversation_id = p_conversation and m.user_id = p_user;
$$;

create or replace function mm_private.can_post(p_conversation uuid, p_user uuid)
returns boolean language sql stable security definer set search_path = public, pg_temp as $$
  select exists (
    select 1
    from public.mm_conversation_members m
    join public.mm_conversations c on c.id = m.conversation_id
    where m.conversation_id = p_conversation
      and m.user_id = p_user
      and m.role in ('owner','admin','member')
      and c.deleted_at is null
      and c.archived_at is null
  );
$$;

create or replace function mm_private.shares_conversation(p_user_a uuid, p_user_b uuid)
returns boolean language sql stable security definer set search_path = public, pg_temp as $$
  select exists (
    select 1
    from public.mm_conversation_members a
    join public.mm_conversation_members b on b.conversation_id = a.conversation_id
    where a.user_id = p_user_a and b.user_id = p_user_b
  );
$$;

create or replace function mm_private.is_blocked(p_user_a uuid, p_user_b uuid)
returns boolean language sql stable security definer set search_path = public, pg_temp as $$
  select exists (
    select 1 from public.mm_blocks b
    where (b.blocker_id = p_user_a and b.blocked_id = p_user_b)
       or (b.blocker_id = p_user_b and b.blocked_id = p_user_a)
  );
$$;

create or replace function mm_private.is_call_participant(p_call uuid, p_user uuid)
returns boolean language sql stable security definer set search_path = public, pg_temp as $$
  select exists (
    select 1 from public.mm_call_participants p
    where p.call_id = p_call and p.user_id = p_user
  );
$$;

do $private_grants$
declare
  r record;
begin
  for r in
    select p.oid::regprocedure as sig
      from pg_proc p
      join pg_namespace n on n.oid = p.pronamespace
     where n.nspname = 'mm_private'
  loop
    execute format('revoke all on function %s from public', r.sig);
    execute format('revoke all on function %s from anon', r.sig);
    execute format('revoke all on function %s from authenticated', r.sig);
    execute format('revoke all on function %s from service_role', r.sig);
  end loop;
end
$private_grants$;

-- --------------------------------------------------------------- caller-scoped public helpers
-- Behaviour is unchanged for the caller's own data (which is what RLS and the RPCs ask about);
-- anything about a third party that the caller has no relationship with now returns false/null.

create or replace function public.mm_is_member(p_conversation uuid, p_user uuid)
returns boolean language plpgsql stable security definer set search_path = public, pg_temp as $$
declare
  v_caller uuid := auth.uid();
begin
  if v_caller is null or p_conversation is null or p_user is null then
    return false;
  end if;
  -- Your own membership: always answerable.
  if p_user = v_caller then
    return mm_private.member_exists(p_conversation, v_caller);
  end if;
  -- A co-member's membership: answerable only while you are a member of that conversation
  -- (the device/SKDM fan-out policies need this).
  if mm_private.member_exists(p_conversation, v_caller) then
    return mm_private.member_exists(p_conversation, p_user);
  end if;
  return false;
end $$;

create or replace function public.mm_role(p_conversation uuid, p_user uuid)
returns text language plpgsql stable security definer set search_path = public, pg_temp as $$
declare
  v_caller uuid := auth.uid();
begin
  if v_caller is null or p_conversation is null or p_user is null then
    return null;
  end if;
  if p_user <> v_caller and not mm_private.member_exists(p_conversation, v_caller) then
    return null;
  end if;
  return mm_private.member_role(p_conversation, p_user);
end $$;

create or replace function public.mm_is_admin(p_conversation uuid, p_user uuid)
returns boolean language plpgsql stable security definer set search_path = public, pg_temp as $$
declare
  v_caller uuid := auth.uid();
begin
  if v_caller is null or p_conversation is null or p_user is null then
    return false;
  end if;
  if p_user <> v_caller and not mm_private.member_exists(p_conversation, v_caller) then
    return false;
  end if;
  return coalesce(mm_private.member_role(p_conversation, p_user) in ('owner','admin'), false);
end $$;

create or replace function public.mm_can_post(p_conversation uuid, p_user uuid)
returns boolean language plpgsql stable security definer set search_path = public, pg_temp as $$
declare
  v_caller uuid := auth.uid();
begin
  if v_caller is null or p_conversation is null or p_user is null then
    return false;
  end if;
  if p_user <> v_caller and not mm_private.member_exists(p_conversation, v_caller) then
    return false;
  end if;
  return mm_private.can_post(p_conversation, p_user);
end $$;

create or replace function public.mm_shares_conversation(p_user_a uuid, p_user_b uuid)
returns boolean language plpgsql stable security definer set search_path = public, pg_temp as $$
declare
  v_caller uuid := auth.uid();
begin
  if v_caller is null or p_user_a is null or p_user_b is null then
    return false;
  end if;
  -- Only ever answers a question that involves the caller. Asking about two other users is the
  -- enumeration primitive this migration removes.
  if v_caller <> p_user_a and v_caller <> p_user_b then
    return false;
  end if;
  return mm_private.shares_conversation(p_user_a, p_user_b);
end $$;

create or replace function public.mm_is_blocked(p_user_a uuid, p_user_b uuid)
returns boolean language plpgsql stable security definer set search_path = public, pg_temp as $$
declare
  v_caller uuid := auth.uid();
begin
  if v_caller is null or p_user_a is null or p_user_b is null then
    return false;
  end if;
  if v_caller <> p_user_a and v_caller <> p_user_b then
    return false;
  end if;
  return mm_private.is_blocked(p_user_a, p_user_b);
end $$;

create or replace function public.mm_is_call_participant(p_call uuid, p_user uuid)
returns boolean language plpgsql stable security definer set search_path = public, pg_temp as $$
declare
  v_caller uuid := auth.uid();
begin
  if v_caller is null or p_call is null or p_user is null then
    return false;
  end if;
  if p_user = v_caller then
    return mm_private.is_call_participant(p_call, v_caller);
  end if;
  -- Signal delivery asks about the recipient; a participant may do that, an outsider may not.
  if mm_private.is_call_participant(p_call, v_caller) then
    return mm_private.is_call_participant(p_call, p_user);
  end if;
  return false;
end $$;

-- --------------------------------------------------- service-role rate limiting (edge functions)
-- The Edge Functions must rate limit per *subject* they authenticated themselves, but they call
-- the database with the service-role key, where `auth.uid()` is NULL. `mm_consume_rate_limit`
-- would therefore count every dispatch in a single shared 'anon' bucket. This variant takes the
-- subject explicitly, is only executable by `service_role`, and enforces a small allow-list of
-- actions so a leaked service key cannot forge arbitrary bucket names.
create or replace function public.mm_consume_rate_limit_as(
  p_subject text,
  p_action text,
  p_max integer,
  p_window_seconds integer
) returns void
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_hits integer;
  v_window timestamptz;
begin
  if p_subject is null or char_length(p_subject) < 8 or char_length(p_subject) > 128 then
    raise exception 'invalid_subject' using errcode = 'P0006';
  end if;
  if p_action not in ('push_dispatch', 'turn_creds', 'account_delete') then
    raise exception 'invalid_rate_limit_action' using errcode = 'P0006';
  end if;
  if p_max is null or p_max < 1 or p_max > 10000 then
    raise exception 'invalid_rate_limit_max' using errcode = 'P0006';
  end if;
  if p_window_seconds is null or p_window_seconds < 1 or p_window_seconds > 86400 then
    raise exception 'invalid_rate_limit_window' using errcode = 'P0006';
  end if;

  v_window := to_timestamp(floor(extract(epoch from now()) / p_window_seconds) * p_window_seconds);
  insert into public.mm_rate_limits (subject, action, window_start, hits)
  values (p_subject, p_action, v_window, 1)
  on conflict (subject, action, window_start) do update set hits = public.mm_rate_limits.hits + 1
  returning hits into v_hits;

  if v_hits > p_max then
    raise exception 'rate_limited: % (max % per %s)', p_action, p_max, p_window_seconds
      using errcode = 'P0001';
  end if;
end $$;

-- Expired rate-limit windows are pure garbage; keep the table from growing without bound.
create or replace function public.mm_prune_rate_limits(p_older_than interval default '7 days')
returns integer language sql security definer set search_path = public, pg_temp as $$
  with gone as (
    delete from public.mm_rate_limits
     where window_start < now() - p_older_than
     returning 1
  )
  select count(*)::integer from gone;
$$;

-- ------------------------------------------------------------------------- function privileges
-- rules: nothing in mm_* is callable by anon or PUBLIC; the internal schema is not callable at
-- all except by its owner (through the SECURITY DEFINER wrappers).
do $grants$
declare
  r record;
begin
  for r in
    select p.oid::regprocedure as sig,
           (p.prorettype = 'trigger'::regtype) as is_trigger,
           p.proname
      from pg_proc p
      join pg_namespace n on n.oid = p.pronamespace
     where n.nspname = 'public'
       and p.proname like 'mm\_%'
  loop
    execute format('revoke all on function %s from public', r.sig);
    execute format('revoke all on function %s from anon', r.sig);
    if r.is_trigger then
      execute format('revoke all on function %s from authenticated', r.sig);
      execute format('revoke all on function %s from service_role', r.sig);
    elsif r.proname = 'mm_consume_rate_limit_as' then
      -- Edge Functions only: the Android client never needs to pick its own rate-limit subject.
      execute format('revoke all on function %s from authenticated', r.sig);
      execute format('grant execute on function %s to service_role', r.sig);
    elsif r.proname = 'mm_prune_rate_limits' then
      execute format('revoke all on function %s from authenticated', r.sig);
      execute format('grant execute on function %s to service_role', r.sig);
    else
      execute format('grant execute on function %s to authenticated', r.sig);
      execute format('grant execute on function %s to service_role', r.sig);
    end if;
  end loop;
end
$grants$;

-- Belt and braces: even if a future migration grants USAGE on mm_private, no API role may
-- execute its functions.
do $private_grants_final$
declare
  r record;
begin
  for r in
    select p.oid::regprocedure as sig
      from pg_proc p
      join pg_namespace n on n.oid = p.pronamespace
     where n.nspname = 'mm_private'
  loop
    execute format('revoke all on function %s from public', r.sig);
    execute format('revoke all on function %s from anon', r.sig);
    execute format('revoke all on function %s from authenticated', r.sig);
  end loop;
end
$private_grants_final$;
