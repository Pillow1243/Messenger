-- Messenger: write-path hardening (audit findings W-1 .. W-19, see docs/AUDIT.md part A.6).
--
-- Every finding closed here was reproduced against a disposable PostgreSQL as the `authenticated`
-- role with forged-but-valid JWT claims before this file was written, and each one has a
-- regression assertion in supabase/tests/rls_write_path_hardening.sql.
--
-- The root cause shared by most of them: the tables were reachable through PostgREST with the
-- platform's default `GRANT ALL ... TO authenticated`, and several RLS policies only checked *who*
-- was writing, not *what* was being written. The security-definer RPCs enforced the real rules
-- (block lists, device cap, epoch rotation, terminal call states, rate limits), but a client could
-- simply skip the RPC and write the row itself.
--
-- The fix has three layers:
--   1. Least-privilege grants: every mm_* table is reset to an explicit allow-list of the
--      statements (and columns) the app really issues. Membership, devices, pre-keys, calls,
--      reports and push tokens are now writable through RPCs only.
--   2. Tightened RLS policies for the tables that stay directly writable.
--   3. Corrected RPCs (device revocation, registration, read receipts, search, reports, pre-keys).
--
-- Scope: only Messenger-owned `mm_*` objects, the `mm_private` schema and the two `mm-*` storage
-- buckets' policies. Nothing belonging to another application sharing the project is touched.
-- Idempotent: safe to re-run.

-- =====================================================================================
-- 0. Contract fixes (functional defects that made valid client requests fail)
-- =====================================================================================

-- W-1  mm_messages.kind rejected kinds the shipped client sends ('image', 'video', 'audio' for
--      media and 'text-edit' for edits), so those uploads failed with a check violation.
alter table public.mm_messages drop constraint if exists mm_messages_kind_check;
alter table public.mm_messages add constraint mm_messages_kind_check
  check (kind in ('text','file','voice','image','video','audio','text-edit','system','call'));

-- W-2  Two overloads mm_set_call_state(uuid,text) and mm_set_call_state(uuid,text,text default
--      null) made a PostgREST call with only {p_call, p_state} ambiguous (PGRST203, HTTP 300):
--      answering a call could never mark it active. The legacy two-argument overload also skipped
--      the terminal-state rules. It is superseded by the three-argument function.
drop function if exists public.mm_set_call_state(uuid, text);

-- Same body as before with two tightenings: a call can never move *back* to "ringing" (which
-- would re-arm the ring for the other side), and the free-text end reason is bounded.
create or replace function public.mm_set_call_state(p_call uuid, p_state text, p_end_reason text default null)
returns void
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_current text;
  v_started timestamptz;
  v_reason text := nullif(left(btrim(coalesce(p_end_reason, '')), 64), '');
begin
  if v_uid is null or not public.mm_is_call_participant(p_call, v_uid) then
    raise exception 'not_a_participant' using errcode = 'P0013';
  end if;
  if p_state not in ('ringing', 'active', 'ended', 'declined', 'missed', 'failed') then
    raise exception 'invalid_call_state' using errcode = 'P0017';
  end if;

  select state, started_at into v_current, v_started
    from public.mm_calls where id = p_call for update;
  if v_current is null then
    raise exception 'call_not_found' using errcode = 'P0009';
  end if;

  -- Once terminal, late or duplicate transitions are ignored (idempotent). "ringing" is only
  -- ever the initial state.
  if v_current in ('ended', 'declined', 'missed', 'failed') or p_state = 'ringing' then
    return;
  end if;

  -- Reject answering an expired ring (> 70s).
  if p_state = 'active' and v_current = 'ringing' and v_started < now() - interval '70 seconds' then
    update public.mm_calls
       set state = 'missed', ended_at = now(), end_reason = 'expired'
     where id = p_call;
    return;
  end if;

  update public.mm_calls
     set state = p_state,
         answered_at = case when p_state = 'active' then coalesce(answered_at, now()) else answered_at end,
         ended_at = case when p_state in ('ended','declined','missed','failed') then coalesce(ended_at, now()) else ended_at end,
         end_reason = coalesce(v_reason, end_reason)
   where id = p_call;

  if p_state = 'active' then
    update public.mm_call_participants
       set state = 'joined', joined_at = coalesce(joined_at, now())
     where call_id = p_call and user_id = v_uid;
  elsif p_state = 'declined' then
    update public.mm_call_participants
       set state = 'declined', left_at = now()
     where call_id = p_call and user_id = v_uid;
  elsif p_state in ('ended', 'missed', 'failed') then
    update public.mm_call_participants
       set state = case when state = 'joined' then 'left' else state end,
           left_at = coalesce(left_at, now())
     where call_id = p_call;
  end if;
end $$;
revoke all on function public.mm_set_call_state(uuid, text, text) from public, anon;
grant execute on function public.mm_set_call_state(uuid, text, text) to authenticated, service_role;

-- =====================================================================================
-- 1. Device/session revocation
-- =====================================================================================

-- W-3  Revoking a device did not end its auth session, and mm_register_device() cleared
--      revoked_at, so a revoked (stolen) device simply re-registered itself. The registering
--      session is now recorded and a revoked session is refused everywhere membership is checked.
alter table public.mm_devices add column if not exists auth_session_id uuid;
create index if not exists mm_devices_revoked_session_idx
  on public.mm_devices (auth_session_id) where revoked_at is not null and auth_session_id is not null;

