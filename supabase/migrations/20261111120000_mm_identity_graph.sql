-- Messenger hybrid backend — migration 1: identity, devices, contacts, conversation graph.
--
-- Tables are prefixed with `mm_` on purpose: this Supabase project is shared with another
-- application whose objects already live in the `public` schema and PostgREST only exposes
-- `public` here, so a namespaced prefix is the safe way to add a second, fully isolated
-- product to the same database. Every table below has RLS enabled; nothing is reachable
-- without a valid JWT, and access is decided in the database, never in the client.

create extension if not exists pgcrypto;

-- --------------------------------------------------------------------- profiles
create table if not exists public.mm_profiles (
  id            uuid primary key references auth.users(id) on delete cascade,
  username      text,
  display_name  text not null default '',
  avatar_url    text,
  bio           text,
  locale        text not null default 'en',
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  last_seen_at  timestamptz default now(),
  deleted_at    timestamptz,
  constraint mm_profiles_username_fmt check (username is null or username ~ '^[a-z0-9_]{3,32}$'),
  constraint mm_profiles_display_len check (char_length(display_name) <= 64),
  constraint mm_profiles_bio_len check (bio is null or char_length(bio) <= 280)
);
create unique index if not exists mm_profiles_username_uidx
  on public.mm_profiles (lower(username)) where username is not null;
create index if not exists mm_profiles_display_idx on public.mm_profiles (lower(display_name));

-- ---------------------------------------------------------------------- devices
-- One row per installed app instance. End-to-end encryption keys live on the device; only
-- public halves are stored here so other devices can bootstrap a Signal session with it.
create table if not exists public.mm_devices (
  id                     uuid primary key default gen_random_uuid(),
  user_id                uuid not null references public.mm_profiles(id) on delete cascade,
  device_id              text not null,
  name                   text not null default '',
  platform               text not null default 'android',
  identity_key           text not null,
  registration_id        integer not null check (registration_id >= 0),
  signed_prekey_id       integer not null,
  signed_prekey_public   text not null,
  signed_prekey_signature text not null,
  kyber_prekey_id        integer not null,
  kyber_prekey_public    text not null,
  kyber_prekey_signature text not null,
  created_at             timestamptz not null default now(),
  last_seen_at           timestamptz not null default now(),
  revoked_at             timestamptz,
  constraint mm_devices_device_len check (char_length(device_id) between 6 and 128),
  constraint mm_devices_identity_len check (char_length(identity_key) between 8 and 256),
  unique (user_id, device_id)
);
create index if not exists mm_devices_user_idx on public.mm_devices (user_id);

-- One-time pre-keys are handed out exactly once through mm_claim_prekey_bundle().
create table if not exists public.mm_device_prekeys (
  id             bigserial primary key,
  device         uuid not null references public.mm_devices(id) on delete cascade,
  prekey_id      integer not null,
  prekey_public  text not null,
  claimed_at     timestamptz,
  created_at     timestamptz not null default now(),
  unique (device, prekey_id)
);
create index if not exists mm_device_prekeys_device_idx
  on public.mm_device_prekeys (device) where claimed_at is null;

-- ------------------------------------------------------------------ user settings
create table if not exists public.mm_user_settings (
  user_id     uuid primary key references public.mm_profiles(id) on delete cascade,
  privacy     jsonb not null default '{}'::jsonb,
  notifications jsonb not null default '{}'::jsonb,
  updated_at  timestamptz not null default now(),
  constraint mm_user_settings_privacy_obj check (jsonb_typeof(privacy) = 'object'),
  constraint mm_user_settings_notif_obj check (jsonb_typeof(notifications) = 'object')
);

-- ----------------------------------------------------------------------- contacts
create table if not exists public.mm_contacts (
  owner_id   uuid not null references public.mm_profiles(id) on delete cascade,
  contact_id uuid not null references public.mm_profiles(id) on delete cascade,
  alias      text check (alias is null or char_length(alias) <= 64),
  created_at timestamptz not null default now(),
  primary key (owner_id, contact_id),
  constraint mm_contacts_self check (owner_id <> contact_id)
);
create index if not exists mm_contacts_contact_idx on public.mm_contacts (contact_id);

create table if not exists public.mm_contact_requests (
  id          uuid primary key default gen_random_uuid(),
  from_user   uuid not null references public.mm_profiles(id) on delete cascade,
  to_user     uuid not null references public.mm_profiles(id) on delete cascade,
  message     text check (message is null or char_length(message) <= 280),
  status      text not null default 'pending' check (status in ('pending','accepted','declined','blocked')),
  created_at  timestamptz not null default now(),
  responded_at timestamptz,
  constraint mm_contact_requests_self check (from_user <> to_user)
);
create unique index if not exists mm_contact_requests_pending_uidx
  on public.mm_contact_requests (from_user, to_user) where status = 'pending';
