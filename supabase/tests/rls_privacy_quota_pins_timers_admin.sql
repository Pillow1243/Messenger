-- Regression suite for migration 20261113090000 (audit section A.7: P-1..P-3, Q-1, D-1, G-1).
--
-- The P-1, P-2 and P-3 reads below were observed SUCCEEDING against the schema as of
-- 20261112120000 while impersonating the `authenticated` role (one-sided contact entry, public
-- channel co-subscription, other members' mute state), and failing after this migration. The
-- Q-1, D-1, pin and G-1 blocks specify behaviour that did not exist before.
-- Runs inside BEGIN/ROLLBACK and leaves no residue.

begin;

create function pg_temp.act_as(p_user uuid, p_session uuid default null) returns void
language plpgsql as $$
begin
  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', p_user, 'role', 'authenticated',
                      'session_id', coalesce(p_session::text, ''))::text, true);
  execute 'set local role authenticated';
end $$;

create function pg_temp.run(p_sql text) returns text
language plpgsql as $$
declare
  v_rows bigint;
begin
  begin
    execute p_sql;
    get diagnostics v_rows = row_count;
  exception when assert_failure or others then
    return 'err:' || sqlerrm;
  end;
  return 'ok:' || v_rows;
end $$;

create function pg_temp.refused(p_sql text) returns boolean
language sql as $$ select pg_temp.run(p_sql) like 'err:%' or pg_temp.run(p_sql) = 'ok:0' $$;

do $setup$
begin
  execute format('grant usage on schema %I to authenticated',
                 (select nspname from pg_namespace where oid = pg_my_temp_schema()));
end
$setup$;

do $test$
declare
  v_alice   uuid := 'dddddddd-0000-4000-8000-000000000001';
  v_bob     uuid := 'dddddddd-0000-4000-8000-000000000002';
  v_carol   uuid := 'dddddddd-0000-4000-8000-000000000003';
  v_mallory uuid := 'dddddddd-0000-4000-8000-000000000004';
  v_dave    uuid := 'dddddddd-0000-4000-8000-000000000005';
  s_alice   uuid := 'eeeeeeee-0000-4000-8000-000000000001';
  s_bob     uuid := 'eeeeeeee-0000-4000-8000-000000000002';
  s_carol   uuid := 'eeeeeeee-0000-4000-8000-000000000003';
  s_mallory uuid := 'eeeeeeee-0000-4000-8000-000000000004';
  s_dave    uuid := 'eeeeeeee-0000-4000-8000-000000000005';
  v_direct uuid; v_group uuid; v_channel uuid; v_private uuid;
  v_msg uuid; v_msg2 uuid; v_edit uuid; v_gmsg uuid; v_tmp uuid;
  v_count bigint; v_bytes bigint; v_text text; v_code text; v_code2 text; v_epoch integer;
  v_expires timestamptz;
  c_signed constant jsonb := '{"id":1,"public":"c2lnbmVkLXByZWtleQ==","signature":"c2lnbmF0dXJl"}';
  c_kyber  constant jsonb := '{"id":1,"public":"a3liZXItcHJla2V5","signature":"c2lnbmF0dXJl"}';
  c_pre    constant jsonb := '[{"id":1,"public":"b25lLXRpbWUtMQ=="}]';
