-- Social-graph authorisation regression suite (audit finding S-2).
--
-- Before migration 20261112070000 every signed-in user could call the SECURITY DEFINER policy
-- helpers through PostgREST with arbitrary identifiers and learn relationships between *other*
-- people (who shares a conversation with whom, who administers a group, who blocked whom, who is
-- on a call). This suite pins the hardened contract:
--
--   * the caller may ask about itself, and about a co-member of a conversation it belongs to,
--   * everything else answers false/null instead of the truth,
--   * the implementation helpers live in `mm_private`, which no API role can reach,
--   * legitimate RLS behaviour (profiles of co-members, SKDM fan-out, call signalling) still works.
--
-- Runs inside BEGIN/ROLLBACK, so it leaves no residue and can run against a disposable database.

begin;

do $test$
declare
  v_alice   uuid := 'aaaaaaaa-0000-4000-8000-000000000001';
  v_bob     uuid := 'aaaaaaaa-0000-4000-8000-000000000002';
  v_carol   uuid := 'aaaaaaaa-0000-4000-8000-000000000003';
  v_mallory uuid := 'aaaaaaaa-0000-4000-8000-000000000004';
  v_group   uuid;
  v_direct  uuid;
  v_call    uuid;
  v_count   integer;
  v_bool    boolean;
  v_failed  boolean;
