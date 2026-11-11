-- Authorization regression test for the Messenger backend.
--
-- Run with: select 1; (see supabase/README.md) — or apply through the Supabase SQL editor.
-- The script impersonates real JWT subjects by setting request.jwt.claims and switching to the
-- `authenticated`/`anon` roles, so it exercises the same code path PostgREST uses. Every check
-- raises an exception on failure; the fixtures are removed before the script returns.

do $test$
declare
  v_alice uuid := '11111111-1111-1111-1111-111111111111';
  v_bob   uuid := '22222222-2222-2222-2222-222222222222';
  v_mallory uuid := '33333333-3333-3333-3333-333333333333';
  v_alice_device uuid;
  v_bob_device uuid;
  v_conv uuid;
  v_msg uuid;
  v_count integer;
  v_bundle jsonb;
  v_failed boolean;
begin
  -- ---------------------------------------------------------------- fixtures
  insert into auth.users (id, instance_id, aud, role, email, email_confirmed_at,
                          created_at, updated_at, raw_app_meta_data, raw_user_meta_data,
                          is_sso_user, is_anonymous)
  values
    (v_alice, '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated',
     'alice@example.invalid', now(), now(), now(), '{}'::jsonb, '{}'::jsonb, false, false),
    (v_bob, '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated',
     'bob@example.invalid', now(), now(), now(), '{}'::jsonb, '{}'::jsonb, false, false),
    (v_mallory, '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated',
     'mallory@example.invalid', now(), now(), now(), '{}'::jsonb, '{}'::jsonb, false, false)
  on conflict (id) do nothing;

  -- ------------------------------------------------------------- alice session
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_alice, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';

  perform public.mm_ensure_profile('Alice', 'alice');
  v_alice_device := public.mm_register_device(
    'device-alice-1', 'Pixel Alice', 'android', 'alice-identity-key-b64', 111,
    '{"id":1,"public":"YWxpY2Utc2lnbmVk","signature":"YWxpY2Utc2ln"}'::jsonb,
    '{"id":1,"public":"YWxpY2Uta3liZXI=","signature":"YWxpY2Uta3liZXItc2ln"}'::jsonb,
    '[{"id":1,"public":"YWxpY2UtcHJla2V5"}]'::jsonb);
  assert v_alice_device is not null, 'alice device registration failed';

  -- --------------------------------------------------------------- bob session
  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_bob, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';
  perform public.mm_ensure_profile('Bob', 'bob');
  v_bob_device := public.mm_register_device(
    'device-bob-1', 'Pixel Bob', 'android', 'bob-identity-key-b64', 222,
    '{"id":1,"public":"Ym9iLXNpZ25lZA==","signature":"Ym9iLXNpZw=="}'::jsonb,
    '{"id":1,"public":"Ym9iLWt5YmVy","signature":"Ym9iLWt5Yi1zaWc="}'::jsonb,
    '[{"id":1,"public":"Ym9iLXByZWtleQ=="}]'::jsonb);
  assert v_bob_device is not null, 'bob device registration failed';

  -- ------------------------------------------------------- direct conversation
  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_alice, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';
  v_conv := public.mm_start_direct_conversation(v_bob);
  assert v_conv is not null, 'direct conversation not created';
  select count(*) into v_count from public.mm_conversation_members where conversation_id = v_conv;
  assert v_count = 2, 'direct conversation should have exactly two members, got ' || v_count;

  insert into public.mm_messages (conversation_id, sender_id, sender_device_id, client_msg_id, kind)
  values (v_conv, v_alice, 'device-alice-1', 'client-msg-alice-1', 'text')
  returning id into v_msg;

  insert into public.mm_message_envelopes
    (message_id, recipient_user_id, recipient_device, sender_device, cipher_type, ciphertext)
  values (v_msg, v_bob, v_bob_device, v_alice_device, 3, 'ciphertext-for-bob-device');

  -- a pre-key bundle for alice's device is claimable by bob, who shares a conversation
  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_bob, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';
  v_bundle := public.mm_claim_prekey_bundle(v_alice_device);
  assert (v_bundle->>'identityKey') = 'alice-identity-key-b64', 'bundle should expose identity key';
  assert (v_bundle->'oneTimePreKey'->>'prekeyId') is null
     and (v_bundle->'oneTimePreKey'->>'public') = 'YWxpY2UtcHJla2V5',
    'bundle should carry the one-time pre-key';
  -- the one-time pre-key is now consumed and cannot be handed out twice
  v_bundle := public.mm_claim_prekey_bundle(v_alice_device);
  assert v_bundle->'oneTimePreKey' = 'null'::jsonb, 'one-time pre-key must be single use';

  select count(*) into v_count from public.mm_messages where conversation_id = v_conv;
  assert v_count = 1, 'bob must read the message';
  select count(*) into v_count from public.mm_message_envelopes where message_id = v_msg;
  assert v_count = 1, 'bob must read his envelope';
  select count(*) into v_count from public.mm_device_prekeys;
  assert v_count = 0, 'pre-keys must never be readable through PostgREST';

  -- --------------------------------------------------------- mallory (outsider)
  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_mallory, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';
  perform public.mm_ensure_profile('Mallory', 'mallory');

  select count(*) into v_count from public.mm_messages;
  assert v_count = 0, 'outsider must not read messages';
  select count(*) into v_count from public.mm_message_envelopes;
  assert v_count = 0, 'outsider must not read ciphertext envelopes';
  select count(*) into v_count from public.mm_conversations where id = v_conv;
  assert v_count = 0, 'outsider must not see a private conversation';
  select count(*) into v_count from public.mm_profiles where id = v_alice;
  assert v_count = 0, 'outsider must not read unrelated profiles';

  v_failed := false;
  begin
    insert into public.mm_messages (conversation_id, sender_id, sender_device_id, client_msg_id)
    values (v_conv, v_mallory, 'device-mallory-1', 'client-msg-mallory-1');
  exception when others then
    v_failed := true;
  end;
  assert v_failed, 'outsider must not be able to post into a conversation';

  v_failed := false;
  begin
    perform public.mm_claim_prekey_bundle(v_alice_device);
  exception when others then
    v_failed := true;
  end;
  assert v_failed, 'pre-key bundles must be refused to unrelated users';

  v_failed := false;
  begin
    insert into public.mm_message_receipts (message_id, user_id, read_at)
    values (v_msg, v_mallory, now());
  exception when others then
    v_failed := true;
  end;
  assert v_failed, 'outsider must not forge receipts for foreign messages';

  -- ------------------------------------------------------------------- anon role
  -- Anonymous callers hold no table privileges at all on the mm_ tables, which is stricter
  -- than RLS and is what we assert here (denial, not an empty result set).
  execute 'reset role';
  perform set_config('request.jwt.claims', '{"role":"anon"}'::text, true);
  execute 'set local role anon';
  v_failed := false;
  begin
    select count(*) into v_count from public.mm_messages;
  exception when insufficient_privilege then
    v_failed := true;
  end;
  assert v_failed, 'anonymous callers must be denied on messages';
  v_failed := false;
  begin
    select count(*) into v_count from public.mm_profiles;
  exception when insufficient_privilege then
    v_failed := true;
  end;
  assert v_failed, 'anonymous callers must be denied on profiles';

  -- -------------------------------------------------------------- block semantics
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
    insert into public.mm_messages (conversation_id, sender_id, sender_device_id, client_msg_id)
    values (v_conv, v_alice, 'device-alice-1', 'client-msg-blocked');
  exception when others then
    v_failed := true;
  end;
  assert v_failed, 'a blocked user must not be able to message the blocker';

  -- ---------------------------------------------------------------- rate limiting
  perform public.mm_consume_rate_limit('selftest', 2, 3600);
  perform public.mm_consume_rate_limit('selftest', 2, 3600);
  v_failed := false;
  begin
    perform public.mm_consume_rate_limit('selftest', 2, 3600);
  exception when others then
    v_failed := true;
  end;
  assert v_failed, 'rate limiter must reject the third call';

  assert public.mm_try_uuid('not-a-uuid') is null, 'path parsing must not raise';

  -- -------------------------------------------------------------------- clean up
  execute 'reset role';
  delete from auth.users where id in (v_alice, v_bob, v_mallory);
  raise notice 'RLS authorization suite: ALL CHECKS PASSED';
exception when others then
  execute 'reset role';
  delete from auth.users where id in (v_alice, v_bob, v_mallory);
  raise;
end
$test$;