create index if not exists mm_contact_requests_to_idx on public.mm_contact_requests (to_user, status);

create table if not exists public.mm_blocks (
  blocker_id uuid not null references public.mm_profiles(id) on delete cascade,
  blocked_id uuid not null references public.mm_profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (blocker_id, blocked_id),
  constraint mm_blocks_self check (blocker_id <> blocked_id)
);

-- ------------------------------------------------------------ abuse rate limiting
-- Counters are written only through mm_consume_rate_limit(); the table itself is not
-- reachable through PostgREST (RLS on, no policies).
create table if not exists public.mm_rate_limits (
  subject      text not null,
  action       text not null,
  window_start timestamptz not null,
  hits         integer not null default 0,
  primary key (subject, action, window_start)
);

-- ------------------------------------------------------------- conversations & graph
create table if not exists public.mm_conversations (
  id          uuid primary key default gen_random_uuid(),
  type        text not null check (type in ('direct','group','channel')),
  title       text not null default '' check (char_length(title) <= 128),
  description text check (description is null or char_length(description) <= 512),
  avatar_url  text,
  owner_id    uuid not null references public.mm_profiles(id) on delete cascade,
  visibility  text not null default 'private' check (visibility in ('private','public')),
  username    text,
  invite_code text,
  direct_key  text,
  settings    jsonb not null default '{}'::jsonb,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  last_message_at timestamptz,
  archived_at timestamptz,
  deleted_at  timestamptz,
  constraint mm_conversations_username_fmt check (username is null or username ~ '^[a-z0-9_]{4,32}$'),
  constraint mm_conversations_public_channel check (visibility = 'private' or type = 'channel')
);
create unique index if not exists mm_conversations_direct_uidx
  on public.mm_conversations (direct_key) where direct_key is not null;
create unique index if not exists mm_conversations_username_uidx
  on public.mm_conversations (lower(username)) where username is not null;
create index if not exists mm_conversations_public_idx
  on public.mm_conversations (type, visibility) where deleted_at is null;
create index if not exists mm_conversations_owner_idx on public.mm_conversations (owner_id);

create table if not exists public.mm_conversation_members (
  conversation_id uuid not null references public.mm_conversations(id) on delete cascade,
  user_id         uuid not null references public.mm_profiles(id) on delete cascade,
  role            text not null default 'member' check (role in ('owner','admin','member','subscriber')),
  joined_at       timestamptz not null default now(),
  invited_by      uuid references public.mm_profiles(id) on delete set null,
  muted_until     timestamptz,
  last_read_message_id uuid,
  notification_level text not null default 'all' check (notification_level in ('all','mentions','none')),
  primary key (conversation_id, user_id)
);
create index if not exists mm_members_user_idx on public.mm_conversation_members (user_id);

-- ------------------------------------------------------------------- helper funcs
-- SECURITY DEFINER so RLS policies can ask membership questions without recursing into the
-- very policies they are part of. Each function only answers a boolean/role for explicit ids.

create or replace function public.mm_touch_updated_at() returns trigger
language plpgsql as $$
begin
  new.updated_at := now();
  return new;
end $$;

create or replace function public.mm_is_member(p_conversation uuid, p_user uuid)
returns boolean language sql stable security definer set search_path = public, pg_temp as $$
  select exists (
    select 1 from public.mm_conversation_members m
    where m.conversation_id = p_conversation and m.user_id = p_user
  );
$$;

create or replace function public.mm_role(p_conversation uuid, p_user uuid)
returns text language sql stable security definer set search_path = public, pg_temp as $$
  select m.role from public.mm_conversation_members m
  where m.conversation_id = p_conversation and m.user_id = p_user;
$$;

create or replace function public.mm_is_admin(p_conversation uuid, p_user uuid)
returns boolean language sql stable security definer set search_path = public, pg_temp as $$
  select coalesce(public.mm_role(p_conversation, p_user) in ('owner','admin'), false);
$$;

create or replace function public.mm_can_post(p_conversation uuid, p_user uuid)
returns boolean language sql stable security definer set search_path = public, pg_temp as $$
  select exists (
    select 1
    from public.mm_conversation_members m
    join public.mm_conversations c on c.id = m.conversation_id
    where m.conversation_id = p_conversation
      and m.user_id = p_user
      and m.role in ('owner','admin','member')
      and c.deleted_at is null
      and c.archived_at is null
  );
$$;

