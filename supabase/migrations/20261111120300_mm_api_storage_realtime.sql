-- Messenger hybrid backend — migration 4: RPC surface, storage buckets and realtime.
--
-- All multi-row operations that a client must perform atomically are exposed as SECURITY
-- DEFINER functions that re-check authorisation themselves, so a policy mistake can never turn
-- into a privilege escalation: the functions validate the caller and the graph they touch.

-- Safe uuid cast for storage paths (invalid path segments must not raise inside a policy).
create or replace function public.mm_try_uuid(p_value text) returns uuid
language plpgsql immutable as $$
begin
  return p_value::uuid;
exception when others then
  return null;
end $$;

-- ------------------------------------------------------------------ profile + devices
create or replace function public.mm_ensure_profile(
  p_display_name text default null,
  p_username text default null
) returns public.mm_profiles
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_profile public.mm_profiles;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  insert into public.mm_profiles (id, display_name, username)
  values (v_uid, coalesce(p_display_name, ''), nullif(lower(p_username), ''))
  on conflict (id) do update
    set display_name = coalesce(nullif(excluded.display_name, ''), public.mm_profiles.display_name),
        username = coalesce(public.mm_profiles.username, excluded.username),
        last_seen_at = now()
  returning * into v_profile;

  insert into public.mm_user_settings (user_id) values (v_uid) on conflict (user_id) do nothing;
  return v_profile;
end $$;

create or replace function public.mm_touch_last_seen() returns void
language sql security definer set search_path = public, pg_temp as $$
  update public.mm_profiles set last_seen_at = now() where id = auth.uid();
$$;

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
  v_key jsonb;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  if not exists (select 1 from public.mm_profiles p where p.id = v_uid) then
    raise exception 'profile_missing' using errcode = 'P0003';
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

  -- The device owns its pre-key pool; publishing replaces whatever is still unclaimed.
  delete from public.mm_device_prekeys where device = v_device and claimed_at is null;
  if p_prekeys is not null and jsonb_typeof(p_prekeys) = 'array' then
    for v_key in select * from jsonb_array_elements(p_prekeys) loop
      insert into public.mm_device_prekeys (device, prekey_id, prekey_public)
      values (v_device, (v_key->>'id')::integer, v_key->>'public')
      on conflict (device, prekey_id) do update set prekey_public = excluded.prekey_public;
    end loop;
  end if;
  return v_device;
end $$;

create or replace function public.mm_replenish_prekeys(p_device_id text, p_prekeys jsonb)
returns integer
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_device uuid;
  v_key jsonb;
  v_added integer := 0;
begin
  select d.id into v_device from public.mm_devices d
   where d.user_id = v_uid and d.device_id = p_device_id and d.revoked_at is null;
  if v_device is null then
    raise exception 'device_not_found' using errcode = 'P0004';
  end if;
  for v_key in select * from jsonb_array_elements(coalesce(p_prekeys, '[]'::jsonb)) loop
    insert into public.mm_device_prekeys (device, prekey_id, prekey_public)
    values (v_device, (v_key->>'id')::integer, v_key->>'public')
    on conflict (device, prekey_id) do nothing;
    v_added := v_added + 1;
  end loop;
  return v_added;
end $$;

-- Hands out exactly one one-time pre-key, atomically, and only to callers that already share
-- a conversation with the device owner (or own the device). This is the online equivalent of
-- the mesh HELLO: it is how a Signal session is bootstrapped without a plaintext server.
create or replace function public.mm_claim_prekey_bundle(p_device uuid)
returns jsonb
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_owner uuid;
  v_bundle jsonb;
  v_prekey_id integer;
  v_prekey_public text;
