-- Write-path hardening regression suite (audit findings W-1 .. W-19, migration 20261112120000).
--
-- Every assertion here was first observed FAILING against the previous schema while impersonating
-- the `authenticated` role with forged-but-valid JWT claims. The suite has two halves:
--
--   A. Client contract: the exact statements the Android client issues through PostgREST
--      (`INSERT ... ON CONFLICT ... RETURNING *`, column-scoped PATCHes, RPCs) must succeed.
--      PostgREST turns `Prefer: resolution=ignore-duplicates` into ON CONFLICT DO NOTHING and
--      `resolution=merge-duplicates` into ON CONFLICT DO UPDATE SET <every payload column>.
--   B. Attacks: everything a hostile signed-in user could previously do by skipping the RPCs
--      must now be refused.
--
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

-- Runs one statement as the current role. Returns 'ok:<rows>' or 'err:<message>'.
create function pg_temp.run(p_sql text) returns text
language plpgsql as $$
declare
  v_rows bigint;
begin
  begin
    execute p_sql;
    get diagnostics v_rows = row_count;
  -- `others` deliberately excludes assert_failure (SQLSTATE P0004), which this schema also uses
  -- for "device_not_found"; name it explicitly so that refusal is observed like any other.
  exception when assert_failure or others then
    return 'err:' || sqlerrm;
  end;
  return 'ok:' || v_rows;
end $$;

-- True when the statement raised or affected no row at all.
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
  v_alice   uuid := 'bbbbbbbb-0000-4000-8000-000000000001';
  v_bob     uuid := 'bbbbbbbb-0000-4000-8000-000000000002';
  v_carol   uuid := 'bbbbbbbb-0000-4000-8000-000000000003';
  v_mallory uuid := 'bbbbbbbb-0000-4000-8000-000000000004';
  s_alice   uuid := 'cccccccc-0000-4000-8000-000000000001';
  s_alice2  uuid := 'cccccccc-0000-4000-8000-000000000011';
  s_alice3  uuid := 'cccccccc-0000-4000-8000-000000000021';
  s_bob     uuid := 'cccccccc-0000-4000-8000-000000000002';
  s_carol   uuid := 'cccccccc-0000-4000-8000-000000000003';
  s_mallory uuid := 'cccccccc-0000-4000-8000-000000000004';
  d_alice uuid; d_alice2 uuid; d_bob uuid; d_carol uuid; d_mallory uuid; d_tmp uuid;
  v_direct uuid; v_group uuid; v_foreign uuid;
  v_msg uuid; v_tmp uuid; v_bob_msg uuid; v_foreign_msg uuid; v_call uuid; v_report uuid;
  v_att uuid := gen_random_uuid();
  v_epoch integer; v_epoch2 integer; v_count bigint; v_text text; v_ms bigint;
  k text;
  c_signed constant jsonb := '{"id":1,"public":"c2lnbmVkLXByZWtleQ==","signature":"c2lnbmF0dXJl"}';
  c_kyber  constant jsonb := '{"id":1,"public":"a3liZXItcHJla2V5","signature":"c2lnbmF0dXJl"}';
  c_pre    constant jsonb := '[{"id":1,"public":"b25lLXRpbWUtMQ=="},{"id":2,"public":"b25lLXRpbWUtMg=="}]';
