-- Messenger (mm_*) - privacy scoping, scalable media quota, pinned messages, disappearing
-- messages and group/channel administration.
--
-- Scope: only `mm_*` tables, `mm_private.*` / `public.mm_*` functions, policies named `mm_*`
-- (including the `mm_media_*` policies and three `mm_media_usage_track_*` triggers on
-- `storage.objects`, all restricted to bucket `mm-media` by their WHEN clause) and one pg_cron job named
-- `mm_purge_expired_messages`. Nothing belonging to Luma is read, altered or dropped.
-- Idempotent and additive: safe to run twice.
--
-- Findings closed here (docs/AUDIT.md section A.7):
--   P-1  A profile was readable by anyone who inserted the owner's id into their own contact
--        list, or who sent a contact request - consent of the profile owner was never needed
--        and the block list was ignored. `last_seen_at` was readable with the rest of the row,
--        so this was a presence tracker for arbitrary accounts.
--   P-2  "Shares a conversation" included public channels: joining a public channel exposed the
--        profile, the device directory (identity keys, device names, platform) and the
--        membership row of every other subscriber.
--   P-3  Membership rows exposed every member's private `muted_until` / `notification_level`.
--   Q-1  The media quota scanned the whole `mm-media` bucket on every upload.
--   D-1  There was no server-enforced expiry for messages.
--   G-1  Roles, titles, ownership, invites and bans could not be administered at all after the
--        direct-write path was closed in 20261112120000.

-- =====================================================================================
-- 1. Who may see whom
-- =====================================================================================

-- A conversation makes two members visible to each other when it is a direct chat or a group.
-- In a channel only the staff (owner, admins) are visible to subscribers, and subscribers are
-- visible to the staff - never to each other.
create or replace function mm_private.shares_visible_conversation(p_viewer uuid, p_target uuid)
returns boolean language sql stable security definer set search_path = public, pg_temp as $$
  select exists (
    select 1
      from public.mm_conversation_members a
      join public.mm_conversation_members b on b.conversation_id = a.conversation_id
      join public.mm_conversations c on c.id = a.conversation_id
     where a.user_id = p_viewer and b.user_id = p_target
       and c.deleted_at is null
       and (c.type <> 'channel'
            or a.role in ('owner', 'admin')
            or b.role in ('owner', 'admin'))
  );
$$;

create or replace function mm_private.can_see_user(p_viewer uuid, p_target uuid)
returns boolean language sql stable security definer set search_path = public, pg_temp as $$
  select p_viewer is not null and p_target is not null and (
    p_viewer = p_target
    -- Group and channel-staff co-membership stays visible even across a block: the client has
    -- to be able to label the messages both parties can already read.
    or exists (
      select 1
        from public.mm_conversation_members a
        join public.mm_conversation_members b on b.conversation_id = a.conversation_id
        join public.mm_conversations c on c.id = a.conversation_id
       where a.user_id = p_viewer and b.user_id = p_target and c.deleted_at is null
         and (c.type = 'group'
              or (c.type = 'channel' and (a.role in ('owner', 'admin') or b.role in ('owner', 'admin'))))
    )
    or (
      not mm_private.is_blocked(p_viewer, p_target)
      and (
        exists (
          select 1
            from public.mm_conversation_members a
            join public.mm_conversation_members b on b.conversation_id = a.conversation_id
            join public.mm_conversations c on c.id = a.conversation_id
           where a.user_id = p_viewer and b.user_id = p_target
             and c.deleted_at is null and c.type = 'direct'
        )
        -- A contact entry only counts when it is mutual: both sides chose each other.
        or (exists (select 1 from public.mm_contacts c
                     where c.owner_id = p_viewer and c.contact_id = p_target)
            and exists (select 1 from public.mm_contacts c
                         where c.owner_id = p_target and c.contact_id = p_viewer))
        -- The recipient of a pending request may see who is asking; the sender learns nothing
        -- about the recipient until the request is accepted.
        or exists (select 1 from public.mm_contact_requests r
                    where r.status = 'pending' and r.from_user = p_target and r.to_user = p_viewer)
      )
    )
  );
$$;

revoke all on function mm_private.shares_visible_conversation(uuid, uuid) from public, anon, authenticated;
revoke all on function mm_private.can_see_user(uuid, uuid) from public, anon, authenticated;

-- Caller-scoped wrapper used by the policies: it only ever answers for auth.uid().
create or replace function public.mm_can_see_user(p_target uuid) returns boolean
language plpgsql stable security definer set search_path = public, pg_temp as $$
declare
  v_caller uuid := auth.uid();
