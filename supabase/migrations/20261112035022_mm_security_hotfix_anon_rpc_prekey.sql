-- Security hotfix: close anonymous RPC access and an unauthenticated one-time pre-key claim.
--
-- Found by live audit (2026-11-12): every mm_* function in `public` was EXECUTE-able by `anon`
-- (PostgreSQL grants EXECUTE to PUBLIC by default and no earlier migration revoked it). Inside
-- mm_claim_prekey_bundle the ownership guard `v_owner <> v_uid` evaluates to NULL when auth.uid()
-- is NULL, so an anonymous caller (anyone holding the public anon key shipped in the APK) could
-- claim a one-time pre-key for any device. Confirmed with a rolled-back probe as the anon role.
--
-- This migration is additive and data-preserving: it only replaces one function body (same
-- signature, same return type) and changes function privileges. No table, row or column changes.
--
-- Rule for future mm_* migrations: every new function must be followed by the same grants, because
-- the default PUBLIC EXECUTE privilege returns on `create function`.

-- 1. Explicit authentication guard in the pre-key claim (body otherwise unchanged).
create or replace function public.mm_claim_prekey_bundle(p_device uuid)
returns jsonb
language plpgsql
security definer
set search_path to 'public', 'pg_temp'
as $function$
declare
  v_uid uuid := auth.uid();
  v_owner uuid;
  v_bundle jsonb;
  v_prekey_id integer;
  v_prekey_public text;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = 'P0002';
  end if;

  select d.user_id into v_owner from public.mm_devices d where d.id = p_device and d.revoked_at is null;
  if v_owner is null then raise exception 'device_not_found' using errcode = 'P0004'; end if;
  if v_owner <> v_uid and not public.mm_shares_conversation(v_owner, v_uid) then
    raise exception 'not_authorized' using errcode = 'P0005';
  end if;

  select pk.prekey_id, pk.prekey_public into v_prekey_id, v_prekey_public
    from public.mm_device_prekeys pk where pk.device = p_device and pk.claimed_at is null
   order by pk.id limit 1 for update skip locked;
  if v_prekey_id is not null then
    update public.mm_device_prekeys set claimed_at = now() where device = p_device and prekey_id = v_prekey_id;
  end if;

  select jsonb_build_object('device', d.id, 'user', d.user_id, 'deviceId', d.device_id,
    'identityKey', d.identity_key, 'registrationId', d.registration_id,
    'signedPreKey', jsonb_build_object('id', d.signed_prekey_id, 'public', d.signed_prekey_public, 'signature', d.signed_prekey_signature),
    'kyberPreKey', jsonb_build_object('id', d.kyber_prekey_id, 'public', d.kyber_prekey_public, 'signature', d.kyber_prekey_signature),
    'oneTimePreKey', case when v_prekey_id is null then 'null'::jsonb else jsonb_build_object('id', v_prekey_id, 'public', v_prekey_public) end
  ) into v_bundle from public.mm_devices d where d.id = p_device;
  return v_bundle;
end
$function$;

-- 2. Function privileges: nothing in mm_* is callable by anon or PUBLIC. Signed-in clients
--    (authenticated) and the Edge Functions (service_role) keep EXECUTE. Trigger functions are not
--    callable through PostgREST at all, so they are only revoked, never granted.
do $grants$
declare
  r record;
begin
  for r in
    select p.oid::regprocedure as sig, (p.prorettype = 'trigger'::regtype) as is_trigger
      from pg_proc p
      join pg_namespace n on n.oid = p.pronamespace
     where n.nspname = 'public'
       and p.proname like 'mm\_%'
  loop
    execute format('revoke all on function %s from public', r.sig);
    execute format('revoke all on function %s from anon', r.sig);
    if r.is_trigger then
      execute format('revoke all on function %s from authenticated', r.sig);
    else
      execute format('grant execute on function %s to authenticated', r.sig);
      execute format('grant execute on function %s to service_role', r.sig);
    end if;
  end loop;
end
$grants$;