create schema if not exists mm_private;
revoke all on schema mm_private from public, anon, authenticated;

create or replace function mm_private.session_id() returns uuid
language plpgsql stable set search_path = public, pg_temp as $$
begin
  return nullif(auth.jwt() ->> 'session_id', '')::uuid;
exception when others then
  return null;
end $$;

-- Tombstones of auth sessions whose device was revoked. Internal: RLS on, no policy, no grants.
create table if not exists public.mm_revoked_sessions (
  session_id uuid primary key,
  user_id    uuid not null references auth.users (id) on delete cascade,
  device     uuid,
  revoked_at timestamptz not null default now()
);
alter table public.mm_revoked_sessions enable row level security;

create or replace function mm_private.session_revoked() returns boolean
language sql stable security definer set search_path = public, pg_temp as $$
  select mm_private.session_id() is not null and exists (
    select 1 from public.mm_revoked_sessions r where r.session_id = mm_private.session_id()
  );
$$;

-- Policy helper exposed in `public` because RLS expressions are evaluated as the querying role,
-- which has no USAGE on mm_private. It only ever answers about the caller's own session.
create or replace function public.mm_private_session_revoked() returns boolean
language sql stable security definer set search_path = public, pg_temp as $$
  select mm_private.session_revoked();
$$;

create or replace function public.mm_is_member(p_conversation uuid, p_user uuid) returns boolean
language plpgsql stable security definer set search_path = public, pg_temp as $$
declare
  v_caller uuid := auth.uid();
begin
  if v_caller is null or p_conversation is null or p_user is null then
    return false;
  end if;
  if mm_private.session_revoked() then
    return false;
  end if;
  if p_user = v_caller then
    return mm_private.member_exists(p_conversation, v_caller);
  end if;
  if mm_private.member_exists(p_conversation, v_caller) then
    return mm_private.member_exists(p_conversation, p_user);
  end if;
  return false;
end $$;

create or replace function public.mm_can_post(p_conversation uuid, p_user uuid) returns boolean
language plpgsql stable security definer set search_path = public, pg_temp as $$
declare
  v_caller uuid := auth.uid();
begin
  if v_caller is null or p_conversation is null or p_user is null then
    return false;
  end if;
  if mm_private.session_revoked() then
    return false;
  end if;
  if p_user <> v_caller and not mm_private.member_exists(p_conversation, v_caller) then
    return false;
  end if;
  return mm_private.can_post(p_conversation, p_user);
end $$;

create or replace function public.mm_shares_conversation(p_user_a uuid, p_user_b uuid) returns boolean
language plpgsql stable security definer set search_path = public, pg_temp as $$
declare
  v_caller uuid := auth.uid();
begin
  if v_caller is null or p_user_a is null or p_user_b is null then
    return false;
  end if;
  if v_caller <> p_user_a and v_caller <> p_user_b then
    return false;
  end if;
  if mm_private.session_revoked() then
    return false;
  end if;
  return mm_private.shares_conversation(p_user_a, p_user_b);
end $$;