begin
  if v_caller is null or p_target is null then
    return false;
  end if;
  if v_caller = p_target then
    return true;
  end if;
  if mm_private.session_revoked() then
    return false;
  end if;
  return mm_private.can_see_user(v_caller, p_target);
end $$;

-- May the caller read the membership row (p_conversation, p_member) whose role is p_role?
create or replace function public.mm_member_visible(p_conversation uuid, p_member uuid, p_role text)
returns boolean
language plpgsql stable security definer set search_path = public, pg_temp as $$
declare
  v_caller uuid := auth.uid();
  v_role text;
  v_type text;
begin
  if v_caller is null or p_conversation is null or p_member is null then
    return false;
  end if;
  if v_caller = p_member then
    return true;
  end if;
  if mm_private.session_revoked() then
    return false;
  end if;
  v_role := mm_private.member_role(p_conversation, v_caller);
  if v_role is null then
    return false;
  end if;
  select c.type into v_type from public.mm_conversations c where c.id = p_conversation;
  if v_type is distinct from 'channel' then
    return true;
  end if;
  return v_role in ('owner', 'admin') or p_role in ('owner', 'admin');
end $$;

drop policy if exists mm_profiles_select on public.mm_profiles;
create policy mm_profiles_select on public.mm_profiles for select to authenticated
  using (id = auth.uid() or public.mm_can_see_user(id));

drop policy if exists mm_devices_select on public.mm_devices;
create policy mm_devices_select on public.mm_devices for select to authenticated
  using (user_id = auth.uid() or public.mm_can_see_user(user_id));

drop policy if exists mm_members_select on public.mm_conversation_members;
create policy mm_members_select on public.mm_conversation_members for select to authenticated
  using (user_id = auth.uid() or public.mm_member_visible(conversation_id, user_id, role));

-- Column privileges. `last_seen_at` is not exposed to clients at all (no client reads it; the
-- push dispatcher and maintenance jobs use the service role). Other members' mute state and
-- notification level are private preferences; the owner reads them through mm_my_memberships().
revoke select on public.mm_profiles from authenticated;
grant select (id, username, display_name, avatar_url, bio, locale, created_at, updated_at, deleted_at)
  on public.mm_profiles to authenticated;

revoke select on public.mm_conversation_members from authenticated;
grant select (conversation_id, user_id, role, joined_at, last_read_message_id)
  on public.mm_conversation_members to authenticated;

-- The caller's own membership rows, including the private columns. This is what lets a second
-- device pick up mute changes made on the first.
create or replace function public.mm_my_memberships()
returns table (
  conversation_id uuid, role text, muted_until timestamptz,
  notification_level text, last_read_message_id uuid
)
language plpgsql stable security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  if mm_private.session_revoked() then
    raise exception 'device_revoked' using errcode = 'P0019';
  end if;
  return query
    select m.conversation_id, m.role, m.muted_until, m.notification_level, m.last_read_message_id
      from public.mm_conversation_members m
     where m.user_id = v_uid;
end $$;

-- =====================================================================================
-- 2. Media quota that does not scan the bucket
-- =====================================================================================

create table if not exists public.mm_media_usage (
  user_id      uuid primary key,
  object_count bigint not null default 0 check (object_count >= 0),
  total_bytes  bigint not null default 0 check (total_bytes >= 0),
  updated_at   timestamptz not null default now()
);
alter table public.mm_media_usage enable row level security;
drop policy if exists mm_media_usage_select on public.mm_media_usage;
create policy mm_media_usage_select on public.mm_media_usage for select to authenticated
  using (user_id = auth.uid());
revoke all on public.mm_media_usage from anon, authenticated;
grant select on public.mm_media_usage to authenticated;
grant all on public.mm_media_usage to service_role;

create or replace function mm_private.media_object_size(p_metadata jsonb) returns bigint
language plpgsql immutable set search_path = pg_catalog as $$
begin
  return greatest(coalesce((p_metadata ->> 'size')::bigint, 0), 0);
exception when others then
  return 0;
end $$;

create or replace function mm_private.track_media_usage() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_old_owner uuid;
  v_new_owner uuid;
  v_old_size bigint := 0;
  v_new_size bigint := 0;
