-- Messenger hybrid backend — migration 5 (Phase 2):
--   1. Multi-device limits, metadata, pre-key lifecycle & remote revocation cleanup
--   2. Audited Signal Sender Keys (v2) group encryption & automatic epoch rotation
--   3. Remote push tokens, notification preferences, push dedup/expiry log (zero plaintext)
--   4. Hardened 1:1 WebRTC call signalling (state transitions, signal dedup, expiry, 1:1 guard)
--   5. E2EE media attachment lifecycle & storage cleanup helpers

-- =====================================================================
-- 1. Conversations: explicit group encryption protocol versioning
-- =====================================================================
-- Protocol v1 = pairwise per-device Signal fan-out (existing chats keep v1 — never silently broken).
-- Protocol v2 = Signal Sender Keys (GroupSessionBuilder / GroupCipher + pairwise SKDM envelopes).

alter table public.mm_conversations
  add column if not exists encryption_version smallint not null default 1
    check (encryption_version in (1, 2)),
  add column if not exists sender_key_epoch integer not null default 1
    check (sender_key_epoch >= 1),
  add column if not exists distribution_id uuid not null default gen_random_uuid();

alter table public.mm_messages
  add column if not exists sender_device_row_id uuid references public.mm_devices(id) on delete set null,
  add column if not exists group_ciphertext text
    check (group_ciphertext is null or char_length(group_ciphertext) between 8 and 1048576),
  add column if not exists group_cipher_epoch integer,
  add column if not exists group_distribution_id uuid;

create table if not exists public.mm_sender_key_distributions (
  id                bigserial primary key,
  conversation_id   uuid not null references public.mm_conversations(id) on delete cascade,
  epoch             integer not null check (epoch >= 1),
  distribution_id   uuid not null,
  sender_user_id    uuid not null references public.mm_profiles(id) on delete cascade,
  sender_device     uuid not null references public.mm_devices(id) on delete cascade,
  recipient_user_id uuid not null references public.mm_profiles(id) on delete cascade,
  recipient_device  uuid not null references public.mm_devices(id) on delete cascade,
  cipher_type       smallint not null check (cipher_type in (2, 3)),
  ciphertext        text not null check (char_length(ciphertext) between 8 and 65536),
  created_at        timestamptz not null default now(),
  unique (conversation_id, epoch, sender_device, recipient_device)
);
create index if not exists mm_skdm_recipient_idx
  on public.mm_sender_key_distributions (recipient_user_id, conversation_id, epoch);
create index if not exists mm_skdm_conv_epoch_idx
  on public.mm_sender_key_distributions (conversation_id, epoch);

alter table public.mm_sender_key_distributions enable row level security;

drop policy if exists mm_skdm_select on public.mm_sender_key_distributions;
create policy mm_skdm_select on public.mm_sender_key_distributions for select to authenticated
using (
  public.mm_is_member(conversation_id, auth.uid())
  and (recipient_user_id = auth.uid() or sender_user_id = auth.uid())
);

drop policy if exists mm_skdm_insert on public.mm_sender_key_distributions;
create policy mm_skdm_insert on public.mm_sender_key_distributions for insert to authenticated
with check (
  sender_user_id = auth.uid()
  and public.mm_can_post(conversation_id, auth.uid())
  and public.mm_is_member(conversation_id, recipient_user_id)
  and exists (
    select 1 from public.mm_devices sd
     where sd.id = mm_sender_key_distributions.sender_device
       and sd.user_id = auth.uid()
       and sd.revoked_at is null
  )
  and exists (
    select 1 from public.mm_devices rd
     where rd.id = mm_sender_key_distributions.recipient_device
       and rd.user_id = mm_sender_key_distributions.recipient_user_id
       and rd.revoked_at is null
  )
);

drop policy if exists mm_skdm_delete on public.mm_sender_key_distributions;
create policy mm_skdm_delete on public.mm_sender_key_distributions for delete to authenticated
using (recipient_user_id = auth.uid() or sender_user_id = auth.uid());

-- =====================================================================
-- 2. Push tokens, notification preferences & server push dedup log
-- =====================================================================

create table if not exists public.mm_push_tokens (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references public.mm_profiles(id) on delete cascade,
  device_id   uuid not null references public.mm_devices(id) on delete cascade,
  provider    text not null default 'fcm' check (provider in ('fcm', 'unifiedpush')),
  token       text not null check (char_length(token) between 16 and 4096),
  app_id      text not null default 'com.offlinemesh.messenger' check (char_length(app_id) <= 128),
  locale      text not null default 'en' check (char_length(locale) <= 16),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  expires_at  timestamptz not null default (now() + interval '60 days'),
  revoked_at  timestamptz,
  unique (device_id, provider),
  unique (provider, token)
);
create index if not exists mm_push_tokens_user_active_idx
  on public.mm_push_tokens (user_id) where revoked_at is null;