begin
  ---------------------------------------------------------------------------- fixtures
  insert into auth.users (id, email)
  values (v_alice, 'pq-alice@example.invalid'), (v_bob, 'pq-bob@example.invalid'),
         (v_carol, 'pq-carol@example.invalid'), (v_mallory, 'pq-mallory@example.invalid'),
         (v_dave, 'pq-dave@example.invalid')
  on conflict (id) do nothing;

  perform pg_temp.act_as(v_alice, s_alice);
  perform public.mm_ensure_profile('Alice', 'pq_alice');
  perform public.mm_register_device('pq-device-alice', 'A', 'android', 'pq-alice-identity', 1, c_signed, c_kyber, c_pre);
  perform pg_temp.act_as(v_bob, s_bob);
  perform public.mm_ensure_profile('Bob', 'pq_bob');
  perform public.mm_register_device('pq-device-bob', 'B', 'android', 'pq-bob-identity', 2, c_signed, c_kyber, c_pre);
  perform pg_temp.act_as(v_carol, s_carol);
  perform public.mm_ensure_profile('Carol', 'pq_carol');
  perform public.mm_register_device('pq-device-carol', 'C', 'android', 'pq-carol-identity', 3, c_signed, c_kyber, c_pre);
  perform pg_temp.act_as(v_mallory, s_mallory);
  perform public.mm_ensure_profile('Mallory', 'pq_mallory');
  perform public.mm_register_device('pq-device-mallory', 'M', 'android', 'pq-mallory-identity', 4, c_signed, c_kyber, c_pre);
  perform pg_temp.act_as(v_dave, s_dave);
  perform public.mm_ensure_profile('Dave', 'pq_dave');
  perform public.mm_register_device('pq-device-dave', 'D', 'android', 'pq-dave-identity', 5, c_signed, c_kyber, c_pre);

  ------------------------------------------------------------------ P-1 profile scoping
  -- Mallory adds Alice to her own contact list and sends her a request. Neither grants a read.
  perform pg_temp.act_as(v_mallory, s_mallory);
  insert into public.mm_contacts (owner_id, contact_id) values (v_mallory, v_alice);
  select count(*) into v_count from public.mm_profiles where id = v_alice;
  assert v_count = 0, 'P-1: a one-sided contact entry must not expose the profile';
  select count(*) into v_count from public.mm_devices where user_id = v_alice;
  assert v_count = 0, 'P-1: a one-sided contact entry must not expose the device directory';
  insert into public.mm_contact_requests (from_user, to_user, message) values (v_mallory, v_alice, 'hi');
  select count(*) into v_count from public.mm_profiles where id = v_alice;
  assert v_count = 0, 'P-1: sending a contact request must not expose the recipient';
  assert pg_temp.refused(format(
    $q$select last_seen_at from public.mm_profiles where id = %L$q$, v_mallory)),
    'P-1: last_seen_at is not readable by clients, not even for the own row';
  -- Search still works, with its own narrow column set.
  select count(*) into v_count from public.mm_search_users('pq_alice');
  assert v_count = 1, 'search must still find a user by username';

  -- The recipient may see who is asking.
  perform pg_temp.act_as(v_alice, s_alice);
  select count(*) into v_count from public.mm_profiles where id = v_mallory;
  assert v_count = 1, 'the recipient of a pending request must see the sender';
  select count(*) into v_count from public.mm_profiles where id = v_alice;
  assert v_count = 1, 'own profile is always readable';
  -- Mutual contacts see each other, until one of them blocks.
  insert into public.mm_contacts (owner_id, contact_id) values (v_alice, v_mallory);
  perform pg_temp.act_as(v_mallory, s_mallory);
  select count(*) into v_count from public.mm_profiles where id = v_alice;
  assert v_count = 1, 'mutual contacts must see each other';
  perform pg_temp.act_as(v_alice, s_alice);
  insert into public.mm_blocks (blocker_id, blocked_id) values (v_alice, v_mallory);
  perform pg_temp.act_as(v_mallory, s_mallory);
  insert into public.mm_contacts (owner_id, contact_id) values (v_mallory, v_alice)
    on conflict do nothing;
  select count(*) into v_count from public.mm_profiles where id = v_alice;
  assert v_count = 0, 'P-1: a blocked account must not read the blocker''s profile';
  select count(*) into v_count from public.mm_devices where user_id = v_alice;
  assert v_count = 0, 'P-1: a blocked account must not read the blocker''s devices';

  -- Direct and group co-membership keep working (what the client relies on).
  perform pg_temp.act_as(v_alice, s_alice);
  v_direct := public.mm_start_direct_conversation(v_bob);
  v_group := public.mm_create_group('PQ trio', array[v_bob, v_carol]);
  perform pg_temp.act_as(v_bob, s_bob);
  select count(*) into v_count from public.mm_profiles where id in (v_alice, v_carol);
  assert v_count = 2, 'group and direct members must see each other''s profiles';
  select count(*) into v_count from public.mm_devices where user_id in (v_alice, v_carol);
  assert v_count = 2, 'group and direct members must see each other''s devices';
  select count(*) into v_count from public.mm_conversation_members where conversation_id = v_group;
  assert v_count = 3, 'group members must see the whole member list';

  ------------------------------------------------------- P-2 channels do not leak people
  perform pg_temp.act_as(v_alice, s_alice);
  v_channel := public.mm_create_channel('PQ news', 'pq_news', true, null);
  perform pg_temp.act_as(v_dave, s_dave);
  perform public.mm_join_channel(v_channel, null);
  perform pg_temp.act_as(v_mallory, s_mallory);
  perform public.mm_join_channel(v_channel, null);
  select count(*) into v_count from public.mm_profiles where id = v_dave;
  assert v_count = 0, 'P-2: a channel subscriber must not read another subscriber''s profile';
  select count(*) into v_count from public.mm_devices where user_id = v_dave;
  assert v_count = 0, 'P-2: a channel subscriber must not read another subscriber''s devices';
  select count(*) into v_count from public.mm_conversation_members
   where conversation_id = v_channel and user_id = v_dave;
  assert v_count = 0, 'P-2: a channel subscriber must not enumerate the other subscribers';
  select count(*) into v_count from public.mm_conversation_members where conversation_id = v_channel;
  assert v_count = 2, 'a subscriber sees the staff and itself';
  -- Staff stays visible to subscribers even though Alice blocked Mallory, and staff sees everyone.
  select count(*) into v_count from public.mm_profiles where id = v_alice;
  assert v_count = 1, 'channel staff must be visible to subscribers';
  perform pg_temp.act_as(v_alice, s_alice);
  select count(*) into v_count from public.mm_conversation_members where conversation_id = v_channel;
  assert v_count = 3, 'channel staff must see every subscriber';
  select count(*) into v_count from public.mm_profiles where id in (v_dave, v_mallory);
  assert v_count = 2, 'channel staff must see subscriber profiles';

  ------------------------------------------------------ P-3 private member preferences
  perform pg_temp.act_as(v_bob, s_bob);
  update public.mm_conversation_members
     set notification_level = 'none', muted_until = now() + interval '1 day'
   where conversation_id = v_group and user_id = v_bob;
  get diagnostics v_count = row_count;
  assert v_count = 1, 'a member must still be able to mute their own membership';
  perform pg_temp.act_as(v_alice, s_alice);
  assert pg_temp.refused(format(
    $q$select muted_until from public.mm_conversation_members where conversation_id = %L$q$, v_group)),
    'P-3: mute state of other members must not be readable';
  assert pg_temp.refused(format(
    $q$select notification_level from public.mm_conversation_members where conversation_id = %L$q$, v_group)),
    'P-3: notification level of other members must not be readable';
  assert pg_temp.refused($q$select * from public.mm_conversation_members$q$),
    'P-3: select * on members must be refused (private columns)';
  -- The exact projection the client uses keeps working.
  select count(*) into v_count from (
    select conversation_id, user_id, role from public.mm_conversation_members
     where conversation_id = v_group) s;
  assert v_count = 3, 'client projection conversation_id,user_id,role must work';
  select count(*) into v_count from (
    select id, username, display_name, avatar_url from public.mm_profiles where id = v_bob) s;
  assert v_count = 1, 'client projection id,username,display_name,avatar_url must work';
  -- Own preferences come back through the RPC, and only the caller's.
  perform pg_temp.act_as(v_bob, s_bob);
  select notification_level into v_text from public.mm_my_memberships() where conversation_id = v_group;
  assert v_text = 'none', 'mm_my_memberships must return the caller''s own mute state';
  select count(*) into v_count from public.mm_my_memberships();
  assert v_count = 2, 'mm_my_memberships returns only the caller''s rows';

  ---------------------------------------------------------------- Q-1 media usage counters
  reset role;
  assert mm_private.media_usage_tracked(), 'Q-1: the usage trigger must be installed';
  insert into storage.objects (bucket_id, name, owner, metadata)
  values ('mm-media', v_direct || '/pq-1.enc', v_alice, '{"size": 1000}'),
         ('mm-media', v_direct || '/pq-2.enc', v_alice, '{"size": 500}'),
         ('mm-avatars', v_alice || '/pq-avatar.png', v_alice, '{"size": 99999}');
  select object_count, total_bytes into v_count, v_bytes from public.mm_media_usage where user_id = v_alice;
  assert v_count = 2 and v_bytes = 1500, 'Q-1: inserts are counted, other buckets are not';
  update storage.objects set metadata = '{"size": 4000}' where name = v_direct || '/pq-2.enc';
  select object_count, total_bytes into v_count, v_bytes from public.mm_media_usage where user_id = v_alice;
  assert v_count = 2 and v_bytes = 5000, 'Q-1: a size change is tracked';
  delete from storage.objects where name = v_direct || '/pq-1.enc';
  select object_count, total_bytes into v_count, v_bytes from public.mm_media_usage where user_id = v_alice;
  assert v_count = 1 and v_bytes = 4000, 'Q-1: deletes are subtracted';
  -- Objects of other buckets never reach the Messenger trigger function.
  assert not exists (
    select 1 from pg_trigger t
     where t.tgrelid = 'storage.objects'::regclass and t.tgname like 'mm_media_usage_track%'
       and pg_get_triggerdef(t.oid) not like '%mm-media%'),
    'Q-1: every usage trigger is limited to bucket mm-media by its WHEN clause';
  insert into storage.objects (bucket_id, name, owner, metadata)
  values ('mm-media', v_direct || '/pq-carol.enc', v_carol, '{"size": 7}');
  delete from storage.objects where name = v_direct || '/pq-carol.enc';
  assert not exists (select 1 from public.mm_media_usage where user_id = v_carol),
    'Q-1: no usage row is kept for an account with nothing stored';
  -- Reconciliation gives the same answer as the incremental counters.
  update public.mm_media_usage set object_count = 777, total_bytes = 1 where user_id = v_alice;
  perform mm_private.rebuild_media_usage();
  select object_count, total_bytes into v_count, v_bytes from public.mm_media_usage where user_id = v_alice;
  assert v_count = 1 and v_bytes = 4000, 'Q-1: rebuild restores the true usage';
  -- The quota decision follows the counters.
  assert mm_private.media_quota_ok(v_alice), 'Q-1: under quota';
  update public.mm_media_usage set total_bytes = 5368709120 where user_id = v_alice;
  assert not mm_private.media_quota_ok(v_alice), 'Q-1: byte quota reached';
  update public.mm_media_usage set total_bytes = 0, object_count = 20000 where user_id = v_alice;
  assert not mm_private.media_quota_ok(v_alice), 'Q-1: object quota reached';
  perform pg_temp.act_as(v_alice, s_alice);
  assert pg_temp.refused(format(
    $q$insert into storage.objects (bucket_id, name, owner) values ('mm-media', %L, %L)$q$,
    v_direct || '/pq-over.enc', v_alice)),
    'Q-1: an upload over quota must be refused by the storage policy';
  reset role;
  perform mm_private.rebuild_media_usage();
  perform pg_temp.act_as(v_alice, s_alice);
  assert pg_temp.run(format(
    $q$insert into storage.objects (bucket_id, name, owner) values ('mm-media', %L, %L)$q$,
    v_direct || '/pq-under.enc', v_alice)) = 'ok:1',
    'Q-1: an upload under quota must be accepted';
  -- Counters are private, and never writable by a client.
  select count(*) into v_count from public.mm_media_usage;
  assert v_count = 1, 'Q-1: a user reads only their own usage row';
  assert pg_temp.refused($q$update public.mm_media_usage set total_bytes = 0$q$),
    'Q-1: clients must not write usage counters';
  assert pg_temp.refused($q$select mm_private.rebuild_media_usage()$q$),
    'Q-1: clients must not call the rebuild helper';
  assert pg_temp.refused(format($q$select * from public.mm_media_objects_of(%L)$q$, v_alice)),
    'clients must not list storage objects through the account-deletion helper';
  reset role;
  set local role service_role;
  select count(*) into v_count from public.mm_media_objects_of(v_alice);
  assert v_count = 2, 'the service role lists every mm-media object the account owns, and no avatar';
  reset role;

  ------------------------------------------------------------ D-1 disappearing messages
  perform pg_temp.act_as(v_alice, s_alice);
  insert into public.mm_messages (conversation_id, sender_id, sender_device_id, client_msg_id, kind, meta, sent_at_ms)
  values (v_direct, v_alice, 'pq-device-alice', 'pq-plain', 'text', '{}', 1) returning id, expires_at into v_msg, v_expires;
  assert v_expires is null, 'no timer: messages do not expire';
  assert public.mm_set_message_ttl(v_direct, 60) = 60, 'either side of a direct chat may set the timer';
  assert pg_temp.refused(format($q$select public.mm_set_message_ttl(%L, 2)$q$, v_direct)),
    'D-1: a timer below 5 s is refused';
  assert pg_temp.refused(format($q$update public.mm_conversations set message_ttl_seconds = 5 where id = %L$q$, v_direct)),
    'D-1: the timer is not directly writable';
  assert pg_temp.refused(format(
    $q$insert into public.mm_messages (conversation_id, sender_id, sender_device_id, client_msg_id, kind, meta, sent_at_ms, expires_at)
       values (%L, %L, 'pq-device-alice', 'pq-forged', 'text', '{}', 1, now() + interval '10 years')$q$,
    v_direct, v_alice)), 'D-1: a client must not supply its own expires_at';
  insert into public.mm_messages (conversation_id, sender_id, sender_device_id, client_msg_id, kind, meta, sent_at_ms)
  values (v_direct, v_alice, 'pq-device-alice', 'pq-timed', 'text', '{}', 1)
  returning id, expires_at into v_msg2, v_expires;
  assert v_expires between now() + interval '55 seconds' and now() + interval '65 seconds',
    'D-1: the server stamps expires_at from the conversation timer';
  insert into public.mm_messages (conversation_id, sender_id, sender_device_id, client_msg_id, kind, meta, sent_at_ms)
  values (v_direct, v_alice, 'pq-device-alice', 'pq-edit', 'text-edit',
          jsonb_build_object('target', v_msg2), 1)
  returning id into v_edit;
  assert (select expires_at from public.mm_messages where id = v_edit) = v_expires,
    'D-1: an edit expires together with its target';
  assert pg_temp.refused(format($q$update public.mm_messages set expires_at = null where id = %L$q$, v_msg2)),
    'D-1: the sender must not be able to extend a message';
  insert into public.mm_message_envelopes (message_id, recipient_user_id, recipient_device, cipher_type, ciphertext)
  select v_msg2, v_bob, d.id, 3, 'Y2lwaGVydGV4dA==' from public.mm_devices d where d.user_id = v_bob;
  perform pg_temp.act_as(v_bob, s_bob);
  select count(*) into v_count from public.mm_message_envelopes where message_id = v_msg2;
  assert v_count = 1, 'the recipient reads the envelope while the message is live';
  assert pg_temp.refused(format($q$select public.mm_set_message_ttl(%L, 60)$q$, v_group)),
    'D-1: a plain group member must not change the timer';
  perform pg_temp.act_as(v_mallory, s_mallory);
  assert pg_temp.refused(format($q$select public.mm_set_message_ttl(%L, 60)$q$, v_direct)),
    'D-1: an outsider must not change the timer';

  reset role;
  update public.mm_messages set expires_at = now() - interval '1 second' where id in (v_msg2, v_edit);
  perform pg_temp.act_as(v_bob, s_bob);
  select count(*) into v_count from public.mm_messages where id in (v_msg2, v_edit);
  assert v_count = 0, 'D-1: an expired message is unreadable before the purge runs';
  select count(*) into v_count from public.mm_message_envelopes where message_id = v_msg2;
  assert v_count = 0, 'D-1: envelopes of an expired message are unreadable before the purge runs';
  perform pg_temp.act_as(v_alice, s_alice);
  select count(*) into v_count from public.mm_messages where id = v_msg2;
  assert v_count = 0, 'D-1: the sender cannot read an expired message either';
  assert pg_temp.refused(format($q$select public.mm_pin_message(%L, true)$q$, v_msg2)),
    'D-1: an expired message cannot be pinned';
  assert pg_temp.refused($q$select public.mm_purge_expired_messages()$q$),
    'D-1: clients must not call the purge';
  reset role;
  assert public.mm_purge_expired_messages() = 2, 'D-1: the purge removes exactly the expired rows';
  assert not exists (select 1 from public.mm_messages where id in (v_msg2, v_edit)), 'purged rows are gone';
  assert not exists (select 1 from public.mm_message_envelopes where message_id = v_msg2), 'envelopes cascade';
  assert exists (select 1 from public.mm_messages where id = v_msg), 'unexpired rows survive the purge';
  perform pg_temp.act_as(v_alice, s_alice);
  assert public.mm_set_message_ttl(v_direct, 0) = 0, 'the timer can be switched off';
  assert (select message_ttl_seconds from public.mm_conversations where id = v_direct) is null, 'timer cleared';

  -------------------------------------------------------------------- pinned messages
  perform pg_temp.act_as(v_bob, s_bob);
  assert public.mm_pin_message(v_msg, true), 'either side of a direct chat may pin';
  assert public.mm_pin_message(v_msg, true), 'pinning twice is idempotent';
  select count(*) into v_count from public.mm_pinned_messages where conversation_id = v_direct;
  assert v_count = 1, 'one pin row';
  perform pg_temp.act_as(v_mallory, s_mallory);
  select count(*) into v_count from public.mm_pinned_messages where conversation_id = v_direct;
  assert v_count = 0, 'an outsider must not read pins';
  assert pg_temp.refused(format($q$select public.mm_pin_message(%L, true)$q$, v_msg)),
    'an outsider must not pin';
  assert pg_temp.run(format($q$select public.mm_pin_message(%L, true)$q$, v_msg)) like '%message_not_found%',
    'an outsider gets the same answer as for a missing message';
  assert pg_temp.refused(format(
    $q$insert into public.mm_pinned_messages (conversation_id, message_id, pinned_by) values (%L, %L, %L)$q$,
    v_direct, v_msg, v_mallory)), 'pins are not directly writable';
  perform pg_temp.act_as(v_alice, s_alice);
  insert into public.mm_messages (conversation_id, sender_id, sender_device_id, client_msg_id, kind, meta, sent_at_ms)
  values (v_group, v_alice, 'pq-device-alice', 'pq-group-1', 'text', '{}', 1) returning id into v_gmsg;
  perform pg_temp.act_as(v_bob, s_bob);
  assert pg_temp.refused(format($q$select public.mm_pin_message(%L, true)$q$, v_gmsg)),
    'a plain group member must not pin';
  assert pg_temp.refused(format($q$delete from public.mm_pinned_messages where message_id = %L$q$, v_msg)),
    'pins are not directly deletable';
  perform pg_temp.act_as(v_alice, s_alice);
  assert public.mm_pin_message(v_gmsg, true), 'a group admin may pin';
  assert not public.mm_pin_message(v_gmsg, false), 'and unpin';
  assert public.mm_pin_message(v_msg, true), 'still pinned in the direct chat';
  update public.mm_messages set deleted_at = now() where id = v_msg and conversation_id = v_direct;
  -- Pins of soft-deleted messages can no longer be created.
  assert pg_temp.refused(format($q$select public.mm_pin_message(%L, true)$q$, v_msg)),
    'a deleted message cannot be pinned';

  -------------------------------------------------------- G-1 group/channel administration
  perform pg_temp.act_as(v_bob, s_bob);
  assert pg_temp.refused(format($q$select public.mm_update_conversation(%L, 'Hijacked', null)$q$, v_group)),
    'G-1: a plain member must not rename the group';
  assert pg_temp.refused(format($q$select public.mm_set_member_role(%L, %L, 'admin')$q$, v_group, v_bob)),
    'G-1: a member must not promote themselves';
  assert pg_temp.refused(format($q$select public.mm_transfer_ownership(%L, %L)$q$, v_group, v_bob)),
    'G-1: a member must not take ownership';
  assert pg_temp.refused(format($q$select public.mm_set_ban(%L, %L, true)$q$, v_group, v_carol)),
    'G-1: a member must not ban';
  perform pg_temp.act_as(v_mallory, s_mallory);
  assert pg_temp.refused(format($q$select public.mm_update_conversation(%L, 'Hijacked', null)$q$, v_group)),
    'G-1: an outsider must not rename the group';
  assert pg_temp.refused(format($q$select public.mm_update_conversation(%L, 'Hijacked', null)$q$, v_direct)),
    'G-1: a direct chat has no title to administer';

  perform pg_temp.act_as(v_alice, s_alice);
  assert public.mm_update_conversation(v_group, '  Renamed  ', 'About'), 'the owner renames the group';
  assert (select title from public.mm_conversations where id = v_group) = 'Renamed', 'title trimmed and stored';
  assert pg_temp.refused(format($q$select public.mm_update_conversation(%L, '   ', null)$q$, v_group)),
    'G-1: a blank title is refused';
  assert pg_temp.refused(format($q$select public.mm_set_member_role(%L, %L, 'owner')$q$, v_group, v_bob)),
    'G-1: owner is not an assignable role';
  assert pg_temp.refused(format($q$select public.mm_set_member_role(%L, %L, 'subscriber')$q$, v_group, v_bob)),
    'G-1: subscriber is not a group role';
  assert pg_temp.refused(format($q$select public.mm_set_member_role(%L, %L, 'member')$q$, v_group, v_alice)),
    'G-1: the owner cannot demote themselves';
  assert public.mm_set_member_role(v_group, v_bob, 'admin'), 'the owner appoints an admin';

  perform pg_temp.act_as(v_bob, s_bob);
  assert public.mm_update_conversation(v_group, 'By admin', null), 'an admin may rename';
  assert (select description from public.mm_conversations where id = v_group) = 'About',
    'a null description leaves the old one in place';
  assert pg_temp.refused(format($q$select public.mm_set_member_role(%L, %L, 'admin')$q$, v_group, v_carol)),
    'G-1: only the owner appoints admins';
  assert pg_temp.refused(format($q$select public.mm_set_ban(%L, %L, true)$q$, v_group, v_alice)),
    'G-1: the owner cannot be banned';
  assert pg_temp.refused(format($q$select public.mm_remove_group_member(%L, %L)$q$, v_group, v_alice)),
    'G-1: the owner cannot be removed';

  -- Ban: removes the member, rotates the group key epoch, and blocks re-adding.
  select sender_key_epoch into v_epoch from public.mm_conversations where id = v_group;
  assert public.mm_set_ban(v_group, v_carol, true), 'an admin bans a member';
  assert not exists (select 1 from public.mm_conversation_members
                      where conversation_id = v_group and user_id = v_carol), 'a banned member is removed';
  assert (select sender_key_epoch from public.mm_conversations where id = v_group) = v_epoch + 1,
    'G-1: a ban rotates the sender-key epoch';
  assert pg_temp.refused(format($q$select public.mm_add_group_members(%L, array[%L]::uuid[])$q$, v_group, v_carol)),
    'G-1: a banned account cannot be added back';
  perform pg_temp.act_as(v_carol, s_carol);
  select count(*) into v_count from public.mm_conversation_bans;
  assert v_count = 0, 'the ban list is visible to staff only';
  select count(*) into v_count from public.mm_messages where conversation_id = v_group;
  assert v_count = 0, 'a banned member loses access to the history';
  perform pg_temp.act_as(v_bob, s_bob);
  assert not public.mm_set_ban(v_group, v_carol, false), 'an admin lifts the ban';
  perform public.mm_add_group_members(v_group, array[v_carol]);
  assert exists (select 1 from public.mm_conversation_members
                  where conversation_id = v_group and user_id = v_carol), 'an unbanned account can be added again';

  -- Admin versus admin needs the owner.
  perform pg_temp.act_as(v_alice, s_alice);
  assert public.mm_set_member_role(v_group, v_carol, 'admin'), 'second admin';
  perform pg_temp.act_as(v_bob, s_bob);
  assert pg_temp.refused(format($q$select public.mm_remove_group_member(%L, %L)$q$, v_group, v_carol)),
    'G-1: an admin must not remove another admin';
  assert pg_temp.refused(format($q$select public.mm_set_ban(%L, %L, true)$q$, v_group, v_carol)),
    'G-1: an admin must not ban another admin';
  perform pg_temp.act_as(v_alice, s_alice);
  assert public.mm_set_member_role(v_group, v_carol, 'member'), 'the owner dismisses an admin';

  -- Ownership transfer.
  assert pg_temp.refused(format($q$select public.mm_transfer_ownership(%L, %L)$q$, v_group, v_mallory)),
    'G-1: ownership only goes to a member';
  assert public.mm_transfer_ownership(v_group, v_bob), 'the owner hands the group over';
  assert (select owner_id from public.mm_conversations where id = v_group) = v_bob, 'owner_id follows';
  assert (select role from public.mm_conversation_members where conversation_id = v_group and user_id = v_alice) = 'admin',
    'the previous owner becomes an admin';
  assert (select count(*) from public.mm_conversation_members where conversation_id = v_group and role = 'owner') = 1,
    'exactly one owner';
  assert pg_temp.refused(format($q$select public.mm_transfer_ownership(%L, %L)$q$, v_group, v_alice)),
    'G-1: the previous owner cannot take it back';

  -- Channels: bans stick, invites rotate.
  perform pg_temp.act_as(v_alice, s_alice);
  assert public.mm_set_ban(v_channel, v_mallory, true), 'channel staff bans a subscriber';
  perform pg_temp.act_as(v_mallory, s_mallory);
  assert pg_temp.refused(format($q$select public.mm_join_channel(%L, null)$q$, v_channel)),
    'G-1: a banned account cannot rejoin a public channel';
  assert pg_temp.run(format($q$select public.mm_join_channel(%L, null)$q$, v_channel)) like '%channel_not_found%',
    'a banned account gets the not-found answer';
  select count(*) into v_count from public.mm_messages where conversation_id = v_channel;
  assert v_count = 0, 'a banned subscriber reads nothing';
  perform pg_temp.act_as(v_dave, s_dave);
  assert pg_temp.refused(format($q$select public.mm_set_member_role(%L, %L, 'admin')$q$, v_channel, v_dave)),
    'G-1: a subscriber must not promote themselves';
  assert pg_temp.refused(format($q$select public.mm_rotate_invite(%L)$q$, v_channel)),
    'G-1: a subscriber must not rotate invites';
  perform pg_temp.act_as(v_alice, s_alice);
  assert pg_temp.refused(format($q$select public.mm_rotate_invite(%L)$q$, v_channel)),
    'a public channel has no invite code';
  assert pg_temp.refused(format($q$select public.mm_set_member_role(%L, %L, 'member')$q$, v_channel, v_dave)),
    'G-1: member is not a channel role';
  assert public.mm_set_member_role(v_channel, v_dave, 'admin'), 'the channel owner appoints an admin';
  v_private := public.mm_create_channel('PQ private', null, false, null);
  select invite_code into v_code from public.mm_conversations where id = v_private;
  v_code2 := public.mm_rotate_invite(v_private);
  assert v_code2 is not null and v_code2 <> v_code and v_code2 !~ '[/+=]', 'the invite code is replaced, URL-safe';
  perform pg_temp.act_as(v_carol, s_carol);
  assert pg_temp.refused(format($q$select public.mm_join_channel(%L, %L)$q$, v_private, v_code)),
    'G-1: a rotated-out invite must stop working';
  assert public.mm_join_channel(v_private, v_code2), 'the new invite works';
end
$test$;

rollback;