begin
  ------------------------------------------------------------------ platform invariants
  -- 0a. Every mm_* table enforces RLS.
  select count(*) into v_count
    from pg_tables
   where schemaname = 'public'
     and tablename like 'mm\_%' escape '\'
     and rowsecurity = false;
  assert v_count = 0, 'every mm_* table must have RLS enabled, missing on ' || v_count;

  -- 0b. Policy coverage: every table is either protected by at least one policy or is internal
  --     bookkeeping that no client role should ever read (no policy = deny all for non-owners).
  select count(*) into v_count
    from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
   where n.nspname = 'public'
     and c.relkind = 'r'
     and c.relname like 'mm\_%' escape '\'
     and not exists (select 1 from pg_policies p
                      where p.schemaname = 'public' and p.tablename = c.relname)
     and c.relname not in ('mm_rate_limits', 'mm_push_log');
  assert v_count = 0,
    'unexpected mm_* table without any RLS policy (add a policy or extend the internal allow-list): ' || v_count;

  -- 0c. The internal helper schema is invisible to every API role.
  assert not has_schema_privilege('anon', 'mm_private', 'USAGE'),
    'anon must not have USAGE on mm_private';
  assert not has_schema_privilege('authenticated', 'mm_private', 'USAGE'),
    'authenticated must not have USAGE on mm_private';
  assert (select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace
           where n.nspname = 'mm_private'
             and (has_function_privilege('anon', p.oid, 'EXECUTE')
               or has_function_privilege('authenticated', p.oid, 'EXECUTE'))) = 0,
    'no mm_private function may be executable by an API role';
  assert (select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace
           where n.nspname = 'public' and p.proname like 'mm\_%'
             and has_function_privilege('anon', p.oid, 'EXECUTE')) = 0,
    'anon must not execute any public mm_* function';

  -- 0d. The subject rate limiter exists for Edge Functions only.
  assert has_function_privilege('service_role',
           'public.mm_consume_rate_limit_as(text,text,integer,integer)', 'EXECUTE'),
    'service_role must be able to rate limit by subject';
  assert not has_function_privilege('authenticated',
           'public.mm_consume_rate_limit_as(text,text,integer,integer)', 'EXECUTE'),
    'authenticated clients must not choose their own rate-limit subject';
  assert not has_function_privilege('anon',
           'public.mm_consume_rate_limit_as(text,text,integer,integer)', 'EXECUTE'),
    'anon must not be able to rate limit by subject';

  ------------------------------------------------------------------------------- fixtures
  insert into auth.users (id, instance_id, aud, role, email, email_confirmed_at,
                          created_at, updated_at, raw_app_meta_data, raw_user_meta_data,
                          is_sso_user, is_anonymous)
  values
    (v_alice, '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated',
     'sg-alice@example.invalid', now(), now(), now(), '{}'::jsonb, '{}'::jsonb, false, false),
    (v_bob, '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated',
     'sg-bob@example.invalid', now(), now(), now(), '{}'::jsonb, '{}'::jsonb, false, false),
    (v_carol, '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated',
     'sg-carol@example.invalid', now(), now(), now(), '{}'::jsonb, '{}'::jsonb, false, false),
    (v_mallory, '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated',
     'sg-mallory@example.invalid', now(), now(), now(), '{}'::jsonb, '{}'::jsonb, false, false)
  on conflict (id) do nothing;

  perform set_config('request.jwt.claims',
    json_build_object('sub', v_alice, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';

  perform public.mm_ensure_profile('Alice', 'sg_alice');
  perform public.mm_register_device(
    'sg-device-alice', 'Alice phone', 'android', 'sg-alice-identity-key', 11,
    '{"id":1,"public":"c2ctYWxpY2U=","signature":"c2ctYWxpY2Utc2ln"}'::jsonb,
    '{"id":1,"public":"c2ctYWxpY2Uta3liZXI=","signature":"c2ctYWxpY2Uta3liZXItc2ln"}'::jsonb,
    '[{"id":1,"public":"c2ctYWxpY2UtcHJla2V5"}]'::jsonb);

  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_bob, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';
  perform public.mm_ensure_profile('Bob', 'sg_bob');
  perform public.mm_register_device(
    'sg-device-bob', 'Bob phone', 'android', 'sg-bob-identity-key', 12,
    '{"id":1,"public":"c2ctYm9i","signature":"c2ctYm9iLXNpZw=="}'::jsonb,
    '{"id":1,"public":"c2ctYm9iLWt5YmVy","signature":"c2ctYm9iLWt5Yi1zaWc="}'::jsonb,
    '[{"id":1,"public":"c2ctYm9iLXByZWtleQ=="}]'::jsonb);

  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_carol, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';
  perform public.mm_ensure_profile('Carol', 'sg_carol');
  perform public.mm_register_device(
    'sg-device-carol', 'Carol phone', 'android', 'sg-carol-identity-key', 13,
    '{"id":1,"public":"c2ctY2Fyb2w=","signature":"c2ctY2Fyb2wtc2ln"}'::jsonb,
    '{"id":1,"public":"c2ctY2Fyb2wta3liZXI=","signature":"c2ctY2Fyb2wta3liZXItc2ln"}'::jsonb,
    '[{"id":1,"public":"c2ctY2Fyb2wtcHJla2V5"}]'::jsonb);

  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_mallory, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';
  perform public.mm_ensure_profile('Mallory', 'sg_mallory');
  perform public.mm_register_device(
    'sg-device-mallory', 'Mallory phone', 'android', 'sg-mallory-identity-key', 14,
    '{"id":1,"public":"c2ctbWFsbG9yeQ==","signature":"c2ctbWFsbG9yeS1zaWc="}'::jsonb,
    '{"id":1,"public":"c2ctbWFsbG9yeS1reWJlcg==","signature":"c2ctbWFsbG9yeS1rcyE="}'::jsonb,
    '[{"id":1,"public":"c2ctbWFsbG9yeS1wcmVrZXk="}]'::jsonb);

  -- Alice creates a group with Bob only, so "alice and bob share a conversation" is true while
  -- Mallory and Carol are outsiders.
  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_alice, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';
  v_group := public.mm_create_group('Social Graph Test', array[v_bob]);
  assert v_group is not null, 'group creation failed';
  v_direct := public.mm_start_direct_conversation(v_bob);
  assert v_direct is not null, 'direct conversation must be created for the call fixture';

  -- Alice blocks Carol: a relationship between two *other* users that Mallory must not learn.
  insert into public.mm_blocks (blocker_id, blocked_id) values (v_alice, v_carol)
  on conflict do nothing;

  v_call := public.mm_create_call(v_direct, 'voice');
  assert v_call is not null, 'alice must be able to start a 1:1 call';

  -------------------------------------------------- 1. enumeration attempts are refused
  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_mallory, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';

  -- 1a. Who talks to whom? Mallory asks about Alice and Bob.
  assert public.mm_shares_conversation(v_alice, v_bob) = false,
    'S-2: an outsider must not learn that alice and bob share a conversation';
  assert public.mm_shares_conversation(v_bob, v_alice) = false,
    'S-2: an outsider must not learn that bob and alice share a conversation';
  -- …and the same question about itself still works (it is its own data).
  assert public.mm_shares_conversation(v_mallory, v_alice) = false,
    'mallory genuinely shares nothing with alice';
  assert public.mm_shares_conversation(v_mallory, v_mallory) = false,
    'mallory shares no conversation with herself in this fixture';

  -- 1b. Group membership oracle.
  assert public.mm_is_member(v_group, v_alice) = false,
    'S-2: an outsider must not learn that alice is in a group';
  assert public.mm_is_member(v_group, v_bob) = false,
    'S-2: an outsider must not learn that bob is in a group';
  assert public.mm_role(v_group, v_alice) is null,
    'S-2: an outsider must not read another member role';
  assert public.mm_is_admin(v_group, v_alice) = false,
    'S-2: an outsider must not probe admin status of a group';
  assert public.mm_can_post(v_group, v_bob) = false,
    'S-2: an outsider must not probe posting rights of a group';

  -- 1c. Block oracle: alice blocked carol. Mallory must not see it.
  assert public.mm_is_blocked(v_alice, v_carol) = false,
    'S-2: an outsider must not learn that alice blocked carol';
  assert public.mm_is_blocked(v_carol, v_alice) = false,
    'S-2: block state must not leak in either direction';

  -- 1d. Call participation oracle.
  assert public.mm_is_call_participant(v_call, v_bob) = false,
    'S-2: an outsider must not learn who is on a call';
  assert public.mm_is_call_participant(v_call, v_alice) = false,
    'S-2: call participation of another user must not be enumerable';

  -- 1e. The helpers still answer about the caller itself (RLS depends on it).
  assert public.mm_is_member(v_group, v_mallory) = false, 'mallory is not in the group';
  assert public.mm_role(v_group, v_mallory) is null, 'mallory has no role in the group';

  -- 1f. Null-safety: NULL identifiers must not raise (PostgREST would otherwise 500 on probes).
  assert public.mm_is_member(null, v_mallory) = false, 'null conversation id must be false';
  assert public.mm_shares_conversation(null, v_mallory) = false, 'null user id must be false';

  ----------------------------------------------------- 2. the private helpers are unreachable
  v_failed := false;
  begin
    execute 'select count(*) from mm_private.member_exists(' || quote_literal(v_group::text)
         || ', ' || quote_literal(v_alice::text) || ')';
  exception when insufficient_privilege then
    v_failed := true;
  end;
  assert v_failed, 'authenticated must not be able to call mm_private.member_exists directly';

  v_failed := false;
  begin
    execute 'create function mm_private.mallory_trojan() returns int language sql as $$ select 1 $$';
  exception when insufficient_privilege then
    v_failed := true;
  end;
  assert v_failed, 'authenticated must not be able to create objects in mm_private';

  ------------------------------------- 3. legitimate access paths still work for members
  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_bob, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';

  -- 3a. Bob is a member: his own membership is true, and he may inspect co-members (RLS needs it
  --     for every policy that says "conversation_id in (my conversations)").
  assert public.mm_is_member(v_group, v_bob) = true, 'bob must see his own membership';
  assert public.mm_is_member(v_group, v_alice) = true,
    'a member must still be able to check a co-member (SKDM fan-out depends on it)';
  assert public.mm_is_admin(v_group, v_alice) = true, 'bob must see that alice owns the group';
  assert public.mm_is_admin(v_group, v_bob) = false, 'bob is not an admin';
  assert public.mm_can_post(v_group, v_bob) = true, 'bob may post in the group';
  assert public.mm_shares_conversation(v_alice, v_bob) = true,
    'bob must still see that he shares a conversation with alice';

  -- 3b. Bob must still not learn about relationships he is not part of.
  assert public.mm_is_blocked(v_alice, v_carol) = false,
    'a member of an unrelated conversation must not see the alice->carol block';
  assert public.mm_shares_conversation(v_alice, v_carol) = false,
    'a member must not learn about third-party relationships';

  -- 3c. Block visibility for the two parties involved.
  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_carol, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';
  assert public.mm_is_blocked(v_carol, v_alice) = true,
    'a blocked user must still learn that it is blocked (needed to stop retrying)';
  assert public.mm_is_blocked(v_alice, v_carol) = true,
    'block state is visible to both parties';

  -- 3d. Call signalling: a participant may check the other participant, an outsider may not.
  assert public.mm_is_call_participant(v_call, v_carol) = false, 'carol is not on the call';

  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_bob, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';
  assert public.mm_is_call_participant(v_call, v_bob) = true, 'bob is on the call';
  assert public.mm_is_call_participant(v_call, v_alice) = true,
    'a participant may check the peer participant (mm_call_signals insert policy needs it)';
  assert public.mm_is_call_participant(v_call, v_carol) = false, 'carol is not on the call';

  --------------------------------------------- 4. subject rate limiter (Edge Function surface)
  execute 'reset role';
  perform set_config('request.jwt.claims', '{"role":"service_role"}'::text, true);
  execute 'set local role service_role';
  perform public.mm_consume_rate_limit_as(v_mallory::text, 'push_dispatch', 2, 3600);
  perform public.mm_consume_rate_limit_as(v_mallory::text, 'push_dispatch', 2, 3600);
  v_failed := false;
  begin
    perform public.mm_consume_rate_limit_as(v_mallory::text, 'push_dispatch', 2, 3600);
  exception when others then
    v_failed := true;
  end;
  assert v_failed, 'subject rate limiter must reject the third dispatch in the window';

  v_failed := false;
  begin
    perform public.mm_consume_rate_limit_as(v_mallory::text, 'forged_bucket', 2, 3600);
  exception when others then
    v_failed := true;
  end;
  assert v_failed, 'arbitrary rate-limit action names must be rejected';

  v_failed := false;
  begin
    perform public.mm_consume_rate_limit_as('', 'push_dispatch', 2, 3600);
  exception when others then
    v_failed := true;
  end;
  assert v_failed, 'empty rate-limit subject must be rejected';

  -- A different user is a different bucket.
  perform public.mm_consume_rate_limit_as(v_alice::text, 'push_dispatch', 2, 3600);

  select public.mm_prune_rate_limits('0 seconds') into v_count;
  assert v_count >= 3, 'pruning must remove the expired windows';

  ------------------------------------------------ 5. the client cannot impersonate the limiter
  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', v_mallory, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';
  v_failed := false;
  begin
    perform public.mm_consume_rate_limit_as('someone-else', 'push_dispatch', 2, 3600);
  exception when insufficient_privilege then
    v_failed := true;
  end;
  assert v_failed, 'authenticated must not execute the subject rate limiter';

  execute 'reset role';
  raise notice 'Social-graph authorisation suite (S-2): ALL CHECKS PASSED';
end
$test$;

rollback;