alter table public.mm_push_tokens enable row level security;

drop policy if exists mm_push_tokens_select on public.mm_push_tokens;
create policy mm_push_tokens_select on public.mm_push_tokens for select to authenticated
using (user_id = auth.uid());

drop policy if exists mm_push_tokens_insert on public.mm_push_tokens;
create policy mm_push_tokens_insert on public.mm_push_tokens for insert to authenticated
with check (
  user_id = auth.uid()
  and exists (
    select 1 from public.mm_devices d
     where d.id = mm_push_tokens.device_id and d.user_id = auth.uid() and d.revoked_at is null
  )
);

drop policy if exists mm_push_tokens_update on public.mm_push_tokens;
create policy mm_push_tokens_update on public.mm_push_tokens for update to authenticated
using (user_id = auth.uid())
with check (user_id = auth.uid());

drop policy if exists mm_push_tokens_delete on public.mm_push_tokens;
create policy mm_push_tokens_delete on public.mm_push_tokens for delete to authenticated
using (user_id = auth.uid());

drop trigger if exists mm_push_tokens_touch on public.mm_push_tokens;
create trigger mm_push_tokens_touch before update on public.mm_push_tokens
  for each row execute function public.mm_touch_updated_at();

create table if not exists public.mm_notification_prefs (
  user_id           uuid primary key references public.mm_profiles(id) on delete cascade,
  enabled           boolean not null default true,
  direct_enabled    boolean not null default true,
  group_enabled     boolean not null default true,
  channel_enabled   boolean not null default false,
  calls_enabled     boolean not null default true,
  quiet_hours_start smallint check (quiet_hours_start is null or quiet_hours_start between 0 and 23),
  quiet_hours_end   smallint check (quiet_hours_end is null or quiet_hours_end between 0 and 23),
  updated_at        timestamptz not null default now()
);

alter table public.mm_notification_prefs enable row level security;

drop policy if exists mm_notification_prefs_all on public.mm_notification_prefs;
create policy mm_notification_prefs_all on public.mm_notification_prefs for all to authenticated
using (user_id = auth.uid())
with check (user_id = auth.uid());

drop trigger if exists mm_notification_prefs_touch on public.mm_notification_prefs;
create trigger mm_notification_prefs_touch before update on public.mm_notification_prefs
  for each row execute function public.mm_touch_updated_at();

-- Internal push deduplication & expiration table (service-role / security-definer only).
create table if not exists public.mm_push_log (
  dedup_key   text primary key check (char_length(dedup_key) between 8 and 256),
  user_id     uuid not null references public.mm_profiles(id) on delete cascade,
  kind        text not null check (kind in ('message', 'call', 'group_invite', 'contact_request')),
  created_at  timestamptz not null default now(),
  expires_at  timestamptz not null default (now() + interval '24 hours')
);
create index if not exists mm_push_log_expires_idx on public.mm_push_log (expires_at);
alter table public.mm_push_log enable row level security;

-- =====================================================================
-- 3. Call signalling hardening: signal dedup & expiry
-- =====================================================================

alter table public.mm_call_signals
  add column if not exists client_signal_id text
    check (client_signal_id is null or char_length(client_signal_id) between 4 and 128),
  add column if not exists expires_at timestamptz not null default (now() + interval '2 minutes');

create unique index if not exists mm_call_signals_dedup_uidx
  on public.mm_call_signals (call_id, from_user, client_signal_id)
  where client_signal_id is not null;

-- =====================================================================
-- 4. Multi-device, Sender Keys, Push, Media & Call RPCs
-- =====================================================================