create or replace function public.mm_shares_conversation(p_user_a uuid, p_user_b uuid)
returns boolean language sql stable security definer set search_path = public, pg_temp as $$
  select exists (
    select 1
    from public.mm_conversation_members a
    join public.mm_conversation_members b on b.conversation_id = a.conversation_id
    where a.user_id = p_user_a and b.user_id = p_user_b
  );
$$;

create or replace function public.mm_is_blocked(p_user_a uuid, p_user_b uuid)
returns boolean language sql stable security definer set search_path = public, pg_temp as $$
  select exists (
    select 1 from public.mm_blocks b
    where (b.blocker_id = p_user_a and b.blocked_id = p_user_b)
       or (b.blocker_id = p_user_b and b.blocked_id = p_user_a)
  );
$$;

create or replace function public.mm_consume_rate_limit(p_action text, p_max integer, p_window_seconds integer)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_subject text := coalesce(auth.uid()::text, 'anon');
  v_window timestamptz := to_timestamp(floor(extract(epoch from now()) / p_window_seconds) * p_window_seconds);
  v_hits integer;
begin
  insert into public.mm_rate_limits (subject, action, window_start, hits)
  values (v_subject, p_action, v_window, 1)
  on conflict (subject, action, window_start) do update set hits = public.mm_rate_limits.hits + 1
  returning hits into v_hits;
  if v_hits > p_max then
    raise exception 'rate_limited: % (max % per %s)', p_action, p_max, p_window_seconds
      using errcode = 'P0001';
  end if;
end $$;

-- ---------------------------------------------------------------- RLS: profiles
alter table public.mm_profiles enable row level security;
drop policy if exists mm_profiles_select on public.mm_profiles;
create policy mm_profiles_select on public.mm_profiles for select to authenticated
using (
  id = auth.uid()
  or public.mm_shares_conversation(id, auth.uid())
  or exists (select 1 from public.mm_contacts c where c.owner_id = auth.uid() and c.contact_id = mm_profiles.id)
  or exists (
    select 1 from public.mm_contact_requests r
    where r.status = 'pending'
      and ((r.from_user = auth.uid() and r.to_user = mm_profiles.id)
        or (r.to_user = auth.uid() and r.from_user = mm_profiles.id))
  )
);
drop policy if exists mm_profiles_insert on public.mm_profiles;
create policy mm_profiles_insert on public.mm_profiles for insert to authenticated
with check (id = auth.uid());
drop policy if exists mm_profiles_update on public.mm_profiles;
create policy mm_profiles_update on public.mm_profiles for update to authenticated
using (id = auth.uid()) with check (id = auth.uid());

-- ---------------------------------------------------------------- RLS: settings
alter table public.mm_user_settings enable row level security;
drop policy if exists mm_settings_all on public.mm_user_settings;
create policy mm_settings_all on public.mm_user_settings for all to authenticated
using (user_id = auth.uid()) with check (user_id = auth.uid());

-- ------------------------------------------------------------------ RLS: devices
alter table public.mm_devices enable row level security;
drop policy if exists mm_devices_select on public.mm_devices;
create policy mm_devices_select on public.mm_devices for select to authenticated
using (user_id = auth.uid() or public.mm_shares_conversation(user_id, auth.uid()));
drop policy if exists mm_devices_insert on public.mm_devices;
create policy mm_devices_insert on public.mm_devices for insert to authenticated
with check (user_id = auth.uid());
drop policy if exists mm_devices_update on public.mm_devices;
create policy mm_devices_update on public.mm_devices for update to authenticated
using (user_id = auth.uid()) with check (user_id = auth.uid());

-- Pre-keys are never readable through PostgREST; mm_claim_prekey_bundle() is the only path.
alter table public.mm_device_prekeys enable row level security;
drop policy if exists mm_prekeys_insert on public.mm_device_prekeys;
create policy mm_prekeys_insert on public.mm_device_prekeys for insert to authenticated
with check (exists (
  select 1 from public.mm_devices d where d.id = mm_device_prekeys.device and d.user_id = auth.uid()
));
drop policy if exists mm_prekeys_delete on public.mm_device_prekeys;
create policy mm_prekeys_delete on public.mm_device_prekeys for delete to authenticated
using (exists (
  select 1 from public.mm_devices d where d.id = mm_device_prekeys.device and d.user_id = auth.uid()
));

-- ---------------------------------------------------------------- RLS: contacts
alter table public.mm_contacts enable row level security;
drop policy if exists mm_contacts_all on public.mm_contacts;
create policy mm_contacts_all on public.mm_contacts for all to authenticated
using (owner_id = auth.uid()) with check (owner_id = auth.uid());