create or replace function public.mm_register_device(
  p_device_id text, p_name text, p_platform text, p_identity_key text, p_registration_id integer,
  p_signed jsonb, p_kyber jsonb, p_prekeys jsonb
) returns uuid
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_session uuid := mm_private.session_id();
  v_device uuid;
  v_active_count integer;
  v_existing uuid;
  v_revoked timestamptz;
  v_old_session uuid;
  v_key jsonb;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  if not exists (select 1 from public.mm_profiles p where p.id = v_uid and p.deleted_at is null) then
    raise exception 'profile_missing' using errcode = 'P0003';
  end if;
  -- A session whose device was revoked may not register anything again, under any device id.
  if mm_private.session_revoked() then
    raise exception 'device_revoked' using errcode = 'P0019';
  end if;

  select d.id, d.revoked_at into v_existing, v_revoked
    from public.mm_devices d
   where d.user_id = v_uid and d.device_id = p_device_id;

  -- A revoked device is never silently resurrected by the session that was revoked. Only a
  -- different, freshly authenticated session (i.e. somebody who knows the password again) may
  -- re-link the same installation, and it counts against the device cap like a new device.
  if v_existing is not null and v_revoked is not null then
    select d.auth_session_id into v_old_session from public.mm_devices d where d.id = v_existing;
    if v_session is null or v_old_session is null or v_session = v_old_session then
      raise exception 'device_revoked' using errcode = 'P0019';
    end if;
  end if;

  if v_existing is null or v_revoked is not null then
    select count(*) into v_active_count
      from public.mm_devices d
     where d.user_id = v_uid and d.revoked_at is null;
    if v_active_count >= 5 then
      raise exception 'device_limit_reached' using errcode = 'P0014';
    end if;
  end if;

  if p_prekeys is not null and jsonb_typeof(p_prekeys) = 'array'
     and jsonb_array_length(p_prekeys) > 200 then
    raise exception 'too_many_prekeys' using errcode = 'P0020';
  end if;

  perform public.mm_consume_rate_limit('device_register', 12, 3600);

  insert into public.mm_devices (
    user_id, device_id, name, platform, identity_key, registration_id,
    signed_prekey_id, signed_prekey_public, signed_prekey_signature,
    kyber_prekey_id, kyber_prekey_public, kyber_prekey_signature, last_seen_at, auth_session_id
  ) values (
    v_uid, p_device_id, left(coalesce(p_name, ''), 64), left(coalesce(p_platform, 'android'), 32),
    p_identity_key, p_registration_id,
    (p_signed->>'id')::integer, p_signed->>'public', p_signed->>'signature',
    (p_kyber->>'id')::integer, p_kyber->>'public', p_kyber->>'signature', now(), v_session
  )
  on conflict (user_id, device_id) do update set
    name = excluded.name, platform = excluded.platform, identity_key = excluded.identity_key,
    registration_id = excluded.registration_id, signed_prekey_id = excluded.signed_prekey_id,
    signed_prekey_public = excluded.signed_prekey_public,
    signed_prekey_signature = excluded.signed_prekey_signature,
    kyber_prekey_id = excluded.kyber_prekey_id, kyber_prekey_public = excluded.kyber_prekey_public,
    kyber_prekey_signature = excluded.kyber_prekey_signature,
    last_seen_at = now(), revoked_at = null,
    auth_session_id = coalesce(excluded.auth_session_id, public.mm_devices.auth_session_id)
  returning id into v_device;

  if v_revoked is not null then
    -- Re-linked installation: peers must re-key groups for it like for any new device.
    perform public.mm_rotate_user_groups(v_uid);
  end if;

  delete from public.mm_device_prekeys where device = v_device and claimed_at is null;
  if p_prekeys is not null and jsonb_typeof(p_prekeys) = 'array' then
    for v_key in select * from jsonb_array_elements(p_prekeys) loop
      insert into public.mm_device_prekeys (device, prekey_id, prekey_public)
      values (v_device, (v_key->>'id')::integer, v_key->>'public')
      on conflict (device, prekey_id) do update set prekey_public = excluded.prekey_public;
    end loop;
  end if;

  insert into public.mm_notification_prefs (user_id)
  values (v_uid) on conflict (user_id) do nothing;

  return v_device;
end $$;

-- W-4  mm_revoke_device() scoped only the UPDATE to the caller. The DELETEs that followed ran for
--      any device uuid, so any signed-in user could wipe another user's one-time pre-keys,
--      undelivered envelopes and sender-key distributions (silent message loss).
create or replace function public.mm_revoke_device(p_device uuid) returns void
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_session uuid;
  v_was_active boolean;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  if mm_private.session_revoked() then
    raise exception 'device_revoked' using errcode = 'P0019';
  end if;
  perform public.mm_consume_rate_limit('device_revoke', 20, 3600);

  select d.auth_session_id, d.revoked_at is null into v_session, v_was_active
    from public.mm_devices d
   where d.id = p_device and d.user_id = v_uid
   for update;
  if not found then
    raise exception 'device_not_found' using errcode = 'P0004';
  end if;

  update public.mm_devices set revoked_at = now()
   where id = p_device and user_id = v_uid and revoked_at is null;
  if v_session is not null then
    insert into public.mm_revoked_sessions (session_id, user_id, device)
    values (v_session, v_uid, p_device)
    on conflict (session_id) do nothing;
  end if;

  delete from public.mm_device_prekeys where device = p_device;
  delete from public.mm_message_envelopes where recipient_device = p_device;
  delete from public.mm_sender_key_distributions
   where recipient_device = p_device or sender_device = p_device;
  update public.mm_push_tokens
     set revoked_at = now(), updated_at = now()
   where device_id = p_device and user_id = v_uid and revoked_at is null;

  if v_was_active then
    perform public.mm_rotate_user_groups(v_uid);
  end if;

  -- Best effort: also end the auth session so its refresh token stops working. The session
  -- check above already locks the revoked session out of Messenger data even when this is not
  -- permitted (the auth schema is owned by the platform).
  if v_session is not null then
    begin
      execute 'delete from auth.sessions where id = $1 and user_id = $2' using v_session, v_uid;
    exception when others then
      null;
    end;
  end if;
end $$;

-- W-5  mm_rotate_user_groups(uuid) was callable by every signed-in user for *any* user id: an
--      outsider could bump the sender-key epoch of every v2 group a victim belongs to, forcing
--      endless re-keying (denial of service). It is an internal helper of the RPCs above.
revoke all on function public.mm_rotate_user_groups(uuid) from public, anon, authenticated;
grant execute on function public.mm_rotate_user_groups(uuid) to service_role;

-- W-6  mm_consume_rate_limit(text,int,int) let a client pick the action name and limits, i.e.
--      write arbitrary rows (100 kB keys) into mm_rate_limits and reset nothing useful. Only the
--      security-definer RPCs and triggers call it.
revoke all on function public.mm_consume_rate_limit(text, integer, integer) from public, anon, authenticated;
grant execute on function public.mm_consume_rate_limit(text, integer, integer) to service_role;

-- =====================================================================================
-- 2. Pre-keys
-- =====================================================================================

alter table public.mm_device_prekeys drop constraint if exists mm_device_prekeys_public_len;
alter table public.mm_device_prekeys add constraint mm_device_prekeys_public_len
  check (char_length(prekey_public) between 8 and 512) not valid;