-- Enforce max 5 active devices per user while allowing re-registration of an existing device_id.
create or replace function public.mm_register_device(
  p_device_id text,
  p_name text,
  p_platform text,
  p_identity_key text,
  p_registration_id integer,
  p_signed jsonb,
  p_kyber jsonb,
  p_prekeys jsonb
) returns uuid
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_device uuid;
  v_active_count integer;
  v_existing uuid;
  v_key jsonb;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  if not exists (select 1 from public.mm_profiles p where p.id = v_uid and p.deleted_at is null) then
    raise exception 'profile_missing' using errcode = 'P0003';
  end if;

  select d.id into v_existing
    from public.mm_devices d
   where d.user_id = v_uid and d.device_id = p_device_id;

  if v_existing is null then
    select count(*) into v_active_count
      from public.mm_devices d
     where d.user_id = v_uid and d.revoked_at is null;
    if v_active_count >= 5 then
      raise exception 'device_limit_reached' using errcode = 'P0014';
    end if;
  end if;

  perform public.mm_consume_rate_limit('device_register', 12, 3600);

  insert into public.mm_devices (
    user_id, device_id, name, platform, identity_key, registration_id,
    signed_prekey_id, signed_prekey_public, signed_prekey_signature,
    kyber_prekey_id, kyber_prekey_public, kyber_prekey_signature, last_seen_at
  ) values (
    v_uid, p_device_id, coalesce(p_name, ''), coalesce(p_platform, 'android'), p_identity_key,
    p_registration_id,
    (p_signed->>'id')::integer, p_signed->>'public', p_signed->>'signature',
    (p_kyber->>'id')::integer, p_kyber->>'public', p_kyber->>'signature', now()
  )
  on conflict (user_id, device_id) do update set
    name = excluded.name, platform = excluded.platform, identity_key = excluded.identity_key,
    registration_id = excluded.registration_id, signed_prekey_id = excluded.signed_prekey_id,
    signed_prekey_public = excluded.signed_prekey_public,
    signed_prekey_signature = excluded.signed_prekey_signature,
    kyber_prekey_id = excluded.kyber_prekey_id, kyber_prekey_public = excluded.kyber_prekey_public,
    kyber_prekey_signature = excluded.kyber_prekey_signature,
    last_seen_at = now(), revoked_at = null
  returning id into v_device;

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

-- Rotates signed pre-key and Kyber pre-key for an active device without burning full registration.
create or replace function public.mm_rotate_signed_prekeys(
  p_device_id text,
  p_signed jsonb,
  p_kyber jsonb
) returns void
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_updated integer;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  perform public.mm_consume_rate_limit('prekey_rotate', 12, 3600);
  update public.mm_devices
     set signed_prekey_id = (p_signed->>'id')::integer,
         signed_prekey_public = p_signed->>'public',
         signed_prekey_signature = p_signed->>'signature',
         kyber_prekey_id = (p_kyber->>'id')::integer,
         kyber_prekey_public = p_kyber->>'public',
         kyber_prekey_signature = p_kyber->>'signature',
         last_seen_at = now()
   where user_id = v_uid and device_id = p_device_id and revoked_at is null;
  get diagnostics v_updated = row_count;
  if v_updated = 0 then
    raise exception 'device_not_found' using errcode = 'P0004';
  end if;
end $$;

-- Helper to rotate Sender Key epoch on every v2 group a user belongs to (e.g. on device revocation).
create or replace function public.mm_rotate_user_groups(p_user uuid) returns void
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  update public.mm_conversations c
     set sender_key_epoch = c.sender_key_epoch + 1,
         distribution_id = gen_random_uuid(),
         updated_at = now()
   where c.type = 'group'
     and c.encryption_version = 2
     and c.deleted_at is null
     and exists (
       select 1 from public.mm_conversation_members m
        where m.conversation_id = c.id and m.user_id = p_user
     );
end $$;

-- Revoking a device wipes its pre-keys, pending envelopes, SKDMs, push tokens, and rotates v2 group keys.
create or replace function public.mm_revoke_device(p_device uuid) returns void
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_updated integer;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  update public.mm_devices
     set revoked_at = now()
   where id = p_device and user_id = v_uid and revoked_at is null;
  get diagnostics v_updated = row_count;

  delete from public.mm_device_prekeys where device = p_device;
  delete from public.mm_message_envelopes where recipient_device = p_device;
  delete from public.mm_sender_key_distributions
   where recipient_device = p_device or sender_device = p_device;
  update public.mm_push_tokens
     set revoked_at = now(), updated_at = now()
   where device_id = p_device and user_id = v_uid and revoked_at is null;

  if v_updated > 0 then
    perform public.mm_rotate_user_groups(v_uid);
  end if;
end $$;