begin
  if tg_op in ('UPDATE', 'DELETE') and old.bucket_id = 'mm-media' then
    v_old_owner := coalesce(old.owner, public.mm_try_uuid(old.owner_id::text));
    v_old_size := mm_private.media_object_size(old.metadata);
  end if;
  if tg_op in ('INSERT', 'UPDATE') and new.bucket_id = 'mm-media' then
    v_new_owner := coalesce(new.owner, public.mm_try_uuid(new.owner_id::text));
    v_new_size := mm_private.media_object_size(new.metadata);
  end if;

  if tg_op = 'UPDATE' and v_old_owner is not distinct from v_new_owner then
    if v_new_owner is not null and v_new_size <> v_old_size then
      update public.mm_media_usage
         set total_bytes = greatest(total_bytes + v_new_size - v_old_size, 0), updated_at = now()
       where user_id = v_new_owner;
    end if;
    return null;
  end if;

  if v_old_owner is not null then
    update public.mm_media_usage
       set object_count = greatest(object_count - 1, 0),
           total_bytes = greatest(total_bytes - v_old_size, 0),
           updated_at = now()
     where user_id = v_old_owner;
    -- No row is kept for an account with nothing stored (also covers account deletion).
    delete from public.mm_media_usage where user_id = v_old_owner and object_count = 0;
  end if;
  if v_new_owner is not null then
    insert into public.mm_media_usage as u (user_id, object_count, total_bytes)
    values (v_new_owner, 1, v_new_size)
    on conflict (user_id) do update
      set object_count = u.object_count + 1,
          total_bytes = u.total_bytes + excluded.total_bytes,
          updated_at = now();
  end if;
  return null;
end $$;

-- Recomputes every counter from the bucket. Run by the migration, and available to the owner
-- (service role) as a reconciliation step; it is the only statement here that scans the bucket.
create or replace function mm_private.rebuild_media_usage() returns bigint
language plpgsql security definer set search_path = public, storage, pg_temp as $$
declare
  v_rows bigint := 0;
begin
  if to_regclass('storage.objects') is null then
    return 0;
  end if;
  lock table public.mm_media_usage in exclusive mode;
  delete from public.mm_media_usage;
  insert into public.mm_media_usage (user_id, object_count, total_bytes)
  select s.owner_uuid, count(*), coalesce(sum(s.size_bytes), 0)
    from (
      select coalesce(o.owner, public.mm_try_uuid(o.owner_id::text)) as owner_uuid,
             mm_private.media_object_size(o.metadata) as size_bytes
        from storage.objects o
       where o.bucket_id = 'mm-media'
    ) s
   where s.owner_uuid is not null
   group by s.owner_uuid;
  get diagnostics v_rows = row_count;
  return v_rows;
end $$;

create or replace function mm_private.media_usage_tracked() returns boolean
language sql stable security definer set search_path = pg_catalog, pg_temp as $$
  select to_regclass('storage.objects') is not null and (
    select count(*) from pg_trigger t
     where t.tgrelid = to_regclass('storage.objects')
       and t.tgname in ('mm_media_usage_track_ins', 'mm_media_usage_track_upd',
                        'mm_media_usage_track_del')
       and t.tgenabled <> 'D'
  ) = 3;
$$;

-- Q-1 One primary-key lookup when the counters are maintained by the trigger; the bucket scan
--     only remains as a fallback for an installation where the trigger could not be created.
create or replace function mm_private.media_quota_ok(p_user uuid) returns boolean
language plpgsql stable security definer set search_path = public, storage, pg_temp as $$
declare
  v_count bigint;
  v_bytes bigint;
begin
  if p_user is null then
    return false;
  end if;
  if mm_private.media_usage_tracked() then
    select u.object_count, u.total_bytes into v_count, v_bytes
      from public.mm_media_usage u where u.user_id = p_user;
  else
    select count(*), coalesce(sum(mm_private.media_object_size(o.metadata)), 0)
      into v_count, v_bytes
      from storage.objects o
     where o.bucket_id = 'mm-media'
       and (o.owner = p_user or o.owner_id::text = p_user::text);
  end if;
  return coalesce(v_count, 0) < 20000 and coalesce(v_bytes, 0) < 5368709120;  -- 5 GiB
end $$;

-- Every mm-media object an account owns, for the account-deletion Edge Function (service role
-- only). mm_attachments is not a reliable source: a row can be deleted while the blob remains.
create or replace function public.mm_media_objects_of(p_user uuid)
returns table (object_path text)
language plpgsql stable security definer set search_path = public, storage, pg_temp as $$
begin
  if p_user is null or to_regclass('storage.objects') is null then
    return;
  end if;
  return query
    select o.name::text
      from storage.objects o
     where o.bucket_id = 'mm-media'
       and (o.owner = p_user or o.owner_id::text = p_user::text);
end $$;
revoke all on function public.mm_media_objects_of(uuid) from public, anon, authenticated;
grant execute on function public.mm_media_objects_of(uuid) to service_role;