begin
  select d.user_id into v_owner from public.mm_devices d
   where d.id = p_device and d.revoked_at is null;
  if v_owner is null then
    raise exception 'device_not_found' using errcode = 'P0004';
  end if;
  if v_owner <> v_uid and not public.mm_shares_conversation(v_owner, v_uid) then
    raise exception 'not_authorized' using errcode = 'P0005';
  end if;

  select pk.prekey_id, pk.prekey_public into v_prekey_id, v_prekey_public
    from public.mm_device_prekeys pk
   where pk.device = p_device and pk.claimed_at is null
   order by pk.id
   limit 1
   for update skip locked;
  if v_prekey_id is not null then
    update public.mm_device_prekeys set claimed_at = now()
     where device = p_device and prekey_id = v_prekey_id;
  end if;

  select jsonb_build_object(
    'device', d.id, 'user', d.user_id, 'deviceId', d.device_id,
    'identityKey', d.identity_key, 'registrationId', d.registration_id,
    'signedPreKey', jsonb_build_object('id', d.signed_prekey_id, 'public', d.signed_prekey_public,
                                       'signature', d.signed_prekey_signature),
    'kyberPreKey', jsonb_build_object('id', d.kyber_prekey_id, 'public', d.kyber_prekey_public,
                                      'signature', d.kyber_prekey_signature),
    'oneTimePreKey', case when v_prekey_id is null then 'null'::jsonb
                          else jsonb_build_object('id', v_prekey_id, 'public', v_prekey_public) end
  ) into v_bundle
  from public.mm_devices d where d.id = p_device;
  return v_bundle;
end $$;

create or replace function public.mm_revoke_device(p_device uuid) returns void
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  update public.mm_devices set revoked_at = now()
   where id = p_device and user_id = auth.uid() and revoked_at is null;
  delete from public.mm_device_prekeys where device = p_device;
  delete from public.mm_message_envelopes where recipient_device = p_device;
end $$;

-- ------------------------------------------------------------------ conversations
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
  if public.mm_is_blocked(v_uid, p_other) then
    raise exception 'blocked' using errcode = 'P0008';
  end if;
  v_key := least(v_uid::text, p_other::text) || ':' || greatest(v_uid::text, p_other::text);

  select c.id into v_id from public.mm_conversations c where c.direct_key = v_key;
  if v_id is not null then
    return v_id;
  end if;

  insert into public.mm_conversations (type, title, owner_id, direct_key, visibility)
  values ('direct', '', v_uid, v_key, 'private')
  returning id into v_id;

  insert into public.mm_conversation_members (conversation_id, user_id, role, invited_by)
  values (v_id, p_other, 'member', v_uid)
  on conflict do nothing;
  return v_id;
end $$;

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
  insert into public.mm_conversations (type, title, owner_id, visibility)
  values ('group', coalesce(nullif(trim(p_title), ''), 'Group'), v_uid, 'private')
  returning id into v_id;

  foreach v_member in array coalesce(p_members, array[]::uuid[]) loop
    if v_member <> v_uid and (select count(*) from public.mm_conversation_members where conversation_id = v_id) < 500 then
      insert into public.mm_conversation_members (conversation_id, user_id, role, invited_by)
      values (v_id, v_member, 'member', v_uid) on conflict do nothing;
    end if;
  end loop;
  return v_id;
end $$;

create or replace function public.mm_create_channel(
  p_title text, p_username text, p_public boolean, p_description text default null
) returns uuid
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_id uuid;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  perform public.mm_consume_rate_limit('channel_create', 5, 3600);
  insert into public.mm_conversations
    (type, title, description, owner_id, visibility, username, invite_code)
  values (
    'channel', coalesce(nullif(trim(p_title), ''), 'Channel'), p_description, v_uid,
    case when coalesce(p_public, false) then 'public' else 'private' end,
    nullif(lower(p_username), ''),
    case when coalesce(p_public, false) then null else replace(encode(gen_random_bytes(9), 'base64'), '/', '_') end
  )
  returning id into v_id;
  return v_id;
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
  select * into v_conversation from public.mm_conversations
   where id = p_conversation and type = 'channel' and deleted_at is null;
  if v_conversation.id is null then
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