-- Push token registration & revocation RPCs.
create or replace function public.mm_register_push_token(
  p_device_id text,
  p_provider text,
  p_token text,
  p_app_id text default 'com.offlinemesh.messenger',
  p_locale text default 'en'
) returns uuid
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_device uuid;
  v_id uuid;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  perform public.mm_consume_rate_limit('push_register', 30, 3600);

  select d.id into v_device
    from public.mm_devices d
   where d.user_id = v_uid and d.device_id = p_device_id and d.revoked_at is null;
  if v_device is null then
    raise exception 'device_not_found' using errcode = 'P0004';
  end if;

  -- Reassign token if the same physical installation switched accounts cleanly.
  delete from public.mm_push_tokens
   where provider = coalesce(p_provider, 'fcm') and token = p_token and device_id <> v_device;

  insert into public.mm_push_tokens (
    user_id, device_id, provider, token, app_id, locale, updated_at, expires_at, revoked_at
  ) values (
    v_uid, v_device, coalesce(p_provider, 'fcm'), p_token,
    coalesce(nullif(p_app_id, ''), 'com.offlinemesh.messenger'),
    coalesce(nullif(p_locale, ''), 'en'),
    now(), now() + interval '60 days', null
  )
  on conflict (device_id, provider) do update set
    user_id = excluded.user_id,
    token = excluded.token,
    app_id = excluded.app_id,
    locale = excluded.locale,
    updated_at = now(),
    expires_at = now() + interval '60 days',
    revoked_at = null
  returning id into v_id;

  return v_id;
end $$;

create or replace function public.mm_revoke_push_token(p_device_id text) returns void
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
begin
  if v_uid is null then
    return;
  end if;
  update public.mm_push_tokens pt
     set revoked_at = now(), updated_at = now()
    from public.mm_devices d
   where pt.device_id = d.id
     and d.user_id = v_uid
     and d.device_id = p_device_id
     and pt.revoked_at is null;
end $$;

-- New groups use Signal Sender Keys (encryption_version = 2) while existing v1 groups stay v1 unless upgraded.
create or replace function public.mm_create_group(p_title text, p_members uuid[]) returns uuid
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_id uuid;
  v_member uuid;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  perform public.mm_consume_rate_limit('group_create', 10, 3600);
  insert into public.mm_conversations (
    type, title, owner_id, visibility, encryption_version, sender_key_epoch, distribution_id
  )
  values (
    'group', coalesce(nullif(trim(p_title), ''), 'Group'), v_uid, 'private',
    2, 1, gen_random_uuid()
  )
  returning id into v_id;

  foreach v_member in array coalesce(p_members, array[]::uuid[]) loop
    if v_member <> v_uid
       and not public.mm_is_blocked(v_uid, v_member)
       and (select count(*) from public.mm_conversation_members where conversation_id = v_id) < 500 then
      insert into public.mm_conversation_members (conversation_id, user_id, role, invited_by)
      values (v_id, v_member, 'member', v_uid) on conflict do nothing;
    end if;
  end loop;
  return v_id;
end $$;

create or replace function public.mm_add_group_members(p_conversation uuid, p_members uuid[])
returns integer
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_type text;
  v_member uuid;
  v_added integer := 0;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  if not public.mm_is_admin(p_conversation, v_uid) then
    raise exception 'not_authorized' using errcode = 'P0005';
  end if;
  select c.type into v_type from public.mm_conversations c
   where c.id = p_conversation and c.deleted_at is null;
  if v_type is distinct from 'group' then
    raise exception 'not_a_group' using errcode = 'P0016';
  end if;
  perform public.mm_consume_rate_limit('group_member_change', 60, 3600);

  foreach v_member in array coalesce(p_members, array[]::uuid[]) loop
    if v_member <> v_uid and not public.mm_is_blocked(v_uid, v_member) then
      insert into public.mm_conversation_members (conversation_id, user_id, role, invited_by)
      values (p_conversation, v_member, 'member', v_uid)
      on conflict (conversation_id, user_id) do nothing;
      if found then
        v_added := v_added + 1;
      end if;
    end if;
  end loop;
  return v_added;
end $$;

-- Removing a member immediately revokes membership, deletes pending ciphertext for them, and rotates Sender Keys.
create or replace function public.mm_remove_group_member(p_conversation uuid, p_member uuid)
returns boolean
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_target_role text;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  if not public.mm_is_admin(p_conversation, v_uid) then
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

create or replace function public.mm_upgrade_group_encryption(p_conversation uuid)
returns jsonb
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_epoch integer;
  v_dist uuid;
