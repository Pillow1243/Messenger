-- Authorization & security regression suite for the Messenger hybrid backend (Phase 1 + Phase 2).
--
-- Wrapped in BEGIN ... ROLLBACK so every table, RPC, and storage.objects RLS policy is exercised
-- under real `authenticated` and `anon` role impersonation while leaving zero test residue.

begin;

do $test$
declare
  v_alice uuid := '11111111-1111-1111-1111-111111111111';
  v_bob   uuid := '22222222-2222-2222-2222-222222222222';
  v_carol uuid := '44444444-4444-4444-4444-444444444444';
  v_mallory uuid := '33333333-3333-3333-3333-333333333333';
  v_alice_device uuid;
  v_alice_device_2 uuid;
  v_bob_device uuid;
  v_carol_device uuid;
  v_mallory_device uuid;
  v_conv uuid;
  v_group uuid;
  v_call uuid;
  v_msg uuid;
  v_att uuid;
  v_sig_1 bigint;
  v_sig_2 bigint;
  v_epoch_before integer;
  v_epoch_after integer;
  v_dist_before uuid;
  v_dist_after uuid;
  v_count integer;
  v_bundle jsonb;
  v_failed boolean;
begin
  -- 0. Ensure RLS enabled on all mm_* tables and mm-media bucket is strictly private
  select count(*) into v_count
    from pg_tables
   where schemaname = 'public'
     and tablename like 'mm\_%' escape '\'
     and rowsecurity = false;
  assert v_count = 0, 'all mm_* tables must have RLS enabled, found ' || v_count || ' without RLS';

  select count(*) into v_count
    from storage.buckets
   where id = 'mm-media' and public = false;
  assert v_count = 1, 'mm-media bucket must exist and be private';

  -- 1. Fixtures
  insert into auth.users (id, instance_id, aud, role, email, email_confirmed_at,
                          created_at, updated_at, raw_app_meta_data, raw_user_meta_data,
                          is_sso_user, is_anonymous)
  values
    (v_alice, '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated',
     'alice@example.invalid', now(), now(), now(), '{}'::jsonb, '{}'::jsonb, false, false),
    (v_bob, '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated',
     'bob@example.invalid', now(), now(), now(), '{}'::jsonb, '{}'::jsonb, false, false),
    (v_carol, '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated',
     'carol@example.invalid', now(), now(), now(), '{}'::jsonb, '{}'::jsonb, false, false),
    (v_mallory, '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated',
     'mallory@example.invalid', now(), now(), now(), '{}'::jsonb, '{}'::jsonb, false, false)
  on conflict (id) do nothing;

  -- 2. Alice + Multi-device registration & 5-device cap
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_alice, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';

  perform public.mm_ensure_profile('Alice', 'alice_rls_test');
  v_alice_device := public.mm_register_device(
    'device-alice-1', 'Pixel Alice 1', 'android', 'alice-identity-key-b64', 111,
    '{"id":1,"public":"YWxpY2Utc2lnbmVk","signature":"YWxpY2Utc2ln"}'::jsonb,
    '{"id":1,"public":"YWxpY2Uta3liZXI=","signature":"YWxpY2Uta3liZXItc2ln"}'::jsonb,
    '[{"id":1,"public":"YWxpY2UtcHJla2V5"}]'::jsonb);
  assert v_alice_device is not null, 'alice device 1 registration failed';

  v_alice_device_2 := public.mm_register_device(
    'device-alice-2', 'Pixel Alice 2', 'android', 'alice-identity-key-2-b64', 112,
    '{"id":1,"public":"YWxpY2Utc2lnbmVkMg==","signature":"YWxpY2Utc2lnMg=="}'::jsonb,
    '{"id":1,"public":"YWxpY2Uta3liZXIy","signature":"YWxpY2Uta3liZXItc2lnMg=="}'::jsonb,
    '[{"id":1,"public":"YWxpY2UtcHJla2V5Mg=="}]'::jsonb);
  perform public.mm_register_device(
    'device-alice-3', 'Pixel Alice 3', 'android', 'alice-id-3', 113,
    '{"id":1,"public":"YWxpY2U=","signature":"YWxpY2U="}'::jsonb,
    '{"id":1,"public":"YWxpY2U=","signature":"YWxpY2U="}'::jsonb, '[]'::jsonb);
  perform public.mm_register_device(
    'device-alice-4', 'Pixel Alice 4', 'android', 'alice-id-4', 114,
    '{"id":1,"public":"YWxpY2U=","signature":"YWxpY2U="}'::jsonb,
    '{"id":1,"public":"YWxpY2U=","signature":"YWxpY2U="}'::jsonb, '[]'::jsonb);
  perform public.mm_register_device(
    'device-alice-5', 'Pixel Alice 5', 'android', 'alice-id-5', 115,
    '{"id":1,"public":"YWxpY2U=","signature":"YWxpY2U="}'::jsonb,
    '{"id":1,"public":"YWxpY2U=","signature":"YWxpY2U="}'::jsonb, '[]'::jsonb);

  v_failed := false;
  begin
    perform public.mm_register_device(
      'device-alice-6', 'Pixel Alice 6', 'android', 'alice-id-6', 116,
      '{"id":1,"public":"YWxpY2U=","signature":"YWxpY2U="}'::jsonb,
      '{"id":1,"public":"YWxpY2U=","signature":"YWxpY2U="}'::jsonb, '[]'::jsonb);
  exception when others then
    v_failed := true;
  end;
  assert v_failed, '6th active device registration must be rejected';

  perform public.mm_register_push_token('device-alice-1', 'fcm', 'fcm-token-alice-device-0001', 'com.offlinemesh.messenger', 'en');
  perform public.mm_register_push_token('device-alice-2', 'fcm', 'fcm-token-alice-device-0002', 'com.offlinemesh.messenger', 'fa');
  select count(*) into v_count from public.mm_push_tokens where user_id = v_alice and revoked_at is null;
  assert v_count = 2, 'alice must see her 2 active push tokens';

  perform public.mm_rotate_signed_prekeys(
    'device-alice-1',
    '{"id":2,"public":"YWxpY2Utc2lnbmVkLXYy","signature":"YWxpY2Utc2lnLXYy"}'::jsonb,
    '{"id":2,"public":"YWxpY2Uta3liZXItdjI=","signature":"YWxpY2Uta3liZXItc2lnLXYy"}'::jsonb
  );

  -- 3. Bob & Carol sessions
  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_bob, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';
  perform public.mm_ensure_profile('Bob', 'bob_rls_test');
  v_bob_device := public.mm_register_device(
    'device-bob-1', 'Pixel Bob', 'android', 'bob-identity-key-b64', 222,
    '{"id":1,"public":"Ym9iLXNpZ25lZA==","signature":"Ym9iLXNpZw=="}'::jsonb,
    '{"id":1,"public":"Ym9iLWt5YmVy","signature":"Ym9iLWt5Yi1zaWc="}'::jsonb,
    '[{"id":1,"public":"Ym9iLXByZWtleQ=="}]'::jsonb);
  perform public.mm_register_push_token('device-bob-1', 'fcm', 'fcm-token-bob-device-00001', 'com.offlinemesh.messenger', 'en');

  select count(*) into v_count from public.mm_push_tokens where user_id = v_alice;
  assert v_count = 0, 'bob must never read alice push tokens';
  select count(*) into v_count from public.mm_notification_prefs where user_id = v_alice;
  assert v_count = 0, 'bob must never read alice notification prefs';

  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_carol, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';
  perform public.mm_ensure_profile('Carol', 'carol_rls_test');
  v_carol_device := public.mm_register_device(
    'device-carol-1', 'Pixel Carol', 'android', 'carol-identity-key-b64', 333,
    '{"id":1,"public":"Y2Fyb2wtc2lnbmVk","signature":"Y2Fyb2wtc2ln"}'::jsonb,
    '{"id":1,"public":"Y2Fyb2wta3liZXI=","signature":"Y2Fyb2wta3liLXNpZw=="}'::jsonb,
    '[{"id":1,"public":"Y2Fyb2wtcHJla2V5"}]'::jsonb);

  -- 4. Direct conversation, E2EE media & Storage RLS
  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_alice, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';
  v_conv := public.mm_start_direct_conversation(v_bob);
  assert v_conv is not null, 'direct conversation not created';

  insert into public.mm_attachments (
    conversation_id, uploader_id, bucket, object_path, file_name, mime_type, size_bytes, sha256
  ) values (
    v_conv, v_alice, 'mm-media', v_conv::text || '/enc-blob-1.bin',
    'encrypted.bin', 'application/octet-stream', 4096,
    'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'
  ) returning id into v_att;

  insert into storage.objects (bucket_id, name, owner, owner_id)
  values ('mm-media', v_conv::text || '/enc-blob-1.bin', v_alice, v_alice::text);

  insert into public.mm_messages (conversation_id, sender_id, sender_device_id, client_msg_id, kind, attachment_id)
  values (v_conv, v_alice, 'device-alice-1', 'client-msg-alice-1', 'file', v_att)
  returning id into v_msg;

  insert into public.mm_message_envelopes
    (message_id, recipient_user_id, recipient_device, sender_device, cipher_type, ciphertext)
  values (v_msg, v_bob, v_bob_device, v_alice_device, 3, 'ciphertext-for-bob-device');

  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_bob, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';
  v_bundle := public.mm_claim_prekey_bundle(v_alice_device);
  assert (v_bundle->>'identityKey') = 'alice-identity-key-b64', 'bundle should expose identity key';
  assert (v_bundle->'oneTimePreKey'->>'public') = 'YWxpY2UtcHJla2V5', 'bundle should carry one-time pre-key';
  v_bundle := public.mm_claim_prekey_bundle(v_alice_device);
  assert v_bundle->'oneTimePreKey' = 'null'::jsonb, 'one-time pre-key must be single use';

  select count(*) into v_count from public.mm_attachments where id = v_att;
  assert v_count = 1, 'conversation member bob must read attachment metadata';
  select count(*) into v_count from storage.objects
   where bucket_id = 'mm-media' and name = v_conv::text || '/enc-blob-1.bin';
  assert v_count = 1, 'conversation member bob must be allowed by mm-media read policy';

  -- 5. Outsider (Mallory) isolation across ALL surfaces
  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_mallory, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';
  perform public.mm_ensure_profile('Mallory', 'mallory_rls_test');
  v_mallory_device := public.mm_register_device(
    'device-mallory-1', 'Pixel Mallory', 'android', 'mallory-identity-key-b64', 999,
    '{"id":1,"public":"bWFsbG9yeS1zaWduZWQ=","signature":"bWFsbG9yeS1zaWc="}'::jsonb,
    '{"id":1,"public":"bWFsbG9yeS1reWJlcg==","signature":"bWFsbG9yeS1reWItc2ln"}'::jsonb,
    '[]'::jsonb);

  select count(*) into v_count from public.mm_messages where conversation_id = v_conv;
  assert v_count = 0, 'outsider must not read messages';
  select count(*) into v_count from public.mm_message_envelopes where message_id = v_msg;
  assert v_count = 0, 'outsider must not read ciphertext envelopes';
  select count(*) into v_count from public.mm_attachments where id = v_att;
  assert v_count = 0, 'outsider must not read attachment metadata';
  select count(*) into v_count from storage.objects
   where bucket_id = 'mm-media' and name = v_conv::text || '/enc-blob-1.bin';
  assert v_count = 0, 'outsider must NEVER read private mm-media storage objects';

  v_failed := false;
  begin
    insert into storage.objects (bucket_id, name, owner, owner_id)
    values ('mm-media', v_conv::text || '/mallory-inject.bin', v_mallory, v_mallory::text);
  exception when others then
    v_failed := true;
  end;
  assert v_failed, 'outsider must not upload into foreign conversation storage prefix';

  -- 6. Sender Keys (v2) group encryption & key rotation
  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_alice, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';

  v_group := public.mm_create_group('Mesh Ops', array[v_bob, v_carol]);
  select encryption_version, sender_key_epoch, distribution_id
    into v_count, v_epoch_before, v_dist_before
    from public.mm_conversations where id = v_group;
  assert v_count = 2 and v_epoch_before = 1, 'new groups must use Sender Keys v2 epoch 1';

  insert into public.mm_sender_key_distributions (
    conversation_id, epoch, distribution_id,
    sender_user_id, sender_device, recipient_user_id, recipient_device,
    cipher_type, ciphertext
  ) values
    (v_group, v_epoch_before, v_dist_before, v_alice, v_alice_device, v_bob, v_bob_device, 3, 'skdm-cipher-for-bob'),
    (v_group, v_epoch_before, v_dist_before, v_alice, v_alice_device, v_carol, v_carol_device, 3, 'skdm-cipher-for-carol');

  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_carol, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';
  select count(*) into v_count from public.mm_sender_key_distributions where conversation_id = v_group;
  assert v_count = 1, 'carol must read only her own SKDM while a member';

  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_alice, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';
  perform public.mm_remove_group_member(v_group, v_carol);

  select sender_key_epoch, distribution_id into v_epoch_after, v_dist_after
    from public.mm_conversations where id = v_group;
  assert v_epoch_after = v_epoch_before + 1 and v_dist_after <> v_dist_before,
    'removing a group member must rotate sender_key_epoch and distribution_id';

  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_carol, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';
  select count(*) into v_count from public.mm_conversations where id = v_group;
  assert v_count = 0, 'removed member carol must not see the group';
  select count(*) into v_count from public.mm_sender_key_distributions where conversation_id = v_group;
  assert v_count = 0, 'removed member carol must have zero access to group SKDMs';

  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_alice, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';
  v_epoch_before := v_epoch_after;
  perform public.mm_revoke_device(v_alice_device_2);
  select sender_key_epoch into v_epoch_after from public.mm_conversations where id = v_group;
  assert v_epoch_after = v_epoch_before + 1, 'device revocation must rotate v2 group Sender Keys';
  select count(*) into v_count from public.mm_push_tokens
   where device_id = v_alice_device_2 and revoked_at is null;
  assert v_count = 0, 'revoking a device must immediately revoke its push token';

  -- 7. 1:1 WebRTC Call signalling hardening
  v_failed := false;
  begin
    perform public.mm_create_call(v_group, 'voice');
  exception when others then
    v_failed := true;
  end;
  assert v_failed, 'mm_create_call must reject non-direct conversations';

  v_call := public.mm_create_call(v_conv, 'video');
  assert v_call is not null, '1:1 video call must be created';

  v_sig_1 := public.mm_send_call_signal(v_call, v_bob, 'offer', '{"sdp":"v=0..."}'::jsonb, 'offer-1');
  v_sig_2 := public.mm_send_call_signal(v_call, v_bob, 'offer', '{"sdp":"v=0..."}'::jsonb, 'offer-1');
  assert v_sig_1 = v_sig_2, 'duplicate client_signal_id must be idempotent';

  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_mallory, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';
  select count(*) into v_count from public.mm_call_signals where call_id = v_call;
  assert v_count = 0, 'outsider must not read call signals';
  v_failed := false;
  begin
    perform public.mm_send_call_signal(v_call, v_bob, 'ice', '{}'::jsonb, 'forged-ice');
  exception when others then
    v_failed := true;
  end;
  assert v_failed, 'outsider must not send call signals';

  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_bob, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';
  perform public.mm_set_call_state(v_call, 'active', null);
  perform public.mm_set_call_state(v_call, 'ended', 'hangup');
  perform public.mm_set_call_state(v_call, 'active', null);
  select count(*) into v_count from public.mm_calls where id = v_call and state = 'ended';
  assert v_count = 1, 'ended call must remain in terminal ended state';

  -- 8. Anonymous role denial across old + new tables
  execute 'reset role';
  perform set_config('request.jwt.claims', '{"role":"anon"}'::text, true);
  execute 'set local role anon';
  v_failed := false;
  begin
    select count(*) into v_count from public.mm_push_tokens;
  exception when insufficient_privilege then
    v_failed := true;
  end;
  assert v_failed, 'anon must hold zero privileges on mm_push_tokens';
  v_failed := false;
  begin
    select count(*) into v_count from public.mm_sender_key_distributions;
  exception when insufficient_privilege then
    v_failed := true;
  end;
  assert v_failed, 'anon must hold zero privileges on mm_sender_key_distributions';

  -- 8b. Function privileges (migration 20261112035022): no mm_* function is executable by anon,
  --     and the pre-key claim refuses a request without a session even when the device exists.
  assert (select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace
           where n.nspname = 'public' and p.proname like 'mm\_%'
             and has_function_privilege('anon', p.oid, 'EXECUTE')) = 0,
         'anon must not be able to execute any mm_* function';
  v_failed := false;
  begin
    perform public.mm_claim_prekey_bundle(v_alice_device);
  exception when others then
    v_failed := true;
  end;
  assert v_failed, 'pre-key claim must fail for an anonymous caller';

  -- 9. Block semantics & internal rate limit helper
  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_bob, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';
  insert into public.mm_blocks (blocker_id, blocked_id) values (v_bob, v_alice);

  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_alice, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';
  v_failed := false;
  begin
    perform public.mm_create_call(v_conv, 'voice');
  exception when others then
    v_failed := true;
  end;
  assert v_failed, 'blocked user must not be able to initiate a call';

  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_alice, 'role', 'authenticated')::text, true);
  perform public.mm_consume_rate_limit('selftest', 2, 3600);
  perform public.mm_consume_rate_limit('selftest', 2, 3600);
  v_failed := false;
  begin
    perform public.mm_consume_rate_limit('selftest', 2, 3600);
  exception when others then
    v_failed := true;
  end;
  assert v_failed, 'rate limiter must reject the third call';

  execute 'reset role';
  raise notice 'RLS & Phase 2 authorization suite: ALL CHECKS PASSED';
end
$test$;

rollback;
