-- Minimal Supabase-compatible stub for running the Messenger backend test suite on a plain
-- PostgreSQL instance (local sandbox, GitHub Actions service container, or `supabase db start`).
--
-- Why this exists
-- ---------------
-- The `mm_*` migrations only depend on four things from the Supabase platform:
--   * the roles `anon`, `authenticated`, `service_role`,
--   * `auth.uid()` / `auth.jwt()` / `auth.role()` reading `request.jwt.claims`,
--   * `auth.users` as the FK target for `mm_profiles`,
--   * the `storage.buckets` / `storage.objects` tables the storage RLS policies attach to,
--   * an existing `supabase_realtime` publication.
-- Everything else is plain PostgreSQL, so the whole backend (migrations + the authorisation
-- suite) can be executed and verified without touching the shared production project. The stub
-- deliberately mirrors the platform's default *privilege* posture - `anon` and `authenticated`
-- hold table privileges and are restricted only by RLS - because a suite that ran as a
-- superuser would pass while the real deployment fails.
--
-- This file is never applied to a real Supabase project: `supabase db push` only reads
-- `supabase/migrations`. It is purely a test fixture.

-- --------------------------------------------------------------------------- platform roles
do $roles$
begin
  if not exists (select 1 from pg_roles where rolname = 'anon') then
    create role anon nologin noinherit;
  end if;
  if not exists (select 1 from pg_roles where rolname = 'authenticated') then
    create role authenticated nologin noinherit;
  end if;
  if not exists (select 1 from pg_roles where rolname = 'service_role') then
    create role service_role nologin noinherit bypassrls;
  end if;
  if not exists (select 1 from pg_roles where rolname = 'authenticator') then
    create role authenticator nologin;
  end if;
end
$roles$;

grant anon, authenticated, service_role to current_user;

-- ------------------------------------------------------------------------------ auth schema
create schema if not exists auth;

create table if not exists auth.users (
  id                  uuid primary key default gen_random_uuid(),
  instance_id         uuid,
  aud                 text,
  role                text,
  email               text,
  encrypted_password  text,
  email_confirmed_at  timestamptz,
  invited_at          timestamptz,
  confirmation_token  text,
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  last_sign_in_at     timestamptz,
  raw_app_meta_data   jsonb,
  raw_user_meta_data  jsonb,
  is_super_admin      boolean,
  is_sso_user         boolean not null default false,
  is_anonymous        boolean not null default false,
  deleted_at          timestamptz
);
create unique index if not exists users_email_partial_key on auth.users (email) where is_sso_user = false;

-- goTrue exposes the claims of the current request as a JSON setting (set by PostgREST from the
-- verified JWT). The real implementations are identical in behaviour.
create or replace function auth.jwt() returns jsonb
language sql stable as $$
  select coalesce(nullif(current_setting('request.jwt.claims', true), '')::jsonb, '{}'::jsonb);
$$;

create or replace function auth.uid() returns uuid
language sql stable as $$
  select nullif(auth.jwt() ->> 'sub', '')::uuid;
$$;

create or replace function auth.role() returns text
language sql stable as $$
  select auth.jwt() ->> 'role';
$$;

create or replace function auth.email() returns text
language sql stable as $$
  select auth.jwt() ->> 'email';
$$;

grant usage on schema auth to anon, authenticated, service_role;
grant execute on function auth.jwt(), auth.uid(), auth.role(), auth.email()
  to anon, authenticated, service_role;

-- ------------------------------------------------------------------------- storage schema
create schema if not exists storage;

create table if not exists storage.buckets (
  id                 text primary key,
  name               text not null,
  owner              uuid,
  public             boolean not null default false,
  avif_autodetection boolean not null default false,
  file_size_limit    bigint,
  allowed_mime_types text[],
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now(),
  type               text not null default 'STANDARD'
);

create table if not exists storage.objects (
  id               uuid primary key default gen_random_uuid(),
  bucket_id        text references storage.buckets (id),
  name             text,
  owner            uuid,
  owner_id         text,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  last_accessed_at timestamptz default now(),
  metadata         jsonb,
  user_metadata    jsonb,
  path_tokens      text[] generated always as (string_to_array(name, '/')) stored,
  version          text,
  level            integer
);

alter table storage.objects enable row level security;

-- Platform defaults: the API roles may use the tables, RLS decides which rows.
grant usage on schema storage to anon, authenticated, service_role;
grant all on storage.objects to anon, authenticated, service_role;
grant all on storage.buckets to anon, authenticated, service_role;

-- --------------------------------------------------------------------------- realtime slot
do $realtime$
begin
  if not exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    create publication supabase_realtime;
  end if;
end
$realtime$;

-- ------------------------------------------------------------- API-role defaults for `public`
-- Supabase grants the API roles broad table/sequence/function privileges in `public` and relies
-- on RLS. Reproducing that here is what makes the negative tests meaningful: a revoked grant or
-- a missing policy must fail for `anon`/`authenticated`, not be silently bypassed.
grant usage on schema public to anon, authenticated, service_role;

alter default privileges in schema public
  grant all on tables to anon, authenticated, service_role;
alter default privileges in schema public
  grant all on sequences to anon, authenticated, service_role;
alter default privileges in schema public
  grant execute on functions to anon, authenticated, service_role;

-- ---------------------------------------------------------------- storage policies defaults
-- `storage.objects` policies are created by the migrations; nothing else to seed here.

-- ------------------------------------------------------------------------------------- report
do $report$
begin
  raise notice 'supabase stub ready: roles(anon, authenticated, service_role), auth, storage, realtime';
end
$report$;