revoke all on function mm_private.media_object_size(jsonb) from public, anon, authenticated;
revoke all on function mm_private.track_media_usage() from public, anon, authenticated;
revoke all on function mm_private.rebuild_media_usage() from public, anon, authenticated;
revoke all on function mm_private.media_usage_tracked() from public, anon, authenticated;
revoke all on function mm_private.media_quota_ok(uuid) from public, anon, authenticated;

do $storage$
begin
  if to_regclass('storage.objects') is null then
    raise notice 'mm: storage.objects is absent, media usage tracking not installed';
  elsif not has_table_privilege(current_user, 'storage.objects', 'TRIGGER') then
    raise notice 'mm: no TRIGGER privilege on storage.objects, media quota keeps the scan fallback';
  else
    -- The WHEN clauses keep these triggers from running any Messenger code for objects of
    -- other buckets: a row that belongs to Luma never reaches the function.
    drop trigger if exists mm_media_usage_track_ins on storage.objects;
    create trigger mm_media_usage_track_ins after insert on storage.objects
      for each row when (new.bucket_id = 'mm-media')
      execute function mm_private.track_media_usage();
    drop trigger if exists mm_media_usage_track_del on storage.objects;
    create trigger mm_media_usage_track_del after delete on storage.objects
      for each row when (old.bucket_id = 'mm-media')
      execute function mm_private.track_media_usage();
    drop trigger if exists mm_media_usage_track_upd on storage.objects;
    create trigger mm_media_usage_track_upd after update on storage.objects
      for each row when (old.bucket_id = 'mm-media' or new.bucket_id = 'mm-media')
      execute function mm_private.track_media_usage();
    perform mm_private.rebuild_media_usage();
  end if;
end
$storage$;

-- =====================================================================================
-- 3. Disappearing messages
-- =====================================================================================

alter table public.mm_conversations add column if not exists message_ttl_seconds integer;
alter table public.mm_messages add column if not exists expires_at timestamptz;

do $c$
begin
  if not exists (select 1 from pg_constraint
                  where conname = 'mm_conversations_ttl_range'
                    and conrelid = 'public.mm_conversations'::regclass) then
    alter table public.mm_conversations
      add constraint mm_conversations_ttl_range
      check (message_ttl_seconds is null or message_ttl_seconds between 5 and 31536000);
  end if;
end $c$;

create index if not exists mm_messages_expires_idx
  on public.mm_messages (expires_at) where expires_at is not null;

-- D-1 The expiry is stamped by the server from the conversation's timer when the row is
--     inserted, so a modified client can neither skip it nor extend it.
create or replace function public.mm_guard_client_message() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_target uuid;
  v_ttl integer;
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
  select c.message_ttl_seconds into v_ttl
    from public.mm_conversations c where c.id = new.conversation_id;
  if new.kind = 'text-edit' and v_target is not null then
    -- An edit lives exactly as long as the message it rewrites.
    select t.expires_at into new.expires_at from public.mm_messages t where t.id = v_target;
  elsif v_ttl is not null then
    new.expires_at := now() + make_interval(secs => v_ttl);
  else
    new.expires_at := null;
  end if;
  return new;
end $$;
revoke all on function public.mm_guard_client_message() from public, anon, authenticated;

create or replace function public.mm_message_live(p_message uuid) returns boolean
language sql stable security definer set search_path = public, pg_temp as $$
  select not exists (
    select 1 from public.mm_messages m
     where m.id = p_message and m.expires_at is not null and m.expires_at <= now()
  );
$$;

-- Expired rows stop being readable the instant they expire, whether or not the purge has run.
drop policy if exists mm_messages_select on public.mm_messages;
create policy mm_messages_select on public.mm_messages for select to authenticated
  using (
    public.mm_is_member(conversation_id, auth.uid())
    and (deleted_at is null or sender_id = auth.uid())
    and (expires_at is null or expires_at > now())
  );

drop policy if exists mm_envelopes_select on public.mm_message_envelopes;
create policy mm_envelopes_select on public.mm_message_envelopes for select to authenticated
  using (
    public.mm_message_live(message_id)
    and (
      (recipient_user_id = auth.uid() and not public.mm_private_session_revoked())
      or exists (select 1 from public.mm_messages msg
                  where msg.id = message_id and msg.sender_id = auth.uid())
    )
  );

