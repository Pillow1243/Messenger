-- Messenger hybrid backend — migration 3: notifications, reports and call signalling.
--
-- Call media is never seen by the server: the tables below only carry SDP/ICE signalling and
-- call state so a WebRTC client can negotiate peer-to-peer. History rows deliberately contain
-- no media and no keys.

create table if not exists public.mm_notifications (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null references public.mm_profiles(id) on delete cascade,
  kind            text not null default 'message'
                    check (kind in ('message','mention','call','contact_request','group_invite','system')),
  conversation_id uuid references public.mm_conversations(id) on delete cascade,
  message_id      uuid references public.mm_messages(id) on delete cascade,
  call_id         uuid,
  created_at      timestamptz not null default now(),
  read_at         timestamptz
);
create index if not exists mm_notifications_user_idx
  on public.mm_notifications (user_id, created_at desc) where read_at is null;

create table if not exists public.mm_reports (
  id          uuid primary key default gen_random_uuid(),
  reporter_id uuid not null references public.mm_profiles(id) on delete cascade,
  target_kind text not null check (target_kind in ('user','conversation','message')),
  target_id   uuid not null,
  reason      text not null check (char_length(reason) between 2 and 64),
  details     text check (details is null or char_length(details) <= 2000),
  status      text not null default 'open' check (status in ('open','reviewing','actioned','dismissed')),
  created_at  timestamptz not null default now()
);
create index if not exists mm_reports_status_idx on public.mm_reports (status, created_at desc);

create table if not exists public.mm_calls (
  id              uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references public.mm_conversations(id) on delete cascade,
  initiator_id    uuid not null references public.mm_profiles(id) on delete cascade,
  kind            text not null check (kind in ('voice','video')),
  state           text not null default 'ringing'
                    check (state in ('ringing','active','ended','declined','missed','failed')),
  started_at      timestamptz not null default now(),
  answered_at     timestamptz,
  ended_at        timestamptz,
  end_reason      text
);
create index if not exists mm_calls_conversation_idx on public.mm_calls (conversation_id, started_at desc);

create table if not exists public.mm_call_participants (
  call_id   uuid not null references public.mm_calls(id) on delete cascade,
  user_id   uuid not null references public.mm_profiles(id) on delete cascade,
  state     text not null default 'invited'
              check (state in ('invited','joined','left','declined')),
  joined_at timestamptz,
  left_at   timestamptz,
  primary key (call_id, user_id)
);

create table if not exists public.mm_call_signals (
  id         bigserial primary key,
  call_id    uuid not null references public.mm_calls(id) on delete cascade,
  from_user  uuid not null references public.mm_profiles(id) on delete cascade,
  to_user    uuid not null references public.mm_profiles(id) on delete cascade,
  kind       text not null check (kind in ('offer','answer','ice','renegotiate','bye')),
  payload    jsonb not null,
  created_at timestamptz not null default now()
);
create index if not exists mm_call_signals_to_idx on public.mm_call_signals (to_user, call_id);

-- ------------------------------------------------------------------- helper funcs
create or replace function public.mm_is_call_participant(p_call uuid, p_user uuid)
returns boolean language sql stable security definer set search_path = public, pg_temp as $$
  select exists (select 1 from public.mm_call_participants p where p.call_id = p_call and p.user_id = p_user);
$$;

-- ---------------------------------------------------------------- RLS: notifications
alter table public.mm_notifications enable row level security;
drop policy if exists mm_notifications_select on public.mm_notifications;
create policy mm_notifications_select on public.mm_notifications for select to authenticated
using (user_id = auth.uid());
drop policy if exists mm_notifications_update on public.mm_notifications;
create policy mm_notifications_update on public.mm_notifications for update to authenticated
using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists mm_notifications_delete on public.mm_notifications;
create policy mm_notifications_delete on public.mm_notifications for delete to authenticated
using (user_id = auth.uid());
revoke update on public.mm_notifications from authenticated;
grant update (read_at) on public.mm_notifications to authenticated;

-- -------------------------------------------------------------------- RLS: reports
alter table public.mm_reports enable row level security;
drop policy if exists mm_reports_select on public.mm_reports;
create policy mm_reports_select on public.mm_reports for select to authenticated
using (reporter_id = auth.uid());
drop policy if exists mm_reports_insert on public.mm_reports;
create policy mm_reports_insert on public.mm_reports for insert to authenticated
with check (reporter_id = auth.uid());

-- ---------------------------------------------------------------------- RLS: calls
alter table public.mm_calls enable row level security;
drop policy if exists mm_calls_select on public.mm_calls;
create policy mm_calls_select on public.mm_calls for select to authenticated
using (public.mm_is_member(conversation_id, auth.uid()));
drop policy if exists mm_calls_insert on public.mm_calls;
create policy mm_calls_insert on public.mm_calls for insert to authenticated
with check (initiator_id = auth.uid() and public.mm_can_post(conversation_id, auth.uid()));
drop policy if exists mm_calls_update on public.mm_calls;
create policy mm_calls_update on public.mm_calls for update to authenticated
using (public.mm_is_member(conversation_id, auth.uid()))
with check (public.mm_is_member(conversation_id, auth.uid()));

alter table public.mm_call_participants enable row level security;
drop policy if exists mm_call_participants_select on public.mm_call_participants;
create policy mm_call_participants_select on public.mm_call_participants for select to authenticated
using (public.mm_is_call_participant(call_id, auth.uid()));
drop policy if exists mm_call_participants_update on public.mm_call_participants;
create policy mm_call_participants_update on public.mm_call_participants for update to authenticated
using (user_id = auth.uid() or public.mm_is_call_participant(call_id, auth.uid()))
with check (user_id = auth.uid() or public.mm_is_call_participant(call_id, auth.uid()));

alter table public.mm_call_signals enable row level security;
drop policy if exists mm_call_signals_select on public.mm_call_signals;
create policy mm_call_signals_select on public.mm_call_signals for select to authenticated
using (to_user = auth.uid() or from_user = auth.uid());
drop policy if exists mm_call_signals_insert on public.mm_call_signals;
create policy mm_call_signals_insert on public.mm_call_signals for insert to authenticated
with check (from_user = auth.uid() and public.mm_is_call_participant(call_id, auth.uid())
            and public.mm_is_call_participant(call_id, to_user));
drop policy if exists mm_call_signals_delete on public.mm_call_signals;
create policy mm_call_signals_delete on public.mm_call_signals for delete to authenticated
using (to_user = auth.uid() or from_user = auth.uid());