-- W-7  No cap or rate limit on uploaded one-time pre-keys (storage exhaustion).
create or replace function public.mm_replenish_prekeys(p_device_id text, p_prekeys jsonb) returns integer
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_device uuid;
  v_key jsonb;
  v_added integer := 0;
  v_unclaimed integer;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  select d.id into v_device from public.mm_devices d
   where d.user_id = v_uid and d.device_id = p_device_id and d.revoked_at is null;
  if v_device is null or mm_private.session_revoked() then
    raise exception 'device_not_found' using errcode = 'P0004';
  end if;
  if p_prekeys is null or jsonb_typeof(p_prekeys) <> 'array' then
    return 0;
  end if;
  perform public.mm_consume_rate_limit('prekey_replenish', 30, 3600);
  select count(*) into v_unclaimed from public.mm_device_prekeys
   where device = v_device and claimed_at is null;
  if v_unclaimed + jsonb_array_length(p_prekeys) > 200 then
    raise exception 'too_many_prekeys' using errcode = 'P0020';
  end if;
  for v_key in select * from jsonb_array_elements(p_prekeys) loop
    insert into public.mm_device_prekeys (device, prekey_id, prekey_public)
    values (v_device, (v_key->>'id')::integer, v_key->>'public')
    on conflict (device, prekey_id) do nothing;
    v_added := v_added + 1;
  end loop;
  return v_added;
end $$;

-- =====================================================================================
-- 3. Conversations
-- =====================================================================================

-- W-8  mm_start_direct_conversation(): no rate limit and no check that the peer exists. Together
--      with the dropped direct INSERT policy (W-10) a conversation with a squatted direct_key can
--      no longer exist, but the lookup now also verifies the caller really is a member.
create or replace function public.mm_start_direct_conversation(p_other uuid) returns uuid
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_key text;
  v_id uuid;
begin
  if v_uid is null or p_other is null then
    raise exception 'invalid_arguments' using errcode = 'P0006';
  end if;
  if p_other = v_uid then
    raise exception 'cannot_message_self' using errcode = 'P0007';
  end if;
  if mm_private.session_revoked() then
    raise exception 'device_revoked' using errcode = 'P0019';
  end if;
  if not exists (select 1 from public.mm_profiles p where p.id = p_other and p.deleted_at is null) then
    raise exception 'user_not_found' using errcode = 'P0005';
  end if;
  if mm_private.is_blocked(v_uid, p_other) then
    raise exception 'blocked' using errcode = 'P0008';
  end if;

  v_key := least(v_uid::text, p_other::text) || ':' || greatest(v_uid::text, p_other::text);
  select c.id into v_id from public.mm_conversations c
   where c.direct_key = v_key and c.type = 'direct';
  if v_id is not null then
    if not mm_private.member_exists(v_id, v_uid) or not mm_private.member_exists(v_id, p_other)
       or (select count(*) from public.mm_conversation_members m where m.conversation_id = v_id) <> 2 then
      raise exception 'conversation_corrupt' using errcode = 'P0021';
    end if;
    return v_id;
  end if;

  perform public.mm_consume_rate_limit('direct_start', 40, 3600);

  insert into public.mm_conversations (type, title, owner_id, direct_key, visibility)
  values ('direct', '', v_uid, v_key, 'private')
  returning id into v_id;

  insert into public.mm_conversation_members (conversation_id, user_id, role, invited_by)
  values (v_id, p_other, 'member', v_uid)
  on conflict do nothing;

  return v_id;
end $$;

-- W-9  mm_set_read() recorded a read receipt for any message id, including messages of
--      conversations the caller is not in (forged receipts shown to the sender).
create or replace function public.mm_set_read(p_conversation uuid, p_message uuid) returns void
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
begin
  if v_uid is null or not public.mm_is_member(p_conversation, v_uid) then
    raise exception 'not_a_member' using errcode = 'P0012';
  end if;
  if not exists (
    select 1 from public.mm_messages m where m.id = p_message and m.conversation_id = p_conversation
  ) then
    raise exception 'message_not_found' using errcode = 'P0022';
  end if;
  update public.mm_conversation_members
     set last_read_message_id = p_message
   where conversation_id = p_conversation and user_id = v_uid;
  insert into public.mm_message_receipts (message_id, user_id, delivered_at, read_at)
  values (p_message, v_uid, now(), now())
  on conflict (message_id, user_id) do update
    set delivered_at = coalesce(public.mm_message_receipts.delivered_at, now()), read_at = now();
end $$;

-- =====================================================================================
-- 4. Search and reports
-- =====================================================================================