create or replace function public.mm_set_message_ttl(p_conversation uuid, p_seconds integer)
returns integer
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_role text;
  v_type text;
  v_seconds integer := nullif(coalesce(p_seconds, 0), 0);
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  if mm_private.session_revoked() then
    raise exception 'device_revoked' using errcode = 'P0019';
  end if;
  v_role := mm_private.member_role(p_conversation, v_uid);
  select c.type into v_type from public.mm_conversations c
   where c.id = p_conversation and c.deleted_at is null;
  if v_role is null or v_type is null then
    raise exception 'not_a_member' using errcode = 'P0012';
  end if;
  if v_type <> 'direct' and v_role not in ('owner', 'admin') then
    raise exception 'not_authorized' using errcode = 'P0005';
  end if;
  if v_seconds is not null and v_seconds not between 5 and 31536000 then
    raise exception 'invalid_ttl' using errcode = 'P0028';
  end if;
  perform public.mm_consume_rate_limit('conversation_admin', 60, 3600);
  update public.mm_conversations
     set message_ttl_seconds = v_seconds, updated_at = now()
   where id = p_conversation;
  return coalesce(v_seconds, 0);
end $$;

-- Housekeeping: physically removes expired rows (envelopes, receipts, reactions and pins go
-- with them through ON DELETE CASCADE) and the attachment rows they referenced. The encrypted
-- blobs themselves can only be removed through the Storage API; the sender's client does that
-- when its own copy expires, and the keys needed to read a blob are gone with the envelopes.
create or replace function mm_private.purge_expired_messages(p_limit integer default 5000)
returns integer
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_deleted integer := 0;
begin
  with doomed as (
    select m.id, m.attachment_id
      from public.mm_messages m
     where m.expires_at is not null and m.expires_at <= now()
     order by m.expires_at
     limit greatest(coalesce(p_limit, 5000), 1)
     for update skip locked
  ), gone as (
    delete from public.mm_messages m using doomed d where m.id = d.id
    returning d.attachment_id
  ), attachments as (
    delete from public.mm_attachments a
     where a.id in (select g.attachment_id from gone g where g.attachment_id is not null)
    returning 1
  )
  select (select count(*) from gone) into v_deleted;
  return coalesce(v_deleted, 0);
end $$;

create or replace function public.mm_purge_expired_messages() returns integer
language sql security definer set search_path = public, pg_temp as $$
  select mm_private.purge_expired_messages(5000);
$$;
revoke all on function mm_private.purge_expired_messages(integer) from public, anon, authenticated;
revoke all on function public.mm_purge_expired_messages() from public, anon, authenticated;
grant execute on function public.mm_purge_expired_messages() to service_role;

do $cron$
begin
  if exists (select 1 from pg_extension where extname = 'pg_cron') then
    perform cron.unschedule(j.jobid) from cron.job j where j.jobname = 'mm_purge_expired_messages';
    perform cron.schedule('mm_purge_expired_messages', '*/5 * * * *',
                          'select public.mm_purge_expired_messages()');
  else
    raise notice 'mm: pg_cron is not installed; schedule public.mm_purge_expired_messages() externally';
  end if;
end
$cron$;

-- =====================================================================================
-- 4. Pinned messages
-- =====================================================================================

create table if not exists public.mm_pinned_messages (
  conversation_id uuid not null references public.mm_conversations(id) on delete cascade,
  message_id      uuid not null references public.mm_messages(id) on delete cascade,
  pinned_by       uuid not null references public.mm_profiles(id) on delete cascade,
  pinned_at       timestamptz not null default now(),
  primary key (conversation_id, message_id)
);
create index if not exists mm_pinned_messages_message_idx on public.mm_pinned_messages (message_id);
alter table public.mm_pinned_messages enable row level security;
alter table public.mm_pinned_messages replica identity full;
drop policy if exists mm_pinned_select on public.mm_pinned_messages;
create policy mm_pinned_select on public.mm_pinned_messages for select to authenticated
  using (public.mm_is_member(conversation_id, auth.uid()));
revoke all on public.mm_pinned_messages from anon, authenticated;
grant select on public.mm_pinned_messages to authenticated;
grant all on public.mm_pinned_messages to service_role;