create or replace function public.mm_leave_conversation(p_conversation uuid) returns boolean
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_role text;
  v_remaining integer;
begin
  select role into v_role from public.mm_conversation_members
   where conversation_id = p_conversation and user_id = v_uid;
  if v_role is null then
    return false;
  end if;
  if v_role = 'owner' then
    raise exception 'owner_must_transfer_or_delete' using errcode = 'P0011';
  end if;
  delete from public.mm_conversation_members
   where conversation_id = p_conversation and user_id = v_uid;
  select count(*) into v_remaining from public.mm_conversation_members where conversation_id = p_conversation;
  if v_remaining = 0 then
    update public.mm_conversations set deleted_at = now() where id = p_conversation;
  end if;
  return true;
end $$;

create or replace function public.mm_set_read(p_conversation uuid, p_message uuid) returns void
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  if not public.mm_is_member(p_conversation, auth.uid()) then
    raise exception 'not_a_member' using errcode = 'P0012';
  end if;
  update public.mm_conversation_members
     set last_read_message_id = p_message
   where conversation_id = p_conversation and user_id = auth.uid();
  insert into public.mm_message_receipts (message_id, user_id, delivered_at, read_at)
  values (p_message, auth.uid(), now(), now())
  on conflict (message_id, user_id) do update
    set delivered_at = coalesce(public.mm_message_receipts.delivered_at, now()), read_at = now();
end $$;

-- ------------------------------------------------------------------------ discovery
create or replace function public.mm_search_users(p_query text)
returns table (id uuid, username text, display_name text, avatar_url text)
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_query text := trim(coalesce(p_query, ''));
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  if char_length(v_query) < 2 then
    return;
  end if;
  perform public.mm_consume_rate_limit('user_search', 120, 60);
  return query
    select p.id, p.username, p.display_name, p.avatar_url
      from public.mm_profiles p
     where p.deleted_at is null
       and p.id <> v_uid
       and not public.mm_is_blocked(p.id, v_uid)
       and (p.username ilike v_query || '%' or p.display_name ilike '%' || v_query || '%')
     order by (p.username ilike v_query || '%') desc, p.display_name
     limit 20;
end $$;

create or replace function public.mm_search_public_channels(p_query text)
returns table (id uuid, title text, username text, description text, avatar_url text)
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_query text := trim(coalesce(p_query, ''));
begin
  if auth.uid() is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  return query
    select c.id, c.title, c.username, c.description, c.avatar_url
      from public.mm_conversations c
     where c.type = 'channel' and c.visibility = 'public' and c.deleted_at is null
       and (v_query = '' or c.title ilike '%' || v_query || '%' or c.username ilike v_query || '%')
     order by c.last_message_at desc nulls last, c.created_at desc
     limit 30;
end $$;

-- ----------------------------------------------------------------------------- abuse
create or replace function public.mm_report(
  p_target_kind text, p_target_id uuid, p_reason text, p_details text default null
) returns uuid
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_id uuid;
begin
  if auth.uid() is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  perform public.mm_consume_rate_limit('report', 10, 3600);
  insert into public.mm_reports (reporter_id, target_kind, target_id, reason, details)
  values (auth.uid(), p_target_kind, p_target_id, p_reason, p_details)
  returning id into v_id;
  return v_id;
end $$;

-- Account deletion: the profile is anonymised and every device revoked immediately, so no
-- ciphertext can be delivered any more. The auth.users row itself is removed through the Admin
-- API by the operator (service role is never shipped in the client).
create or replace function public.mm_delete_me() returns void
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;
  update public.mm_devices set revoked_at = now() where user_id = v_uid and revoked_at is null;
  delete from public.mm_device_prekeys where device in (select id from public.mm_devices where user_id = v_uid);
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

-- ----------------------------------------------------------------------------- calls
create or replace function public.mm_create_call(p_conversation uuid, p_kind text) returns uuid
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_uid uuid := auth.uid();
  v_call uuid;
  v_member uuid;