alter table public.mm_contact_requests enable row level security;
drop policy if exists mm_requests_select on public.mm_contact_requests;
create policy mm_requests_select on public.mm_contact_requests for select to authenticated
using (from_user = auth.uid() or to_user = auth.uid());
drop policy if exists mm_requests_insert on public.mm_contact_requests;
create policy mm_requests_insert on public.mm_contact_requests for insert to authenticated
with check (from_user = auth.uid() and status = 'pending');
drop policy if exists mm_requests_update on public.mm_contact_requests;
create policy mm_requests_update on public.mm_contact_requests for update to authenticated
using (to_user = auth.uid() or from_user = auth.uid())
with check (to_user = auth.uid() or from_user = auth.uid());
drop policy if exists mm_requests_delete on public.mm_contact_requests;
create policy mm_requests_delete on public.mm_contact_requests for delete to authenticated
using (from_user = auth.uid() or to_user = auth.uid());

alter table public.mm_blocks enable row level security;
drop policy if exists mm_blocks_select on public.mm_blocks;
create policy mm_blocks_select on public.mm_blocks for select to authenticated
using (blocker_id = auth.uid() or blocked_id = auth.uid());
drop policy if exists mm_blocks_all on public.mm_blocks;
create policy mm_blocks_all on public.mm_blocks for all to authenticated
using (blocker_id = auth.uid()) with check (blocker_id = auth.uid());

alter table public.mm_rate_limits enable row level security;

-- ------------------------------------------------------------- RLS: conversations
alter table public.mm_conversations enable row level security;
drop policy if exists mm_conversations_select on public.mm_conversations;
create policy mm_conversations_select on public.mm_conversations for select to authenticated
using (
  public.mm_is_member(id, auth.uid())
  or (type = 'channel' and visibility = 'public' and deleted_at is null)
);
drop policy if exists mm_conversations_insert on public.mm_conversations;
create policy mm_conversations_insert on public.mm_conversations for insert to authenticated
with check (owner_id = auth.uid());
drop policy if exists mm_conversations_update on public.mm_conversations;
create policy mm_conversations_update on public.mm_conversations for update to authenticated
using (public.mm_is_admin(id, auth.uid())) with check (public.mm_is_admin(id, auth.uid()));
drop policy if exists mm_conversations_delete on public.mm_conversations;
create policy mm_conversations_delete on public.mm_conversations for delete to authenticated
using (owner_id = auth.uid());

alter table public.mm_conversation_members enable row level security;
drop policy if exists mm_members_select on public.mm_conversation_members;
create policy mm_members_select on public.mm_conversation_members for select to authenticated
using (public.mm_is_member(conversation_id, auth.uid()) or user_id = auth.uid());
drop policy if exists mm_members_insert on public.mm_conversation_members;
create policy mm_members_insert on public.mm_conversation_members for insert to authenticated
with check (public.mm_is_admin(conversation_id, auth.uid()) and role <> 'owner');
drop policy if exists mm_members_update on public.mm_conversation_members;
create policy mm_members_update on public.mm_conversation_members for update to authenticated
using (user_id = auth.uid() or public.mm_is_admin(conversation_id, auth.uid()))
with check (user_id = auth.uid() or public.mm_is_admin(conversation_id, auth.uid()));
drop policy if exists mm_members_delete on public.mm_conversation_members;
create policy mm_members_delete on public.mm_conversation_members for delete to authenticated
using (
  (user_id = auth.uid() and role <> 'owner')
  or (public.mm_is_admin(conversation_id, auth.uid()) and role <> 'owner')
);

-- Column-level grants: members may only change their own notification/read state.
revoke update on public.mm_conversation_members from authenticated;
grant update (muted_until, last_read_message_id, notification_level)
  on public.mm_conversation_members to authenticated;

-- -------------------------------------------------------------- triggers & indexes
drop trigger if exists mm_profiles_touch on public.mm_profiles;
create trigger mm_profiles_touch before update on public.mm_profiles
  for each row execute function public.mm_touch_updated_at();

drop trigger if exists mm_conversations_touch on public.mm_conversations;
create trigger mm_conversations_touch before update on public.mm_conversations
  for each row execute function public.mm_touch_updated_at();

create or replace function public.mm_after_conversation_insert() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  insert into public.mm_conversation_members (conversation_id, user_id, role)
  values (new.id, new.owner_id, 'owner')
  on conflict (conversation_id, user_id) do nothing;
  return new;
end $$;

drop trigger if exists mm_conversations_owner_member on public.mm_conversations;
create trigger mm_conversations_owner_member after insert on public.mm_conversations
  for each row execute function public.mm_after_conversation_insert();