create or replace function public.mm_pin_message(p_message uuid, p_pinned boolean default true)
returns boolean
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_conversation uuid;
  v_kind text;
  v_type text;
  v_role text;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  if mm_private.session_revoked() then
    raise exception 'device_revoked' using errcode = 'P0019';
  end if;
  select m.conversation_id, m.kind into v_conversation, v_kind
    from public.mm_messages m
   where m.id = p_message and m.deleted_at is null
     and (m.expires_at is null or m.expires_at > now());
  if v_conversation is null then
    raise exception 'message_not_found' using errcode = 'P0022';
  end if;
  v_role := mm_private.member_role(v_conversation, v_uid);
  if v_role is null then
    -- Same answer as for a message that does not exist: no membership oracle.
    raise exception 'message_not_found' using errcode = 'P0022';
  end if;
  select c.type into v_type from public.mm_conversations c where c.id = v_conversation;
  if v_type <> 'direct' and v_role not in ('owner', 'admin') then
    raise exception 'not_authorized' using errcode = 'P0005';
  end if;
  perform public.mm_consume_rate_limit('pin', 120, 3600);
  if coalesce(p_pinned, true) then
    if v_kind in ('text-edit', 'system', 'call') then
      raise exception 'kind_not_allowed' using errcode = 'P0024';
    end if;
    if (select count(*) from public.mm_pinned_messages p
         where p.conversation_id = v_conversation and p.message_id <> p_message) >= 50 then
      raise exception 'too_many_pins' using errcode = 'P0029';
    end if;
    insert into public.mm_pinned_messages (conversation_id, message_id, pinned_by)
    values (v_conversation, p_message, v_uid)
    on conflict (conversation_id, message_id) do nothing;
    return true;
  end if;
  delete from public.mm_pinned_messages
   where conversation_id = v_conversation and message_id = p_message;
  return false;
end $$;

-- =====================================================================================
-- 5. Group and channel administration
-- =====================================================================================

create table if not exists public.mm_conversation_bans (
  conversation_id uuid not null references public.mm_conversations(id) on delete cascade,
  user_id         uuid not null references public.mm_profiles(id) on delete cascade,
  banned_by       uuid references public.mm_profiles(id) on delete set null,
  created_at      timestamptz not null default now(),
  primary key (conversation_id, user_id)
);
alter table public.mm_conversation_bans enable row level security;
drop policy if exists mm_bans_select on public.mm_conversation_bans;
create policy mm_bans_select on public.mm_conversation_bans for select to authenticated
  using (public.mm_is_admin(conversation_id, auth.uid()));
revoke all on public.mm_conversation_bans from anon, authenticated;
grant select on public.mm_conversation_bans to authenticated;
grant all on public.mm_conversation_bans to service_role;

-- Shared precondition of the administration RPCs: returns the caller's role and raises unless
-- the caller is staff of a live group or channel.
create or replace function mm_private.require_staff(p_conversation uuid, p_owner_only boolean)
returns text
language plpgsql stable security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_role text;
  v_type text;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  if mm_private.session_revoked() then
    raise exception 'device_revoked' using errcode = 'P0019';
  end if;
  v_role := mm_private.member_role(p_conversation, v_uid);
  select c.type into v_type from public.mm_conversations c
   where c.id = p_conversation and c.deleted_at is null;
  if v_role is null or v_type is null then
    raise exception 'not_a_member' using errcode = 'P0012';
  end if;
  if v_type = 'direct' then
    raise exception 'not_a_group' using errcode = 'P0016';
  end if;
  if v_role not in ('owner', 'admin') or (p_owner_only and v_role <> 'owner') then
    raise exception 'not_authorized' using errcode = 'P0005';
  end if;
  return v_role;
end $$;
revoke all on function mm_private.require_staff(uuid, boolean) from public, anon, authenticated;

create or replace function public.mm_update_conversation(
  p_conversation uuid, p_title text default null, p_description text default null
) returns boolean
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_title text := nullif(btrim(coalesce(p_title, '')), '');
begin
  perform mm_private.require_staff(p_conversation, false);
  if p_title is not null and v_title is null then
    raise exception 'invalid_title' using errcode = 'P0030';
  end if;
  if char_length(coalesce(v_title, '')) > 128 or char_length(coalesce(p_description, '')) > 512 then
    raise exception 'invalid_title' using errcode = 'P0030';
  end if;
  perform public.mm_consume_rate_limit('conversation_admin', 60, 3600);
  update public.mm_conversations
     set title = coalesce(v_title, title),
         description = case when p_description is null then description
                            else nullif(btrim(p_description), '') end,
         updated_at = now()
   where id = p_conversation;
  return true;
end $$;

-- Only the owner appoints or dismisses admins. The owner's own role only changes through
-- mm_transfer_ownership, and nobody changes their own role.
create or replace function public.mm_set_member_role(p_conversation uuid, p_member uuid, p_role text)
returns boolean
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_type text;
  v_current text;
  v_base text;