begin
  ---------------------------------------------------------------------------- fixtures
  insert into auth.users (id, email)
  values (v_alice, 'wp-alice@example.invalid'), (v_bob, 'wp-bob@example.invalid'),
         (v_carol, 'wp-carol@example.invalid'), (v_mallory, 'wp-mallory@example.invalid')
  on conflict (id) do nothing;

  perform pg_temp.act_as(v_alice, s_alice);
  perform public.mm_ensure_profile('Alice', 'wp_alice');
  d_alice := public.mm_register_device('wp-device-alice', 'A', 'android', 'wp-alice-identity', 1,
                                       c_signed, c_kyber, c_pre);
  perform pg_temp.act_as(v_bob, s_bob);
  perform public.mm_ensure_profile('Bob', 'wp_bob');
  d_bob := public.mm_register_device('wp-device-bob', 'B', 'android', 'wp-bob-identity', 2,
                                     c_signed, c_kyber, c_pre);
  perform pg_temp.act_as(v_carol, s_carol);
  perform public.mm_ensure_profile('Carol', 'wp_carol');
  d_carol := public.mm_register_device('wp-device-carol', 'C', 'android', 'wp-carol-identity', 3,
                                       c_signed, c_kyber, c_pre);
  perform pg_temp.act_as(v_mallory, s_mallory);
  perform public.mm_ensure_profile('Mallory', 'wp_mallory');
  d_mallory := public.mm_register_device('wp-device-mallory', 'M', 'android', 'wp-mallory-identity', 4,
                                         c_signed, c_kyber, c_pre);
  v_foreign := public.mm_create_group('Mallory only', array[]::uuid[]);
  insert into public.mm_messages (conversation_id, sender_id, sender_device_id, client_msg_id, kind, meta, sent_at_ms)
  values (v_foreign, v_mallory, 'wp-device-mallory', 'wp-foreign-1', 'text', '{}', 1)
  returning id into v_foreign_msg;

  perform pg_temp.act_as(v_alice, s_alice);
  v_direct := public.mm_start_direct_conversation(v_bob);
  v_group := public.mm_create_group('Trio', array[v_bob, v_carol]);
  perform public.mm_upgrade_group_encryption(v_group);

  -- The squatting fixture of the old schema can no longer be created (W-10).
  assert pg_temp.refused(format(
    $q$insert into public.mm_conversations (type, owner_id, direct_key) values ('direct', %L, %L)$q$,
    v_alice, least(v_bob::text, v_carol::text) || ':' || greatest(v_bob::text, v_carol::text))),
    'W-10: a client must not insert conversations directly (direct_key squatting)';

  --------------------------------------------------------------------------- A. contract
  -- A1 (W-1). Every message kind the client emits is accepted, idempotently.
  foreach k in array array['text','file','voice','image','video','audio'] loop
    insert into public.mm_messages (conversation_id, sender_id, sender_device_id,
                                    sender_device_row_id, client_msg_id, kind, meta, sent_at_ms)
    values (v_direct, v_alice, 'wp-device-alice', d_alice, 'wp-kind-' || k, k, '{}',
            (extract(epoch from now()) * 1000)::bigint)
    on conflict (conversation_id, client_msg_id) do nothing
    returning id into v_tmp;
    assert v_tmp is not null, 'W-1: message kind ' || k || ' must be accepted';
    if k = 'text' then v_msg := v_tmp; end if;
  end loop;
  v_tmp := null;
  insert into public.mm_messages (conversation_id, sender_id, sender_device_id, client_msg_id, kind, meta, sent_at_ms)
  values (v_direct, v_alice, 'wp-device-alice', 'wp-kind-text', 'text', '{}', 1)
  on conflict (conversation_id, client_msg_id) do nothing returning id into v_tmp;
  assert v_tmp is null, 'a retried upload must not create a second message row';
  assert (select count(*) from public.mm_messages where conversation_id = v_direct) = 6,
    'exactly one row per client message id';

  -- A2 (W-19). Envelope fan-out: first write wins, a retry is a no-op and never an error.
  for v_count in 1..2 loop
    v_text := pg_temp.run(format(
      $q$insert into public.mm_message_envelopes (message_id, recipient_user_id, recipient_device,
                                                  sender_device, cipher_type, ciphertext)
         values (%L, %L, %L, %L, 3, 'Y2lwaGVydGV4dC0x')
         on conflict (message_id, recipient_device) do nothing returning *$q$,
      v_msg, v_bob, d_bob, d_alice));
    assert v_text like 'ok:%', 'envelope insert / retry must not fail: ' || v_text;
  end loop;
  assert (select count(*) from public.mm_message_envelopes where message_id = v_msg) = 1,
    'the sender can read back the envelope it wrote';
  select id into v_tmp from public.mm_messages where client_msg_id = 'wp-kind-file' and conversation_id = v_direct;
  v_text := pg_temp.run(format(
    $q$insert into public.mm_message_envelopes (message_id, recipient_user_id, recipient_device, cipher_type, ciphertext)
       values (%L, %L, %L, 3, 'Y2lwaGVydGV4dC0y')
       on conflict (message_id, recipient_device) do nothing returning *$q$, v_tmp, v_bob, d_bob));
  assert v_text = 'ok:1', 'an envelope without sender_device is still insertable + returnable: ' || v_text;

  -- A3. Attachment record and sender-key distribution are idempotent too.
  for v_count in 1..2 loop
    v_text := pg_temp.run(format(
      $q$insert into public.mm_attachments (id, conversation_id, uploader_id, bucket, object_path,
                                            file_name, mime_type, size_bytes, sha256)
         values (%L, %L, %L, 'mm-media', %L, 'encrypted.bin', 'application/octet-stream', 1024, %L)
         on conflict (id) do nothing returning *$q$,
      v_att, v_direct, v_alice, v_direct::text || '/' || v_att::text, repeat('a', 64)));
    assert v_text like 'ok:%', 'attachment insert / retry must not fail: ' || v_text;
    v_text := pg_temp.run(format(
      $q$insert into public.mm_sender_key_distributions (conversation_id, epoch, distribution_id,
           sender_user_id, sender_device, recipient_user_id, recipient_device, cipher_type, ciphertext)
         select c.id, c.sender_key_epoch, c.distribution_id, %L, %L, %L, %L, 3, 'c2tkbS1jaXBoZXI='
           from public.mm_conversations c where c.id = %L
         on conflict (conversation_id, epoch, sender_device, recipient_device) do nothing returning *$q$,
      v_alice, d_alice, v_bob, d_bob, v_group));
    assert v_text like 'ok:%', 'sender-key distribution insert / retry must not fail: ' || v_text;
  end loop;

  -- A4. Mute (column-scoped PATCH on the caller's own membership row).
  assert pg_temp.run(format(
    $q$update public.mm_conversation_members set notification_level = 'none', muted_until = null
        where conversation_id = %L and user_id = %L$q$, v_group, v_alice)) = 'ok:1',
    'a member can mute a conversation for itself';

  -- A5 (W-1). Edit: an encrypted "text-edit" row plus the edited_at marker on the original.
  v_text := pg_temp.run(format(
    $q$insert into public.mm_messages (conversation_id, sender_id, sender_device_id, client_msg_id, kind, meta, sent_at_ms)
       values (%L, %L, 'wp-device-alice', %L, 'text-edit', %L, 1)
       on conflict (conversation_id, client_msg_id) do nothing returning *$q$,
    v_direct, v_alice, v_msg::text || ':edit:1', json_build_object('target', v_msg, 'edited_at', 1)::text));
  assert v_text = 'ok:1', 'the author can publish an edit of its own message: ' || v_text;
  assert pg_temp.run(format(
    $q$update public.mm_messages set edited_at = now() where id = %L and conversation_id = %L$q$,
    v_msg, v_direct)) = 'ok:1', 'the author can mark its message edited';

  -- A6. Reactions, typing, notification preferences (merge upserts set every payload column).
  assert pg_temp.run(format(
    $q$insert into public.mm_reactions (message_id, user_id, emoji) values (%L, %L, '👍')
       on conflict (message_id, user_id, emoji) do nothing returning *$q$, v_msg, v_alice)) = 'ok:1',
    'a member can react';
  for v_count in 1..2 loop
    v_text := pg_temp.run(format(
      $q$insert into public.mm_typing (conversation_id, user_id, expires_at)
         values (%L, %L, now() + interval '12 seconds')
         on conflict (conversation_id, user_id) do update set
           conversation_id = excluded.conversation_id, user_id = excluded.user_id,
           expires_at = excluded.expires_at returning *$q$, v_direct, v_alice));
    assert v_text = 'ok:1', 'typing upsert must work repeatedly: ' || v_text;
    v_text := pg_temp.run(format(
      $q$insert into public.mm_notification_prefs (user_id, enabled, direct_enabled, group_enabled, channel_enabled, calls_enabled)
         values (%L, true, true, false, true, true)
         on conflict (user_id) do update set user_id = excluded.user_id, enabled = excluded.enabled,
           direct_enabled = excluded.direct_enabled, group_enabled = excluded.group_enabled,
           channel_enabled = excluded.channel_enabled, calls_enabled = excluded.calls_enabled returning *$q$,
      v_alice));
    assert v_text = 'ok:1', 'notification preference upsert must work repeatedly: ' || v_text;
  end loop;

  -- A7. The recipient fetches, acknowledges and deletes its envelope, then reads the message.
  perform pg_temp.act_as(v_bob, s_bob);
  assert (select count(*) from public.mm_message_envelopes where recipient_user_id = v_bob) = 2,
    'the recipient sees its envelopes';
  for v_count in 1..2 loop
    v_text := pg_temp.run(format(
      $q$insert into public.mm_message_receipts (message_id, user_id, delivered_at) values (%L, %L, now())
         on conflict (message_id, user_id) do update set message_id = excluded.message_id,
           user_id = excluded.user_id, delivered_at = excluded.delivered_at returning *$q$, v_msg, v_bob));
    assert v_text = 'ok:1', 'delivery receipt upsert must work repeatedly: ' || v_text;
  end loop;
  perform public.mm_set_read(v_direct, v_msg);
  assert pg_temp.run(format(
    $q$delete from public.mm_message_envelopes where message_id = %L and recipient_user_id = %L$q$,
    v_msg, v_bob)) = 'ok:1', 'the recipient deletes a consumed envelope';
  insert into public.mm_messages (conversation_id, sender_id, sender_device_id, client_msg_id, kind, meta, sent_at_ms)
  values (v_direct, v_bob, 'wp-device-bob', 'wp-bob-1', 'text', '{}', 1) returning id into v_bob_msg;
  assert pg_temp.run(format(
    $q$delete from public.mm_sender_key_distributions where recipient_user_id = %L and conversation_id = %L$q$,
    v_bob, v_group)) = 'ok:1', 'the recipient deletes a consumed sender-key distribution';

  -- A7b. The remaining direct deletes and the delete-for-everyone marker.
  perform pg_temp.act_as(v_alice, s_alice);
  select id into v_tmp from public.mm_messages where client_msg_id = 'wp-kind-voice' and conversation_id = v_direct;
  assert pg_temp.run(format(
    $q$update public.mm_messages set deleted_at = now() where id = %L and conversation_id = %L$q$,
    v_tmp, v_direct)) = 'ok:1', 'the author can delete its message for everyone';
  assert pg_temp.run(format(
    $q$delete from public.mm_reactions where message_id = %L and user_id = %L and emoji = '👍'$q$,
    v_msg, v_alice)) = 'ok:1', 'a reaction can be removed';
  assert pg_temp.run(format($q$delete from public.mm_attachments where id = %L$q$, v_att)) = 'ok:1',
    'the uploader can delete its attachment record';
  assert pg_temp.run(format(
    $q$insert into public.mm_blocks (blocker_id, blocked_id) values (%L, %L)
       on conflict (blocker_id, blocked_id) do nothing returning *$q$, v_alice, v_carol)) = 'ok:1'
     and pg_temp.run(format(
    $q$delete from public.mm_blocks where blocker_id = %L and blocked_id = %L$q$, v_alice, v_carol)) = 'ok:1',
    'block and unblock work';

  -- A8 (W-2). Calls: one unambiguous state function; the callee can answer and hang up.
  assert (select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace
           where n.nspname = 'public' and p.proname = 'mm_set_call_state') = 1,
    'W-2: mm_set_call_state must not be overloaded (PostgREST cannot pick a candidate)';
  perform pg_temp.act_as(v_alice, s_alice);
  v_call := public.mm_create_call(v_direct, 'voice');
  perform pg_temp.act_as(v_bob, s_bob);
  perform public.mm_set_call_state(p_call => v_call, p_state => 'active');
  assert (select state from public.mm_calls where id = v_call) = 'active', 'the callee can answer';
  assert pg_temp.run(format(
    $q$select public.mm_send_call_signal(%L, %L, 'bye', '{}'::jsonb, 'wp-signal-bye-1')$q$,
    v_call, v_alice)) like 'ok:%', 'the hang-up signal kind the client sends ("bye") is accepted';

  ---------------------------------------------------------------------------- B. attacks
  -- W-17. Call rows are not writable directly.
  assert pg_temp.refused(format($q$update public.mm_calls set state = 'ringing', initiator_id = %L where id = %L$q$, v_bob, v_call)),
    'W-17: a member must not rewrite a call row';
  assert pg_temp.refused(format($q$update public.mm_call_participants set state = 'declined' where call_id = %L$q$, v_call)),
    'W-17: participants must not be rewritten directly';
  assert pg_temp.refused(format($q$insert into public.mm_calls (conversation_id, initiator_id, kind) values (%L, %L, 'voice')$q$, v_direct, v_bob)),
    'W-17: calls are created through mm_create_call only';
  perform public.mm_set_call_state(v_call, 'ringing', null);
  assert (select state from public.mm_calls where id = v_call) = 'active',
    'an answered call cannot be pushed back to ringing';
  perform public.mm_set_call_state(v_call, 'ended', 'hangup');
  perform public.mm_set_call_state(v_call, 'active', null);
  assert (select state from public.mm_calls where id = v_call) = 'ended',
    'an ended call cannot be resurrected';

  -- W-13. Forged / inconsistent message rows.
  perform pg_temp.act_as(v_alice, s_alice);
  assert pg_temp.refused(format(
    $q$insert into public.mm_messages (conversation_id, sender_id, sender_device_id, client_msg_id, kind, meta, sent_at_ms)
       values (%L, %L, 'wp-device-alice', 'wp-forged-system', 'system', '{}', 1)$q$, v_direct, v_alice)),
    'W-13: a client must not forge system messages';
  assert pg_temp.refused(format(
    $q$insert into public.mm_messages (conversation_id, sender_id, sender_device_id, client_msg_id, kind, meta, sent_at_ms, sent_at)
       values (%L, %L, 'wp-device-alice', 'wp-backdated', 'text', '{}', 1, now() - interval '10 years')$q$, v_direct, v_alice)),
    'W-13: sent_at is server-assigned';
  insert into public.mm_messages (conversation_id, sender_id, sender_device_id, client_msg_id, kind, meta, sent_at_ms)
  values (v_direct, v_alice, 'wp-device-alice', 'wp-future', 'text', '{}', 99999999999999)
  returning sent_at_ms into v_ms;
  assert v_ms < (extract(epoch from now()) * 1000)::bigint + 600000,
    'W-13: a far-future client timestamp is clamped';
  assert pg_temp.refused(format(
    $q$insert into public.mm_messages (conversation_id, sender_id, sender_device_id, sender_device_row_id, client_msg_id, kind, meta, sent_at_ms)
       values (%L, %L, 'wp-device-alice', %L, 'wp-foreign-device', 'text', '{}', 1)$q$, v_direct, v_alice, d_mallory)),
    'W-13: a message must not name another user''s device';
  assert pg_temp.refused(format(
    $q$insert into public.mm_messages (conversation_id, sender_id, sender_device_id, client_msg_id, kind, meta, sent_at_ms, reply_to)
       values (%L, %L, 'wp-device-alice', 'wp-cross-reply', 'text', '{}', 1, %L)$q$, v_direct, v_alice, v_foreign_msg)),
    'W-13: a reply must target a message of the same conversation';
  v_text := pg_temp.run(format(
    $q$insert into public.mm_messages (conversation_id, sender_id, sender_device_id, client_msg_id, kind, meta, sent_at_ms)
       values (%L, %L, 'wp-device-alice', 'wp-edit-of-bob', 'text-edit', %L, 1)$q$,
    v_direct, v_alice, json_build_object('target', v_bob_msg)::text));
  assert v_text like 'err:%edit_not_allowed%', 'W-13: nobody can publish an edit of someone else''s message: ' || v_text;
  assert pg_temp.refused(format($q$update public.mm_messages set conversation_id = %L where id = %L$q$, v_foreign, v_msg)),
    'W-13: a message cannot be moved to another conversation';
  assert pg_temp.refused(format($q$update public.mm_messages set kind = 'system' where id = %L$q$, v_msg)),
    'W-13: the kind of a stored message is immutable for clients';
  assert pg_temp.refused(format($q$update public.mm_messages set deleted_at = now() where id = %L$q$, v_bob_msg)),
    'a member cannot delete somebody else''s message';

  -- W-19. Envelopes must target a live device of the stated recipient, from the caller's device.
  assert pg_temp.refused(format(
    $q$insert into public.mm_message_envelopes (message_id, recipient_user_id, recipient_device, sender_device, cipher_type, ciphertext)
       values (%L, %L, %L, %L, 3, 'bWlzbWF0Y2hlZA==')$q$, v_msg, v_bob, d_carol, d_alice)),
    'W-19: the recipient device must belong to the recipient user';
  assert pg_temp.refused(format(
    $q$insert into public.mm_message_envelopes (message_id, recipient_user_id, recipient_device, sender_device, cipher_type, ciphertext)
       values (%L, %L, %L, %L, 3, 'c3Bvb2ZlZC1zZW5kZXI=')$q$, v_msg, v_bob, d_bob, d_mallory)),
    'W-19: sender_device must be one of the caller''s devices';
  assert pg_temp.refused(format(
    $q$insert into public.mm_message_envelopes (message_id, recipient_user_id, recipient_device, sender_device, cipher_type, ciphertext)
       values (%L, %L, %L, %L, 3, 'b3V0c2lkZXI=')$q$, v_msg, v_mallory, d_mallory, d_alice)),
    'W-19: an envelope cannot be addressed to somebody outside the conversation';
  assert pg_temp.refused(format(
    $q$update public.mm_message_envelopes set ciphertext = 'b3ZlcndyaXR0ZW4=' where recipient_user_id = %L$q$, v_bob)),
    'envelopes are immutable once written';

  -- Receipts and typing.
  assert pg_temp.refused(format(
    $q$insert into public.mm_message_receipts (message_id, user_id, read_at) values (%L, %L, now())$q$, v_foreign_msg, v_alice)),
    'a receipt cannot be created for a message of a foreign conversation';
  insert into public.mm_message_receipts (message_id, user_id, delivered_at) values (v_bob_msg, v_alice, now());
  assert pg_temp.refused(format(
    $q$update public.mm_message_receipts set message_id = %L where message_id = %L and user_id = %L$q$,
    v_foreign_msg, v_bob_msg, v_alice)),
    'an existing receipt cannot be re-pointed at a foreign message';
  assert pg_temp.refused(format(
    $q$update public.mm_typing set expires_at = now() + interval '1 year' where conversation_id = %L and user_id = %L$q$,
    v_direct, v_alice)), 'a typing indicator cannot be parked for a year';

  -- W-9. Read receipts only for messages of the stated conversation.
  perform pg_temp.act_as(v_carol, s_carol);
  assert pg_temp.run(format($q$select public.mm_set_read(%L, %L)$q$, v_group, v_msg)) like 'err:%message_not_found%',
    'W-9: mm_set_read must reject a message of another conversation';
  execute 'reset role';
  assert not exists (select 1 from public.mm_message_receipts where message_id = v_msg and user_id = v_carol),
    'W-9: no forged receipt was written';

  -- W-10 / W-15. Membership and conversation rows.
  perform pg_temp.act_as(v_alice, s_alice);
  assert pg_temp.refused(format(
    $q$insert into public.mm_conversation_members (conversation_id, user_id, role) values (%L, %L, 'admin')$q$, v_direct, v_mallory)),
    'W-10: nobody can add a third member to a direct conversation';
  assert pg_temp.refused(format(
    $q$insert into public.mm_conversation_members (conversation_id, user_id, role) values (%L, %L, 'member')$q$, v_group, v_mallory)),
    'W-10: members are added through mm_add_group_members only (block list, cap, limiter)';
  assert pg_temp.refused(format(
    $q$delete from public.mm_conversation_members where conversation_id = %L and user_id = %L$q$, v_group, v_carol)),
    'W-10: a membership cannot be deleted without the sender-key rotation';
  assert pg_temp.refused(format(
    $q$update public.mm_conversations set owner_id = %L, encryption_version = 1, sender_key_epoch = 1, type = 'channel' where id = %L$q$, v_bob, v_group)),
    'W-10: protected conversation columns are not writable';
  assert pg_temp.refused(format($q$delete from public.mm_conversations where id = %L$q$, v_group)),
    'W-10: conversations are not deletable directly';
  assert pg_temp.refused(format(
    $q$update public.mm_conversation_members set notification_level = 'none' where conversation_id = %L and user_id = %L$q$, v_group, v_bob)),
    'W-15: an admin cannot change another member''s notification settings';
  assert pg_temp.refused(format(
    $q$update public.mm_conversation_members set role = 'owner' where conversation_id = %L and user_id = %L$q$, v_group, v_alice)),
    'roles are not writable directly';
  select sender_key_epoch into v_epoch from public.mm_conversations where id = v_group;
  perform public.mm_remove_group_member(v_group, v_carol);
  select sender_key_epoch into v_epoch2 from public.mm_conversations where id = v_group;
  assert v_epoch2 = v_epoch + 1, 'removing a member through the RPC rotates the sender-key epoch';
  perform pg_temp.act_as(v_carol, s_carol);
  assert (select count(*) from public.mm_messages where conversation_id = v_group) = 0
     and (select count(*) from public.mm_sender_key_distributions where conversation_id = v_group) = 0
     and (select count(*) from public.mm_conversations where id = v_group) = 0,
    'a removed member can read neither messages, sender-key distributions nor the new epoch';

  -- W-4 / W-5 / W-6 / W-7 / W-16. Devices, pre-keys, limiter.
  execute 'reset role';
  select sender_key_epoch into v_epoch from public.mm_conversations where id = v_group;
  perform pg_temp.act_as(v_mallory, s_mallory);
  perform public.mm_start_direct_conversation(v_bob);
  assert pg_temp.run(format($q$select public.mm_revoke_device(%L)$q$, d_bob)) like 'err:%device_not_found%',
    'W-4: a device can only be revoked by its owner';
  assert pg_temp.run(format($q$select public.mm_rotate_user_groups(%L)$q$, v_bob)) like 'err:%permission denied%',
    'W-5: mm_rotate_user_groups is internal';
  assert pg_temp.run($q$select public.mm_consume_rate_limit(repeat('x', 100000), 1000000, 1)$q$) like 'err:%permission denied%',
    'W-6: clients cannot drive the rate limiter';
  assert pg_temp.refused(format(
    $q$insert into public.mm_devices (user_id, device_id, name, platform, identity_key, registration_id,
         signed_prekey_id, signed_prekey_public, signed_prekey_signature, kyber_prekey_id, kyber_prekey_public, kyber_prekey_signature)
       values (%L, 'wp-direct-device', 'x', 'android', 'identity-key-x', 1, 1, 'a', 'b', 1, 'c', 'd')$q$, v_mallory)),
    'W-16: devices are registered through mm_register_device only (cap, limiter)';
  assert pg_temp.refused(format($q$update public.mm_devices set revoked_at = null, identity_key = 'attacker-identity' where id = %L$q$, d_mallory)),
    'W-16: device rows are not writable directly';
  assert pg_temp.refused(format(
    $q$insert into public.mm_device_prekeys (device, prekey_id, prekey_public) values (%L, 999, 'ZGlyZWN0LXByZWtleQ==')$q$, d_mallory)),
    'W-7: pre-keys are uploaded through the RPCs only';
  assert pg_temp.run(format(
    $q$select public.mm_replenish_prekeys('wp-device-mallory',
         (select jsonb_agg(jsonb_build_object('id', g, 'public', 'cHJla2V5LXB1YmxpYw==')) from generate_series(10, 260) g))$q$))
    like 'err:%too_many_prekeys%', 'W-7: the number of unclaimed one-time pre-keys is capped';
  assert pg_temp.run($q$select public.mm_replenish_prekeys('wp-device-mallory',
         '[{"id":50,"public":"cHJla2V5LTUw"},{"id":51,"public":"cHJla2V5LTUx"}]'::jsonb)$q$) = 'ok:1',
    'a normal replenish still works';
  execute 'reset role';
  assert (select count(*) from public.mm_device_prekeys where device = d_bob and claimed_at is null) = 2
     and (select count(*) from public.mm_message_envelopes where recipient_device = d_bob) = 1
     and (select revoked_at from public.mm_devices where id = d_bob) is null,
    'W-4: the victim''s pre-keys, queued envelopes and device are untouched';
  assert (select sender_key_epoch from public.mm_conversations where id = v_group) = v_epoch,
    'W-5: the group epoch did not move';
  assert not exists (select 1 from public.mm_rate_limits where char_length(action) > 64),
    'W-6: no attacker-chosen limiter row exists';

  -- W-8 / W-11 / W-12. Direct chats, search, reports.
  perform pg_temp.act_as(v_mallory, s_mallory);
  assert pg_temp.run(format($q$select public.mm_start_direct_conversation(%L)$q$, gen_random_uuid())) like 'err:%user_not_found%',
    'W-8: a direct conversation needs an existing peer';
  assert (select count(*) from public.mm_search_users('__')) = 0
     and (select count(*) from public.mm_search_users('%%')) = 0,
    'W-11: LIKE wildcards in the query do not enumerate profiles';
  assert (select count(*) from public.mm_search_users('wp_ali')) = 1
     and (select count(*) from public.mm_search_users('@wp_bob')) = 1,
    'W-11: a literal prefix search still finds the user';
  assert pg_temp.run(format($q$select public.mm_report('message', %L, 'spam', null)$q$, v_msg)) like 'err:%target_not_found%',
    'W-12: a message the reporter cannot see cannot be reported';
  assert pg_temp.refused(format(
    $q$insert into public.mm_reports (reporter_id, target_kind, target_id, reason) values (%L, 'user', %L, 'spam')$q$, v_mallory, v_alice)),
    'W-12: reports go through mm_report only (rate limit)';
  v_report := public.mm_report('user', v_alice, 'spam', 'first');
  assert public.mm_report('user', v_alice, 'spam', 'again') = v_report,
    'W-12: an open report about the same target is updated, not duplicated';

  -- W-18. Contact requests.
  assert pg_temp.run(format(
    $q$insert into public.mm_contact_requests (from_user, to_user, message) values (%L, %L, 'hi')$q$, v_mallory, v_carol)) = 'ok:1',
    'a contact request can be sent';
  assert pg_temp.refused(format(
    $q$update public.mm_contact_requests set status = 'accepted' where from_user = %L and to_user = %L$q$, v_mallory, v_carol)),
    'W-18: the sender cannot accept its own request';
  perform pg_temp.act_as(v_carol, s_carol);
  assert pg_temp.run(format(
    $q$update public.mm_contact_requests set status = 'accepted', responded_at = now() where from_user = %L and to_user = %L$q$, v_mallory, v_carol)) = 'ok:1',
    'the recipient can accept';
  perform pg_temp.act_as(v_bob, s_bob);
  assert pg_temp.run(format($q$insert into public.mm_blocks (blocker_id, blocked_id) values (%L, %L)$q$, v_bob, v_mallory)) = 'ok:1',
    'a user can block another';
  perform pg_temp.act_as(v_mallory, s_mallory);
  assert pg_temp.refused(format(
    $q$insert into public.mm_contact_requests (from_user, to_user) values (%L, %L)$q$, v_mallory, v_bob)),
    'W-18: a blocked user cannot send a contact request';
  select id into v_tmp from public.mm_conversations
   where direct_key = least(v_mallory::text, v_bob::text) || ':' || greatest(v_mallory::text, v_bob::text);
  assert pg_temp.refused(format(
    $q$insert into public.mm_messages (conversation_id, sender_id, sender_device_id, client_msg_id, kind, meta, sent_at_ms)
       values (%L, %L, 'wp-device-mallory', 'wp-blocked-1', 'text', '{}', 1)$q$, v_tmp, v_mallory)),
    'a blocked user cannot message the blocker';
  assert pg_temp.refused(format(
    $q$insert into public.mm_typing (conversation_id, user_id, expires_at) values (%L, %L, now() + interval '5 seconds')$q$, v_tmp, v_mallory)),
    'a blocked user cannot send typing indicators to the blocker';
  assert pg_temp.run(format($q$select public.mm_create_call(%L, 'voice')$q$, v_tmp)) like 'err:%',
    'a blocked user cannot ring the blocker';

  -- W-14. Storage: path shape, membership and quota.
  perform pg_temp.act_as(v_alice, s_alice);
  assert public.mm_media_upload_allowed(v_direct::text || '/' || gen_random_uuid()::text),
    'W-14: a member can upload into its conversation prefix';
  assert not public.mm_media_upload_allowed(v_foreign::text || '/' || gen_random_uuid()::text),
    'W-14: no upload into a foreign conversation';
  assert not public.mm_media_upload_allowed(v_direct::text || '/a/b/' || gen_random_uuid()::text)
     and not public.mm_media_upload_allowed('not-a-uuid/object-name-1'),
    'W-14: the object name must be "<conversation>/<object id>"';
  execute 'reset role';
  insert into storage.buckets (id, name, public) values ('mm-media', 'mm-media', false) on conflict (id) do nothing;
  insert into storage.objects (bucket_id, name, owner, metadata)
  values ('mm-media', v_direct::text || '/quota-filler-object', v_alice, '{"size": 5368709120}'::jsonb);
  perform pg_temp.act_as(v_alice, s_alice);
  assert not public.mm_media_upload_allowed(v_direct::text || '/' || gen_random_uuid()::text),
    'W-14: uploads stop once the per-user quota is reached';
  assert pg_temp.refused(format(
    $q$insert into storage.objects (bucket_id, name, owner) values ('mm-media', %L, %L)$q$,
    v_direct::text || '/' || gen_random_uuid()::text, v_alice)),
    'W-14: the storage INSERT policy enforces the quota';
  assert pg_temp.refused(format(
    $q$insert into public.mm_attachments (id, conversation_id, uploader_id, bucket, object_path, file_name, mime_type, size_bytes)
       values (%L, %L, %L, 'mm-media', %L, 'encrypted.bin', 'application/octet-stream', 10)$q$,
    gen_random_uuid(), v_direct, v_alice, v_foreign::text || '/' || gen_random_uuid()::text)),
    'an attachment record must point into its own conversation prefix';
  assert pg_temp.refused(format(
    $q$insert into public.mm_attachments (id, conversation_id, uploader_id, bucket, object_path, file_name, mime_type, size_bytes)
       values (%L, %L, %L, 'chat_media', %L, 'encrypted.bin', 'application/octet-stream', 10)$q$,
    gen_random_uuid(), v_direct, v_alice, v_direct::text || '/' || gen_random_uuid()::text)),
    'an attachment record cannot reference another application''s bucket';

  -- W-3. Revocation ends the session, and a revoked session cannot come back.
  perform pg_temp.act_as(v_alice, s_alice2);
  d_alice2 := public.mm_register_device('wp-device-alice-2', 'A2', 'android', 'wp-alice-identity-2', 5,
                                        c_signed, c_kyber, c_pre);
  select sender_key_epoch into v_epoch from public.mm_conversations where id = v_group;
  perform public.mm_revoke_device(d_alice);
  assert (select sender_key_epoch from public.mm_conversations where id = v_group) = v_epoch + 1,
    'revoking a device rotates the sender-key epoch of the owner''s groups';

  perform pg_temp.act_as(v_alice, s_alice);            -- the revoked (stolen) session
  assert pg_temp.run(format(
    $q$select public.mm_register_device('wp-device-alice', 'A', 'android', 'wp-alice-identity', 1, %L, %L, %L)$q$,
    c_signed, c_kyber, c_pre)) like 'err:%device_revoked%',
    'W-3: a revoked session cannot re-register its device';
  assert pg_temp.run(format(
    $q$select public.mm_register_device('wp-device-evil', 'E', 'android', 'wp-evil-identity', 9, %L, %L, %L)$q$,
    c_signed, c_kyber, c_pre)) like 'err:%device_revoked%',
    'W-3: a revoked session cannot register a new device either';
  assert (select count(*) from public.mm_messages) = 0
     and (select count(*) from public.mm_conversations) = 0
     and (select count(*) from public.mm_message_envelopes) = 0,
    'W-3: a revoked session reads no conversation data';
  assert pg_temp.refused(format(
    $q$insert into public.mm_messages (conversation_id, sender_id, sender_device_id, client_msg_id, kind, meta, sent_at_ms)
       values (%L, %L, 'wp-device-alice', 'wp-after-revoke', 'text', '{}', 1)$q$, v_direct, v_alice)),
    'W-3: a revoked session cannot post';
  assert pg_temp.run(format($q$select public.mm_revoke_device(%L)$q$, d_alice2)) like 'err:%device_revoked%',
    'W-3: a revoked session cannot revoke the owner''s other devices';

  perform pg_temp.act_as(v_alice, null);               -- token without a session id
  assert pg_temp.run(format(
    $q$select public.mm_register_device('wp-device-alice', 'A', 'android', 'wp-alice-identity', 1, %L, %L, %L)$q$,
    c_signed, c_kyber, c_pre)) like 'err:%device_revoked%',
    'W-3: without a session id a revoked device stays revoked';

  perform pg_temp.act_as(v_alice, s_alice2);           -- the legitimate session still works
  assert (select count(*) from public.mm_messages where conversation_id = v_direct) > 0,
    'the owner''s other session is unaffected';

  perform pg_temp.act_as(v_alice, s_alice3);           -- a fresh sign-in may re-link the phone
  select sender_key_epoch into v_epoch from public.mm_conversations where id = v_group;
  d_tmp := public.mm_register_device('wp-device-alice', 'A', 'android', 'wp-alice-identity', 1,
                                     c_signed, c_kyber, c_pre);
  assert d_tmp = d_alice, 're-linking keeps the device row';
  assert (select sender_key_epoch from public.mm_conversations where id = v_group) = v_epoch + 1,
    're-linking a device re-keys the owner''s groups';
  execute 'reset role';
  assert (select revoked_at from public.mm_devices where id = d_alice) is null
     and exists (select 1 from public.mm_revoked_sessions where session_id = s_alice),
    'the old session stays tombstoned after a legitimate re-link';

  ------------------------------------------------------------------ privilege invariants
  select string_agg(table_name || ':' || privilege_type, ', ') into v_text
    from information_schema.role_table_grants
   where table_schema = 'public' and table_name like 'mm\_%' escape '\'
     and (grantee in ('anon', 'PUBLIC')
          or (grantee = 'authenticated' and privilege_type in ('TRUNCATE', 'REFERENCES', 'TRIGGER')));
  assert v_text is null, 'unexpected table privilege: ' || v_text;

  select string_agg(table_name || ':' || privilege_type, ', ') into v_text
    from information_schema.role_table_grants
   where table_schema = 'public' and grantee = 'authenticated'
     and privilege_type in ('INSERT', 'UPDATE', 'DELETE')
     and table_name in ('mm_conversations', 'mm_conversation_members', 'mm_devices', 'mm_device_prekeys',
                        'mm_calls', 'mm_call_participants', 'mm_reports', 'mm_push_tokens',
                        'mm_rate_limits', 'mm_push_log', 'mm_revoked_sessions', 'mm_profiles',
                        'mm_messages');
  assert v_text is null, 'RPC-only table is directly writable: ' || v_text;

  select string_agg(c.relname, ', ') into v_text
    from pg_class c join pg_namespace n on n.oid = c.relnamespace
   where n.nspname = 'public' and c.relkind = 'r' and c.relname like 'mm\_%' escape '\'
     and (has_table_privilege('anon', c.oid, 'SELECT') or has_table_privilege('anon', c.oid, 'INSERT')
          or has_table_privilege('authenticated', c.oid, 'TRUNCATE'));
  assert v_text is null, 'anon/truncate privilege on: ' || v_text;

  assert not has_table_privilege('authenticated', 'public.mm_rate_limits', 'SELECT')
     and not has_table_privilege('authenticated', 'public.mm_push_log', 'SELECT')
     and not has_table_privilege('authenticated', 'public.mm_revoked_sessions', 'SELECT')
     and not has_table_privilege('authenticated', 'public.mm_device_prekeys', 'SELECT'),
    'internal tables are not readable by clients';

  select string_agg(p.oid::regprocedure::text, ', ') into v_text
    from pg_proc p join pg_namespace n on n.oid = p.pronamespace
   where n.nspname in ('public', 'mm_private') and p.prosecdef
     and (n.nspname = 'mm_private' or p.proname like 'mm\_%' escape '\')
     and not exists (select 1 from unnest(coalesce(p.proconfig, '{}')) cfg where cfg like 'search_path=%');
  assert v_text is null, 'SECURITY DEFINER function without a pinned search_path: ' || v_text;

  raise notice 'Write-path hardening suite (W-1..W-19): ALL CHECKS PASSED';
end
$test$;

rollback;