begin
  if not public.mm_is_member(p_conversation, v_uid) then
    raise exception 'not_a_member' using errcode = 'P0012';
  end if;
  perform public.mm_consume_rate_limit('call_create', 120, 3600);
  insert into public.mm_calls (conversation_id, initiator_id, kind, state)
  values (p_conversation, v_uid, case when p_kind = 'video' then 'video' else 'voice' end, 'ringing')
  returning id into v_call;

  insert into public.mm_call_participants (call_id, user_id, state, joined_at)
  values (v_call, v_uid, 'joined', now()) on conflict do nothing;

  for v_member in select user_id from public.mm_conversation_members
                  where conversation_id = p_conversation and user_id <> v_uid loop
    insert into public.mm_call_participants (call_id, user_id, state)
    values (v_call, v_member, 'invited') on conflict do nothing;
    insert into public.mm_notifications (user_id, kind, conversation_id, call_id)
    values (v_member, 'call', p_conversation, v_call);
  end loop;
  return v_call;
end $$;

create or replace function public.mm_set_call_state(p_call uuid, p_state text) returns void
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  if not public.mm_is_call_participant(p_call, auth.uid()) then
    raise exception 'not_a_participant' using errcode = 'P0013';
  end if;
  update public.mm_calls
     set state = p_state,
         answered_at = case when p_state = 'active' then coalesce(answered_at, now()) else answered_at end,
         ended_at = case when p_state in ('ended','declined','missed','failed') then now() else ended_at end
   where id = p_call;
end $$;

-- ------------------------------------------------------------------- storage buckets
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('mm-media', 'mm-media', false, 268435456, null)
on conflict (id) do nothing;
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('mm-avatars', 'mm-avatars', true, 5242880, array['image/jpeg','image/png','image/webp'])
on conflict (id) do nothing;

drop policy if exists mm_media_read on storage.objects;
create policy mm_media_read on storage.objects for select to authenticated
using (bucket_id = 'mm-media' and public.mm_is_member(public.mm_try_uuid(split_part(name, '/', 1)), auth.uid()));

drop policy if exists mm_media_write on storage.objects;
create policy mm_media_write on storage.objects for insert to authenticated
with check (bucket_id = 'mm-media' and public.mm_can_post(public.mm_try_uuid(split_part(name, '/', 1)), auth.uid()));

drop policy if exists mm_media_delete on storage.objects;
create policy mm_media_delete on storage.objects for delete to authenticated
using (bucket_id = 'mm-media' and (owner = auth.uid()
  or public.mm_is_admin(public.mm_try_uuid(split_part(name, '/', 1)), auth.uid())));

drop policy if exists mm_avatars_write on storage.objects;
create policy mm_avatars_write on storage.objects for insert to authenticated
with check (bucket_id = 'mm-avatars' and split_part(name, '/', 1) = auth.uid()::text);

drop policy if exists mm_avatars_update on storage.objects;
create policy mm_avatars_update on storage.objects for update to authenticated
using (bucket_id = 'mm-avatars' and split_part(name, '/', 1) = auth.uid()::text)
with check (bucket_id = 'mm-avatars' and split_part(name, '/', 1) = auth.uid()::text);

-- ------------------------------------------------------------------------ realtime
do $$
declare
  t text;
begin
  foreach t in array array[
    'mm_messages', 'mm_message_envelopes', 'mm_message_receipts', 'mm_reactions',
    'mm_typing', 'mm_conversations', 'mm_conversation_members', 'mm_contact_requests',
    'mm_calls', 'mm_call_participants', 'mm_call_signals', 'mm_notifications'
  ] loop
    if not exists (
      select 1 from pg_publication_tables
       where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = t
    ) then
      execute format('alter publication supabase_realtime add table public.%I', t);
    end if;
  end loop;
end $$;