begin
  perform mm_private.require_staff(p_conversation, true);
  select c.type into v_type from public.mm_conversations c where c.id = p_conversation;
  v_base := case when v_type = 'channel' then 'subscriber' else 'member' end;
  if p_role is null or p_role not in ('admin', v_base) then
    raise exception 'invalid_role' using errcode = 'P0031';
  end if;
  if p_member = v_uid then
    raise exception 'not_authorized' using errcode = 'P0005';
  end if;
  select m.role into v_current from public.mm_conversation_members m
   where m.conversation_id = p_conversation and m.user_id = p_member;
  if v_current is null then
    return false;
  end if;
  if v_current = 'owner' then
    raise exception 'cannot_remove_owner' using errcode = 'P0011';
  end if;
  perform public.mm_consume_rate_limit('group_member_change', 60, 3600);
  update public.mm_conversation_members
     set role = p_role
   where conversation_id = p_conversation and user_id = p_member;
  update public.mm_conversations set updated_at = now() where id = p_conversation;
  return true;
end $$;

create or replace function public.mm_transfer_ownership(p_conversation uuid, p_new_owner uuid)
returns boolean
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
begin
  perform mm_private.require_staff(p_conversation, true);
  if p_new_owner is null or p_new_owner = v_uid
     or not mm_private.member_exists(p_conversation, p_new_owner) then
    raise exception 'not_a_member' using errcode = 'P0012';
  end if;
  perform public.mm_consume_rate_limit('group_member_change', 60, 3600);
  update public.mm_conversation_members set role = 'admin'
   where conversation_id = p_conversation and user_id = v_uid;
  update public.mm_conversation_members set role = 'owner'
   where conversation_id = p_conversation and user_id = p_new_owner;
  update public.mm_conversations set owner_id = p_new_owner, updated_at = now()
   where id = p_conversation;
  return true;
end $$;

-- Replaces the invite code of a private channel; every previously shared link stops working.
create or replace function public.mm_rotate_invite(p_conversation uuid) returns text
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_code text;
begin
  perform mm_private.require_staff(p_conversation, false);
  perform public.mm_consume_rate_limit('conversation_admin', 60, 3600);
  v_code := translate(encode(gen_random_bytes(12), 'base64'), '/+=', '_-');
  update public.mm_conversations
     set invite_code = v_code, updated_at = now()
   where id = p_conversation and type = 'channel' and visibility = 'private'
  returning invite_code into v_code;
  if not found then
    raise exception 'channel_not_found' using errcode = 'P0009';
  end if;
  return v_code;
end $$;

-- Removing somebody from a public channel was pointless: they could join again immediately.
-- A ban removes the membership (with the same key hygiene as mm_remove_group_member) and is
-- checked by mm_join_channel and mm_add_group_members.
create or replace function public.mm_set_ban(p_conversation uuid, p_member uuid, p_banned boolean)
returns boolean
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_role text;
  v_target text;
begin
  v_role := mm_private.require_staff(p_conversation, false);
  if p_member is null or p_member = v_uid then
    raise exception 'not_authorized' using errcode = 'P0005';
  end if;
  if not coalesce(p_banned, true) then
    delete from public.mm_conversation_bans
     where conversation_id = p_conversation and user_id = p_member;
    return false;
  end if;
  v_target := mm_private.member_role(p_conversation, p_member);
  if v_target = 'owner' then
    raise exception 'cannot_remove_owner' using errcode = 'P0011';
  end if;
  if v_target = 'admin' and v_role <> 'owner' then
    raise exception 'not_authorized' using errcode = 'P0005';
  end if;
  if not exists (select 1 from public.mm_profiles p where p.id = p_member) then
    raise exception 'user_not_found' using errcode = 'P0003';
  end if;
  perform public.mm_consume_rate_limit('group_member_change', 60, 3600);
  insert into public.mm_conversation_bans (conversation_id, user_id, banned_by)
  values (p_conversation, p_member, v_uid)
  on conflict (conversation_id, user_id) do nothing;
  if v_target is not null then
    delete from public.mm_conversation_members
     where conversation_id = p_conversation and user_id = p_member;
    delete from public.mm_message_envelopes e
     using public.mm_messages m
     where e.message_id = m.id and m.conversation_id = p_conversation
       and e.recipient_user_id = p_member;
    delete from public.mm_sender_key_distributions
     where conversation_id = p_conversation
       and (recipient_user_id = p_member or sender_user_id = p_member);
    update public.mm_conversations
       set sender_key_epoch = sender_key_epoch + 1,
           distribution_id = gen_random_uuid(),
           updated_at = now()
     where id = p_conversation and type = 'group';
  end if;
  return true;
end $$;