begin
  if not public.mm_is_admin(p_conversation, v_uid) then
    raise exception 'not_authorized' using errcode = 'P0005';
  end if;
  update public.mm_conversations
     set encryption_version = 2,
         sender_key_epoch = sender_key_epoch + 1,
         distribution_id = gen_random_uuid(),
         updated_at = now()
   where id = p_conversation and type = 'group' and deleted_at is null
   returning sender_key_epoch, distribution_id into v_epoch, v_dist;
  if v_epoch is null then
    raise exception 'not_a_group' using errcode = 'P0016';
  end if;
  return jsonb_build_object(
    'encryptionVersion', 2,
    'epoch', v_epoch,
    'distributionId', v_dist
  );
end $$;

create or replace function public.mm_leave_conversation(p_conversation uuid) returns boolean
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_role text;
  v_remaining integer;
  v_next_owner uuid;
begin
  select role into v_role from public.mm_conversation_members
   where conversation_id = p_conversation and user_id = v_uid;
  if v_role is null then
    return false;
  end if;

  if v_role = 'owner' then
    select m.user_id into v_next_owner
      from public.mm_conversation_members m
     where m.conversation_id = p_conversation and m.user_id <> v_uid
     order by (m.role = 'admin') desc, m.joined_at asc
     limit 1;
    if v_next_owner is not null then
      update public.mm_conversation_members
         set role = 'owner'
       where conversation_id = p_conversation and user_id = v_next_owner;
      update public.mm_conversations
         set owner_id = v_next_owner
       where id = p_conversation;
    end if;
  end if;

  delete from public.mm_conversation_members
   where conversation_id = p_conversation and user_id = v_uid;

  delete from public.mm_sender_key_distributions
   where conversation_id = p_conversation
     and (recipient_user_id = v_uid or sender_user_id = v_uid);

  select count(*) into v_remaining
    from public.mm_conversation_members
   where conversation_id = p_conversation;

  if v_remaining = 0 then
    update public.mm_conversations set deleted_at = now() where id = p_conversation;
  else
    update public.mm_conversations
       set sender_key_epoch = sender_key_epoch + 1,
           distribution_id = gen_random_uuid(),
           updated_at = now()
     where id = p_conversation and type = 'group';
  end if;
  return true;
end $$;

-- Full account cleanup (callable directly or from mm-account-delete Edge Function).
create or replace function public.mm_delete_me() returns void
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  perform public.mm_rotate_user_groups(v_uid);
  update public.mm_push_tokens set revoked_at = now() where user_id = v_uid and revoked_at is null;
  delete from public.mm_push_tokens where user_id = v_uid;
  delete from public.mm_notification_prefs where user_id = v_uid;
  delete from public.mm_push_log where user_id = v_uid;
  update public.mm_devices set revoked_at = now() where user_id = v_uid and revoked_at is null;
  delete from public.mm_device_prekeys where device in (select id from public.mm_devices where user_id = v_uid);
  delete from public.mm_sender_key_distributions where recipient_user_id = v_uid or sender_user_id = v_uid;
  delete from public.mm_message_envelopes where recipient_user_id = v_uid;
  delete from public.mm_contacts where owner_id = v_uid or contact_id = v_uid;
  delete from public.mm_contact_requests where from_user = v_uid or to_user = v_uid;
  delete from public.mm_blocks where blocker_id = v_uid or blocked_id = v_uid;
  delete from public.mm_conversation_members where user_id = v_uid;
  update public.mm_profiles
     set display_name = 'Deleted account', username = null, avatar_url = null, bio = null,
         deleted_at = now(), last_seen_at = now()
   where id = v_uid;
  delete from public.mm_user_settings where user_id = v_uid;
end $$;