create or replace function mm_private.like_escape(p text) returns text
language sql immutable set search_path = pg_catalog as $$
  select replace(replace(replace(p, '\', '\\'), '%', '\%'), '_', '\_');
$$;

-- W-11 Search passed '%' and '_' straight into ILIKE: the query "__" enumerated every profile.
create or replace function public.mm_search_users(p_query text)
returns table (id uuid, username text, display_name text, avatar_url text)
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_raw text := trim(coalesce(p_query, ''));
  v_query text;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  v_raw := ltrim(v_raw, '@');
  if char_length(v_raw) < 2 or char_length(v_raw) > 64 then
    return;
  end if;
  if mm_private.session_revoked() then
    return;
  end if;
  perform public.mm_consume_rate_limit('user_search', 120, 60);
  v_query := mm_private.like_escape(v_raw);
  return query
    select p.id, p.username, p.display_name, p.avatar_url
      from public.mm_profiles p
     where p.deleted_at is null
       and p.id <> v_uid
       and not mm_private.is_blocked(p.id, v_uid)
       and (p.username ilike v_query || '%' escape '\'
            or p.display_name ilike '%' || v_query || '%' escape '\')
     order by (p.username ilike v_query || '%' escape '\') desc, p.display_name
     limit 20;
end $$;

create or replace function public.mm_search_public_channels(p_query text)
returns table (id uuid, title text, username text, description text, avatar_url text)
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_raw text := left(ltrim(trim(coalesce(p_query, '')), '@'), 64);
  v_query text;
begin
  if auth.uid() is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  perform public.mm_consume_rate_limit('channel_search', 120, 60);
  v_query := mm_private.like_escape(v_raw);
  return query
    select c.id, c.title, c.username, c.description, c.avatar_url
      from public.mm_conversations c
     where c.type = 'channel' and c.visibility = 'public' and c.deleted_at is null
       and (v_query = '' or c.title ilike '%' || v_query || '%' escape '\'
            or c.username ilike v_query || '%' escape '\')
     order by c.last_message_at desc nulls last, c.created_at desc
     limit 30;
end $$;

-- W-12 Reports accepted any target id, without checking the reporter can even see it, and the
--      direct INSERT policy skipped the rate limit entirely.
create unique index if not exists mm_reports_open_dedup_uidx
  on public.mm_reports (reporter_id, target_kind, target_id) where status = 'open';

create or replace function public.mm_report(
  p_target_kind text, p_target_id uuid, p_reason text, p_details text default null
) returns uuid
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_id uuid;
  v_ok boolean := false;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  if p_target_id is null or p_target_kind not in ('user','conversation','message') then
    raise exception 'invalid_arguments' using errcode = 'P0006';
  end if;
  if p_target_kind = 'user' then
    v_ok := p_target_id <> v_uid
      and exists (select 1 from public.mm_profiles p where p.id = p_target_id);
  elsif p_target_kind = 'conversation' then
    v_ok := mm_private.member_exists(p_target_id, v_uid) or exists (
      select 1 from public.mm_conversations c
       where c.id = p_target_id and c.type = 'channel' and c.visibility = 'public');
  else
    v_ok := exists (
      select 1 from public.mm_messages m
       where m.id = p_target_id and mm_private.member_exists(m.conversation_id, v_uid));
  end if;
  if not v_ok then
    raise exception 'target_not_found' using errcode = 'P0023';
  end if;
  perform public.mm_consume_rate_limit('report', 10, 3600);
  insert into public.mm_reports (reporter_id, target_kind, target_id, reason, details)
  values (v_uid, p_target_kind, p_target_id, left(p_reason, 64), left(p_details, 2000))
  on conflict (reporter_id, target_kind, target_id) where status = 'open'
  do update set reason = excluded.reason, details = excluded.details
  returning id into v_id;
  return v_id;
end $$;

-- =====================================================================================
-- 5. Message insert validation
-- =====================================================================================

-- W-13 Rows inserted straight into mm_messages were only checked for sender_id and membership:
--      a member could forge 'system'/'call' rows, back- or forward-date sent_at (to dodge the
--      24 h edit window and reorder history), name another user's device, reply to or attach
--      content from a different conversation, and publish an "edit" for somebody else's message.
--      The guard runs only for statements issued directly by an API role: the trigger's WHEN
--      clause is evaluated as the statement's user, so rows written by security-definer RPCs
--      (system messages) are not subject to it.
create or replace function public.mm_guard_client_message() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_target uuid;
begin
  if new.kind not in ('text','file','voice','image','video','audio','text-edit') then
    raise exception 'kind_not_allowed' using errcode = 'P0024';
  end if;
  new.sent_at := now();
  new.edited_at := null;
  new.deleted_at := null;
  if new.sent_at_ms is null or new.sent_at_ms <= 0
     or new.sent_at_ms > (extract(epoch from now()) * 1000)::bigint + 300000 then
    new.sent_at_ms := (extract(epoch from now()) * 1000)::bigint;
  end if;
  if new.sender_device_row_id is not null and not exists (
    select 1 from public.mm_devices d
     where d.id = new.sender_device_row_id and d.user_id = v_uid and d.revoked_at is null
  ) then
    raise exception 'device_not_found' using errcode = 'P0004';
  end if;
  if new.reply_to is not null and not exists (
    select 1 from public.mm_messages r
     where r.id = new.reply_to and r.conversation_id = new.conversation_id
  ) then
    raise exception 'message_not_found' using errcode = 'P0022';
  end if;
  if new.attachment_id is not null and not exists (
    select 1 from public.mm_attachments a
     where a.id = new.attachment_id and a.conversation_id = new.conversation_id
       and a.uploader_id = v_uid
  ) then
    raise exception 'attachment_not_found' using errcode = 'P0025';
  end if;
  if new.kind = 'text-edit' then
    v_target := public.mm_try_uuid(new.meta ->> 'target');
    if v_target is null or not exists (
      select 1 from public.mm_messages t
       where t.id = v_target and t.conversation_id = new.conversation_id
         and t.sender_id = v_uid and t.deleted_at is null
         and t.kind <> 'text-edit'
    ) then
      raise exception 'edit_not_allowed' using errcode = 'P0026';
    end if;
  end if;
  if pg_column_size(new.meta) > 8192 then
    raise exception 'meta_too_large' using errcode = 'P0027';
  end if;
  return new;
end $$;

drop trigger if exists mm_messages_client_guard on public.mm_messages;
create trigger mm_messages_client_guard before insert on public.mm_messages
  for each row when (current_user in ('authenticated', 'anon'))
  execute function public.mm_guard_client_message();

-- Call signalling payloads (SDP, ICE candidates) are small; anything larger is abuse of a table
-- that is fanned out over Realtime.
do $c$
begin
  if not exists (select 1 from pg_constraint
                  where conname = 'mm_call_signals_payload_size'
                    and conrelid = 'public.mm_call_signals'::regclass) then
    alter table public.mm_call_signals
      add constraint mm_call_signals_payload_size check (pg_column_size(payload) <= 65536) not valid;
  end if;
end $c$;

-- =====================================================================================
-- 6. Storage quota for the encrypted media bucket
-- =====================================================================================

-- W-14 Any member could upload an unlimited number of 256 MB objects (cost / storage DoS).
create or replace function mm_private.media_quota_ok(p_user uuid) returns boolean
language sql stable security definer set search_path = public, storage, pg_temp as $$
  select p_user is not null and (
    select coalesce(sum(coalesce((o.metadata ->> 'size')::bigint, 0)), 0) < 5368709120  -- 5 GiB
           and count(*) < 20000
      from storage.objects o
     where o.bucket_id = 'mm-media' and (o.owner = p_user or o.owner_id = p_user::text)
  );
$$;

create or replace function public.mm_media_upload_allowed(p_name text) returns boolean
language plpgsql stable security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_conversation uuid := public.mm_try_uuid(split_part(coalesce(p_name, ''), '/', 1));
begin
  if v_uid is null or v_conversation is null then
    return false;
  end if;
  -- "<conversation uuid>/<opaque object id>": exactly two non-empty path segments.
  if p_name !~ '^[0-9a-fA-F-]{36}/[A-Za-z0-9._-]{8,128}$' then
    return false;
  end if;
  return public.mm_can_post(v_conversation, v_uid) and mm_private.media_quota_ok(v_uid);
end $$;

do $storage$
begin
  if to_regclass('storage.objects') is not null then
    drop policy if exists "mm_media_write" on storage.objects;
    create policy "mm_media_write" on storage.objects for insert to authenticated
      with check (bucket_id = 'mm-media' and public.mm_media_upload_allowed(name));
  end if;
end
$storage$;

-- =====================================================================================
-- 7. Row level security: replace permissive write policies
-- =====================================================================================

-- W-10 Conversations and memberships were insertable/updatable/deletable directly:
--      * a client could insert a 'direct' conversation with an arbitrary direct_key and so squat
--        the pair of two *other* users (their mm_start_direct_conversation then returned a
--        conversation neither of them belonged to),
--      * the owner of a direct chat could add a third member (silent eavesdropper),
--      * an admin could add anyone, skipping the block list, the 500 member cap and the limiter,
--      * an admin could delete a membership without the sender-key epoch rotation, so the removed
--        member kept a valid group key for future messages,
--      * an admin could rewrite owner_id / type / encryption_version / sender_key_epoch.
drop policy if exists mm_conversations_insert on public.mm_conversations;
drop policy if exists mm_conversations_update on public.mm_conversations;
drop policy if exists mm_conversations_delete on public.mm_conversations;
drop policy if exists mm_members_insert on public.mm_conversation_members;
drop policy if exists mm_members_delete on public.mm_conversation_members;

-- W-15 An admin could change other members' notification level / mute state.
drop policy if exists mm_members_update on public.mm_conversation_members;
create policy mm_members_update on public.mm_conversation_members for update to authenticated
  using (user_id = auth.uid() and public.mm_is_member(conversation_id, auth.uid()))
  with check (user_id = auth.uid());

-- W-16 Devices: a direct INSERT skipped the five-device cap and the limiter, and a direct UPDATE
--      could clear revoked_at. Pre-keys could be inserted directly without any cap.
drop policy if exists mm_devices_insert on public.mm_devices;
drop policy if exists mm_devices_update on public.mm_devices;
drop policy if exists mm_prekeys_insert on public.mm_device_prekeys;
drop policy if exists mm_prekeys_delete on public.mm_device_prekeys;

-- W-17 Calls: any conversation member could UPDATE mm_calls directly (resurrect an ended call,
--      change the initiator) and participants could rewrite each other's rows.
drop policy if exists mm_calls_insert on public.mm_calls;
drop policy if exists mm_calls_update on public.mm_calls;
drop policy if exists mm_call_participants_update on public.mm_call_participants;

-- W-18 Contact requests: the *sender* could set their own request to 'accepted'.
drop policy if exists mm_requests_insert on public.mm_contact_requests;
create policy mm_requests_insert on public.mm_contact_requests for insert to authenticated
  with check (from_user = auth.uid() and status = 'pending'
              and not public.mm_is_blocked(from_user, to_user));
drop policy if exists mm_requests_update on public.mm_contact_requests;
create policy mm_requests_update on public.mm_contact_requests for update to authenticated
  using (to_user = auth.uid() and status = 'pending')
  with check (to_user = auth.uid() and status in ('accepted','declined','blocked'));

-- Profiles and reports are created by RPCs only; push tokens are managed by RPCs only.
drop policy if exists mm_profiles_insert on public.mm_profiles;
drop policy if exists mm_reports_insert on public.mm_reports;
drop policy if exists mm_push_tokens_insert on public.mm_push_tokens;
drop policy if exists mm_push_tokens_update on public.mm_push_tokens;
drop policy if exists mm_push_tokens_delete on public.mm_push_tokens;

-- W-19 Envelopes: the recipient device was not checked against the recipient user (ciphertext
--      could be parked on someone else's device row or on a revoked device) and sender_device
--      was unchecked. The sender of the message can now always see/delete the envelopes they
--      wrote, which the client's "insert ... returning" needs even without sender_device.
drop policy if exists mm_envelopes_insert on public.mm_message_envelopes;
create policy mm_envelopes_insert on public.mm_message_envelopes for insert to authenticated
  with check (
    exists (
      select 1 from public.mm_messages msg
       where msg.id = message_id and msg.sender_id = auth.uid()
         and public.mm_is_member(msg.conversation_id, recipient_user_id))
    and exists (
      select 1 from public.mm_devices rd
       where rd.id = recipient_device and rd.user_id = recipient_user_id and rd.revoked_at is null)
    and (sender_device is null or exists (
      select 1 from public.mm_devices sd
       where sd.id = sender_device and sd.user_id = auth.uid() and sd.revoked_at is null))
  );

drop policy if exists mm_envelopes_select on public.mm_message_envelopes;
create policy mm_envelopes_select on public.mm_message_envelopes for select to authenticated
  using (
    (recipient_user_id = auth.uid() and not public.mm_private_session_revoked())
    or exists (select 1 from public.mm_messages msg
                where msg.id = message_id and msg.sender_id = auth.uid())
  );

drop policy if exists mm_envelopes_delete on public.mm_message_envelopes;
create policy mm_envelopes_delete on public.mm_message_envelopes for delete to authenticated
  using (
    recipient_user_id = auth.uid()
    or exists (select 1 from public.mm_messages msg
                where msg.id = message_id and msg.sender_id = auth.uid())
  );

-- A receipt may only ever point at a message of a conversation the caller belongs to; the
-- UPDATE path (used by the client's upsert) previously checked the owner only.
drop policy if exists mm_receipts_update on public.mm_message_receipts;
create policy mm_receipts_update on public.mm_message_receipts for update to authenticated
  using (user_id = auth.uid())
  with check (user_id = auth.uid() and exists (
    select 1 from public.mm_messages m
     where m.id = message_id and public.mm_is_member(m.conversation_id, auth.uid())));

-- Attachments must point into the caller's own conversation prefix of the encrypted bucket.
drop policy if exists mm_attachments_insert on public.mm_attachments;
create policy mm_attachments_insert on public.mm_attachments for insert to authenticated
  with check (
    uploader_id = auth.uid()
    and public.mm_can_post(conversation_id, auth.uid())
    and bucket = 'mm-media'
    and object_path like conversation_id::text || '/%'
    and object_path ~ '^[0-9a-fA-F-]{36}/[A-Za-z0-9._-]{8,128}$'
  );

-- Typing indicators expire quickly; a client may not park one for a year.
drop policy if exists mm_typing_all on public.mm_typing;
create policy mm_typing_all on public.mm_typing for all to authenticated
  using (user_id = auth.uid() and public.mm_is_member(conversation_id, auth.uid()))
  with check (user_id = auth.uid() and public.mm_can_send_to(conversation_id, auth.uid())
              and expires_at <= now() + interval '30 seconds');

-- =====================================================================================
-- 8. Least-privilege grants
-- =====================================================================================

do $grants$
declare
  t text;
  f regprocedure;
begin
  for t in
    select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'public' and c.relkind = 'r' and c.relname like 'mm\_%' escape '\'
  loop
    execute format('revoke all on public.%I from public, anon, authenticated', t);
    execute format('grant all on public.%I to service_role', t);
  end loop;

  for f in
    select p.oid::regprocedure from pg_proc p join pg_namespace n on n.oid = p.pronamespace
     where n.nspname = 'mm_private'
  loop
    execute format('revoke all on function %s from public, anon, authenticated', f);
    execute format('grant execute on function %s to service_role', f);
  end loop;
end
$grants$;

revoke all on function public.mm_private_session_revoked() from public, anon;
grant execute on function public.mm_private_session_revoked() to authenticated, service_role;
revoke all on function public.mm_media_upload_allowed(text) from public, anon;
grant execute on function public.mm_media_upload_allowed(text) to authenticated, service_role;
revoke all on function public.mm_register_device(text, text, text, text, integer, jsonb, jsonb, jsonb) from public, anon;
grant execute on function public.mm_register_device(text, text, text, text, integer, jsonb, jsonb, jsonb) to authenticated, service_role;
revoke all on function public.mm_revoke_device(uuid) from public, anon;
grant execute on function public.mm_revoke_device(uuid) to authenticated, service_role;
revoke all on function public.mm_replenish_prekeys(text, jsonb) from public, anon;
grant execute on function public.mm_replenish_prekeys(text, jsonb) to authenticated, service_role;
revoke all on function public.mm_start_direct_conversation(uuid) from public, anon;
grant execute on function public.mm_start_direct_conversation(uuid) to authenticated, service_role;
revoke all on function public.mm_set_read(uuid, uuid) from public, anon;
grant execute on function public.mm_set_read(uuid, uuid) to authenticated, service_role;
revoke all on function public.mm_search_users(text) from public, anon;
grant execute on function public.mm_search_users(text) to authenticated, service_role;
revoke all on function public.mm_search_public_channels(text) from public, anon;
grant execute on function public.mm_search_public_channels(text) to authenticated, service_role;
revoke all on function public.mm_report(text, uuid, text, text) from public, anon;
grant execute on function public.mm_report(text, uuid, text, text) to authenticated, service_role;
revoke all on function public.mm_is_member(uuid, uuid) from public, anon;
grant execute on function public.mm_is_member(uuid, uuid) to authenticated, service_role;
revoke all on function public.mm_can_post(uuid, uuid) from public, anon;
grant execute on function public.mm_can_post(uuid, uuid) to authenticated, service_role;
revoke all on function public.mm_shares_conversation(uuid, uuid) from public, anon;
grant execute on function public.mm_shares_conversation(uuid, uuid) to authenticated, service_role;
revoke all on function public.mm_before_message_insert() from public, anon, authenticated;
revoke all on function public.mm_guard_client_message() from public, anon, authenticated;

-- Read access (RLS still decides which rows).
grant select on
  public.mm_profiles, public.mm_devices, public.mm_conversations, public.mm_conversation_members,
  public.mm_messages, public.mm_message_envelopes, public.mm_sender_key_distributions,
  public.mm_attachments, public.mm_message_receipts, public.mm_typing, public.mm_reactions,
  public.mm_blocks, public.mm_contacts, public.mm_contact_requests, public.mm_notifications,
  public.mm_notification_prefs, public.mm_user_settings, public.mm_reports, public.mm_push_tokens,
  public.mm_calls, public.mm_call_participants, public.mm_call_signals
to authenticated;

-- Direct writes the app performs; everything else goes through security-definer RPCs.
grant update (display_name, avatar_url, bio, locale) on public.mm_profiles to authenticated;
grant update (muted_until, last_read_message_id, notification_level)
  on public.mm_conversation_members to authenticated;
grant insert (conversation_id, sender_id, sender_device_id, sender_device_row_id, client_msg_id,
              kind, meta, reply_to, attachment_id, group_ciphertext, group_cipher_epoch,
              group_distribution_id, sent_at_ms)
  on public.mm_messages to authenticated;
grant update (edited_at, deleted_at) on public.mm_messages to authenticated;
grant insert (message_id, recipient_user_id, recipient_device, sender_device, cipher_type, ciphertext),
      delete on public.mm_message_envelopes to authenticated;
grant insert (conversation_id, epoch, distribution_id, sender_user_id, sender_device,
              recipient_user_id, recipient_device, cipher_type, ciphertext),
      delete on public.mm_sender_key_distributions to authenticated;
grant insert (id, conversation_id, uploader_id, bucket, object_path, file_name, mime_type,
              size_bytes, sha256, encryption),
      delete on public.mm_attachments to authenticated;
grant insert (message_id, user_id, device_id, delivered_at, read_at),
      update (message_id, user_id, device_id, delivered_at, read_at) on public.mm_message_receipts to authenticated;
grant insert, update, delete on public.mm_typing to authenticated;
grant insert (message_id, user_id, emoji), delete on public.mm_reactions to authenticated;
grant insert (blocker_id, blocked_id), delete on public.mm_blocks to authenticated;
grant insert (owner_id, contact_id, alias), update (alias), delete on public.mm_contacts to authenticated;
grant insert (from_user, to_user, message), update (status, responded_at), delete
  on public.mm_contact_requests to authenticated;
grant update (read_at), delete on public.mm_notifications to authenticated;
grant insert (user_id, enabled, direct_enabled, group_enabled, channel_enabled, calls_enabled,
              quiet_hours_start, quiet_hours_end),
      update (user_id, enabled, direct_enabled, group_enabled, channel_enabled, calls_enabled,
              quiet_hours_start, quiet_hours_end)
  on public.mm_notification_prefs to authenticated;
grant insert (user_id, privacy, notifications), update (user_id, privacy, notifications)
  on public.mm_user_settings to authenticated;
grant delete on public.mm_call_signals to authenticated;

do $sequences$
begin
  if to_regclass('public.mm_message_envelopes_id_seq') is not null then
    grant usage on sequence public.mm_message_envelopes_id_seq to authenticated;
  end if;
  if to_regclass('public.mm_sender_key_distributions_id_seq') is not null then
    grant usage on sequence public.mm_sender_key_distributions_id_seq to authenticated;
  end if;
end
$sequences$;