create or replace function public.mm_join_channel(p_conversation uuid, p_invite text default null)
returns boolean
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_conversation public.mm_conversations;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  if mm_private.session_revoked() then
    raise exception 'device_revoked' using errcode = 'P0019';
  end if;
  select * into v_conversation from public.mm_conversations
   where id = p_conversation and type = 'channel' and deleted_at is null;
  -- A banned account gets the same answer as for a channel that does not exist.
  if v_conversation.id is null or exists (
    select 1 from public.mm_conversation_bans b
     where b.conversation_id = p_conversation and b.user_id = v_uid
  ) then
    raise exception 'channel_not_found' using errcode = 'P0009';
  end if;
  if v_conversation.visibility = 'private'
     and (p_invite is null or v_conversation.invite_code is distinct from p_invite) then
    raise exception 'invite_required' using errcode = 'P0010';
  end if;
  perform public.mm_consume_rate_limit('channel_join', 60, 3600);
  insert into public.mm_conversation_members (conversation_id, user_id, role)
  values (p_conversation, v_uid, 'subscriber') on conflict do nothing;
  return true;
end $$;

-- An admin may no longer remove another admin; that takes the owner.
create or replace function public.mm_remove_group_member(p_conversation uuid, p_member uuid)
returns boolean
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_role text;
  v_target_role text;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  if mm_private.session_revoked() then
    raise exception 'device_revoked' using errcode = 'P0019';
  end if;
  v_role := mm_private.member_role(p_conversation, v_uid);
  if v_role is null or v_role not in ('owner', 'admin') then
    raise exception 'not_authorized' using errcode = 'P0005';
  end if;
  select role into v_target_role from public.mm_conversation_members
   where conversation_id = p_conversation and user_id = p_member;
  if v_target_role is null then
    return false;
  end if;
  if v_target_role = 'owner' then
    raise exception 'cannot_remove_owner' using errcode = 'P0011';
  end if;
  if v_target_role = 'admin' and v_role <> 'owner' and p_member <> v_uid then
    raise exception 'not_authorized' using errcode = 'P0005';
  end if;
  perform public.mm_consume_rate_limit('group_member_change', 60, 3600);

  delete from public.mm_conversation_members
   where conversation_id = p_conversation and user_id = p_member;

  delete from public.mm_message_envelopes e
   using public.mm_messages m
   where e.message_id = m.id
     and m.conversation_id = p_conversation
     and e.recipient_user_id = p_member;

  delete from public.mm_sender_key_distributions
   where conversation_id = p_conversation
     and (recipient_user_id = p_member or sender_user_id = p_member);

  update public.mm_conversations
     set sender_key_epoch = sender_key_epoch + 1,
         distribution_id = gen_random_uuid(),
         updated_at = now()
   where id = p_conversation and type = 'group';

  return true;
end $$;

-- A banned account cannot be added back by another admin either.
create or replace function mm_private.reject_banned_member() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  if exists (select 1 from public.mm_conversation_bans b
              where b.conversation_id = new.conversation_id and b.user_id = new.user_id) then
    raise exception 'member_banned' using errcode = 'P0032';
  end if;
  return new;
end $$;
revoke all on function mm_private.reject_banned_member() from public, anon, authenticated;
drop trigger if exists mm_members_reject_banned on public.mm_conversation_members;
create trigger mm_members_reject_banned before insert on public.mm_conversation_members
  for each row execute function mm_private.reject_banned_member();

-- =====================================================================================
-- 6. Grants and Realtime
-- =====================================================================================

do $grants$
declare
  f text;
begin
  foreach f in array array[
    'public.mm_can_see_user(uuid)',
    'public.mm_member_visible(uuid, uuid, text)',
    'public.mm_my_memberships()',
    'public.mm_message_live(uuid)',
    'public.mm_set_message_ttl(uuid, integer)',
    'public.mm_pin_message(uuid, boolean)',
    'public.mm_update_conversation(uuid, text, text)',
    'public.mm_set_member_role(uuid, uuid, text)',
    'public.mm_transfer_ownership(uuid, uuid)',
    'public.mm_rotate_invite(uuid)',
    'public.mm_set_ban(uuid, uuid, boolean)',
    'public.mm_join_channel(uuid, text)',
    'public.mm_remove_group_member(uuid, uuid)'
  ] loop
    execute format('revoke all on function %s from public, anon', f);
    execute format('grant execute on function %s to authenticated, service_role', f);
  end loop;
end
$grants$;

do $rt$
declare
  t text;
begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    foreach t in array array['mm_pinned_messages'] loop
      if not exists (
        select 1 from pg_publication_tables
         where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = t
      ) then
        execute format('alter publication supabase_realtime add table public.%I', t);
      end if;
    end loop;
  end if;
end
$rt$;