-- Hardened 1:1 call creation: rejects group/channel calls, blocked peers, and expires stale rings.
create or replace function public.mm_create_call(p_conversation uuid, p_kind text) returns uuid
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_conv public.mm_conversations;
  v_call uuid;
  v_peer uuid;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  if not public.mm_is_member(p_conversation, v_uid) then
    raise exception 'not_a_member' using errcode = 'P0012';
  end if;
  select * into v_conv from public.mm_conversations
   where id = p_conversation and deleted_at is null;
  if v_conv.id is null then
    raise exception 'conversation_not_found' using errcode = 'P0009';
  end if;
  if v_conv.type <> 'direct' then
    raise exception 'group_calls_unsupported' using errcode = 'P0015';
  end if;

  select m.user_id into v_peer
    from public.mm_conversation_members m
   where m.conversation_id = p_conversation and m.user_id <> v_uid
   limit 1;
  if v_peer is null then
    raise exception 'peer_not_found' using errcode = 'P0006';
  end if;
  if public.mm_is_blocked(v_uid, v_peer) then
    raise exception 'blocked' using errcode = 'P0008';
  end if;

  perform public.mm_consume_rate_limit('call_create', 60, 3600);

  -- Expire any stale ringing calls in this conversation older than 65 seconds.
  update public.mm_calls
     set state = 'missed', ended_at = now(), end_reason = 'timeout'
   where conversation_id = p_conversation
     and state = 'ringing'
     and started_at < now() - interval '65 seconds';

  insert into public.mm_calls (conversation_id, initiator_id, kind, state)
  values (p_conversation, v_uid, case when p_kind = 'video' then 'video' else 'voice' end, 'ringing')
  returning id into v_call;

  insert into public.mm_call_participants (call_id, user_id, state, joined_at)
  values (v_call, v_uid, 'joined', now()) on conflict do nothing;

  insert into public.mm_call_participants (call_id, user_id, state)
  values (v_call, v_peer, 'invited') on conflict do nothing;

  insert into public.mm_notifications (user_id, kind, conversation_id, call_id)
  values (v_peer, 'call', p_conversation, v_call);

  return v_call;
end $$;

-- Validated call state transitions (terminal states cannot be reopened).
create or replace function public.mm_set_call_state(
  p_call uuid,
  p_state text,
  p_end_reason text default null
) returns void
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_current text;
  v_started timestamptz;
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

  -- Once terminal, ignore late/duplicate transitions cleanly (idempotent).
  if v_current in ('ended', 'declined', 'missed', 'failed') then
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
         end_reason = coalesce(p_end_reason, end_reason)
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

-- Validated, deduplicated, expiring call signal insertion.
create or replace function public.mm_send_call_signal(
  p_call uuid,
  p_to_user uuid,
  p_kind text,
  p_payload jsonb,
  p_client_signal_id text default null
) returns bigint
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_state text;
  v_id bigint;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  if not public.mm_is_call_participant(p_call, v_uid)
     or not public.mm_is_call_participant(p_call, p_to_user) then
    raise exception 'not_a_participant' using errcode = 'P0013';
  end if;
  select state into v_state from public.mm_calls where id = p_call;
  if v_state is null or (v_state in ('ended','declined','missed','failed') and p_kind <> 'bye') then
    raise exception 'call_inactive' using errcode = 'P0018';
  end if;

  perform public.mm_consume_rate_limit('call_signal', 400, 60);

  -- Clean up expired signals opportunistically.
  delete from public.mm_call_signals where call_id = p_call and expires_at < now();

  insert into public.mm_call_signals (
    call_id, from_user, to_user, kind, payload, client_signal_id, expires_at
  ) values (
    p_call, v_uid, p_to_user, p_kind, coalesce(p_payload, '{}'::jsonb),
    nullif(p_client_signal_id, ''), now() + interval '2 minutes'
  )
  on conflict (call_id, from_user, client_signal_id) where client_signal_id is not null
  do update set expires_at = excluded.expires_at
  returning id into v_id;

  return v_id;
end $$;

-- Storage update policy for resumable / chunked media uploads on private bucket mm-media.
drop policy if exists mm_media_update on storage.objects;
create policy mm_media_update on storage.objects for update to authenticated
using (
  bucket_id = 'mm-media'
  and owner = auth.uid()
  and public.mm_can_post(public.mm_try_uuid(split_part(name, '/', 1)), auth.uid())
)
with check (
  bucket_id = 'mm-media'
  and owner = auth.uid()
  and public.mm_can_post(public.mm_try_uuid(split_part(name, '/', 1)), auth.uid())
);

-- =====================================================================
-- 5. Privileges & Realtime publication
-- =====================================================================

revoke all on public.mm_sender_key_distributions from anon;
revoke all on public.mm_push_tokens from anon;
revoke all on public.mm_notification_prefs from anon;
revoke all on public.mm_push_log from anon;

grant select, insert, delete on public.mm_sender_key_distributions to authenticated;
grant usage, select on sequence public.mm_sender_key_distributions_id_seq to authenticated;
grant select, insert, update, delete on public.mm_push_tokens to authenticated;
grant select, insert, update, delete on public.mm_notification_prefs to authenticated;

do $$
begin
  if not exists (
    select 1 from pg_publication_tables
     where pubname = 'supabase_realtime'
       and schemaname = 'public'
       and tablename = 'mm_sender_key_distributions'
  ) then
    alter publication supabase_realtime add table public.mm_sender_key_distributions;
  end if;
end $$;
