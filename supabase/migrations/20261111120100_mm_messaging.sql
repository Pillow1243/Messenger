-- Messenger hybrid backend — migration 2: messages, ciphertext envelopes, receipts,
-- reactions, typing indicators and attachment metadata.
--
-- Design rule: the server never stores message plaintext. A message row carries only routing
-- and ordering metadata; the readable content lives in mm_message_envelopes, one Signal
-- ciphertext per recipient device (the sender device keeps its own copy locally). This keeps
-- the backend a transport for ciphertext, exactly as the offline mesh is.

create table if not exists public.mm_attachments (
  id              uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references public.mm_conversations(id) on delete cascade,
  uploader_id     uuid not null references public.mm_profiles(id) on delete cascade,
  bucket          text not null default 'mm-media',
  object_path     text not null,
  file_name       text not null check (char_length(file_name) <= 200),
  mime_type       text not null default 'application/octet-stream',
  size_bytes      bigint not null default 0 check (size_bytes >= 0 and size_bytes <= 268435456),
  sha256          text check (sha256 is null or char_length(sha256) = 64),
  encryption      jsonb not null default '{}'::jsonb,
  created_at      timestamptz not null default now(),
  unique (bucket, object_path)
);
create index if not exists mm_attachments_conversation_idx on public.mm_attachments (conversation_id);

create table if not exists public.mm_messages (
  id               uuid primary key default gen_random_uuid(),
  conversation_id  uuid not null references public.mm_conversations(id) on delete cascade,
  sender_id        uuid not null references public.mm_profiles(id) on delete cascade,
  sender_device_id text not null,
  client_msg_id    text not null,
  kind             text not null default 'text'
                     check (kind in ('text','file','voice','system','call','poll')),
  reply_to         uuid references public.mm_messages(id) on delete set null,
  attachment_id    uuid references public.mm_attachments(id) on delete set null,
  sent_at          timestamptz not null default now(),
  sent_at_ms       bigint not null default (extract(epoch from now()) * 1000)::bigint,
  edited_at        timestamptz,
  deleted_at       timestamptz,
  meta             jsonb not null default '{}'::jsonb,
  constraint mm_messages_client_len check (char_length(client_msg_id) between 6 and 128),
  constraint mm_messages_meta_obj check (jsonb_typeof(meta) = 'object'),
  unique (conversation_id, client_msg_id)
);
create index if not exists mm_messages_conversation_idx
  on public.mm_messages (conversation_id, sent_at desc);
create index if not exists mm_messages_sender_idx on public.mm_messages (sender_id);

create table if not exists public.mm_message_envelopes (
  id                 bigserial primary key,
  message_id         uuid not null references public.mm_messages(id) on delete cascade,
  recipient_user_id  uuid not null references public.mm_profiles(id) on delete cascade,
  recipient_device   uuid not null references public.mm_devices(id) on delete cascade,
  sender_device      uuid references public.mm_devices(id) on delete set null,
  cipher_type        smallint not null check (cipher_type between 0 and 15),
  ciphertext         text not null check (char_length(ciphertext) between 8 and 1048576),
  created_at         timestamptz not null default now(),
  unique (message_id, recipient_device)
);
create index if not exists mm_envelopes_recipient_idx
  on public.mm_message_envelopes (recipient_user_id, created_at desc);
create index if not exists mm_envelopes_message_idx on public.mm_message_envelopes (message_id);

create table if not exists public.mm_message_receipts (
  message_id   uuid not null references public.mm_messages(id) on delete cascade,
  user_id      uuid not null references public.mm_profiles(id) on delete cascade,
  device_id    text null,
  delivered_at timestamptz,
  read_at      timestamptz,
  primary key (message_id, user_id)
);
create index if not exists mm_receipts_user_idx on public.mm_message_receipts (user_id);

create table if not exists public.mm_reactions (
  message_id uuid not null references public.mm_messages(id) on delete cascade,
  user_id    uuid not null references public.mm_profiles(id) on delete cascade,
  emoji      text not null check (char_length(emoji) between 1 and 16),
  created_at timestamptz not null default now(),
  primary key (message_id, user_id, emoji)
);

create table if not exists public.mm_typing (
  conversation_id uuid not null references public.mm_conversations(id) on delete cascade,
  user_id         uuid not null references public.mm_profiles(id) on delete cascade,
  expires_at      timestamptz not null default (now() + interval '10 seconds'),
  primary key (conversation_id, user_id)
);

-- ------------------------------------------------------------------- helper funcs
create or replace function public.mm_can_send_to(p_conversation uuid, p_user uuid)
returns boolean language sql stable security definer set search_path = public, pg_temp as $$
  select public.mm_can_post(p_conversation, p_user) and not exists (
    select 1
    from public.mm_conversations c
    join public.mm_conversation_members m on m.conversation_id = c.id
    where c.id = p_conversation and c.type = 'direct' and m.user_id <> p_user
      and public.mm_is_blocked(m.user_id, p_user)
  );
$$;

create or replace function public.mm_before_message_insert() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  perform public.mm_consume_rate_limit('message', 120, 60);
  update public.mm_conversations
     set last_message_at = new.sent_at
   where id = new.conversation_id
     and (last_message_at is null or last_message_at < new.sent_at);
  return new;
end $$;

drop trigger if exists mm_messages_rate_limit on public.mm_messages;
create trigger mm_messages_rate_limit before insert on public.mm_messages
  for each row execute function public.mm_before_message_insert();

drop trigger if exists mm_profiles_touch_msg on public.mm_messages;

-- ---------------------------------------------------------------- RLS: attachments
alter table public.mm_attachments enable row level security;
drop policy if exists mm_attachments_select on public.mm_attachments;
create policy mm_attachments_select on public.mm_attachments for select to authenticated
using (public.mm_is_member(conversation_id, auth.uid()));
drop policy if exists mm_attachments_insert on public.mm_attachments;
create policy mm_attachments_insert on public.mm_attachments for insert to authenticated
with check (uploader_id = auth.uid() and public.mm_can_post(conversation_id, auth.uid()));
drop policy if exists mm_attachments_delete on public.mm_attachments;
create policy mm_attachments_delete on public.mm_attachments for delete to authenticated
using (uploader_id = auth.uid() or public.mm_is_admin(conversation_id, auth.uid()));

-- ------------------------------------------------------------------- RLS: messages
alter table public.mm_messages enable row level security;
drop policy if exists mm_messages_select on public.mm_messages;
create policy mm_messages_select on public.mm_messages for select to authenticated
using (public.mm_is_member(conversation_id, auth.uid()) and (deleted_at is null or sender_id = auth.uid()));
drop policy if exists mm_messages_insert on public.mm_messages;
create policy mm_messages_insert on public.mm_messages for insert to authenticated
with check (sender_id = auth.uid() and public.mm_can_send_to(conversation_id, auth.uid()));
drop policy if exists mm_messages_update on public.mm_messages;
create policy mm_messages_update on public.mm_messages for update to authenticated
using (sender_id = auth.uid() and sent_at > now() - interval '24 hours')
with check (sender_id = auth.uid());

revoke update on public.mm_messages from authenticated;
grant update (edited_at, deleted_at) on public.mm_messages to authenticated;

-- ------------------------------------------------------------------ RLS: envelopes
alter table public.mm_message_envelopes enable row level security;
drop policy if exists mm_envelopes_select on public.mm_message_envelopes;
create policy mm_envelopes_select on public.mm_message_envelopes for select to authenticated
using (
  recipient_user_id = auth.uid()
  or exists (select 1 from public.mm_devices d where d.id = mm_message_envelopes.sender_device and d.user_id = auth.uid())
);
drop policy if exists mm_envelopes_insert on public.mm_message_envelopes;
create policy mm_envelopes_insert on public.mm_message_envelopes for insert to authenticated
with check (exists (
  select 1 from public.mm_messages msg
  join public.mm_conversation_members m on m.conversation_id = msg.conversation_id
  where msg.id = mm_message_envelopes.message_id
    and msg.sender_id = auth.uid()
    and m.user_id = mm_message_envelopes.recipient_user_id
));
drop policy if exists mm_envelopes_delete on public.mm_message_envelopes;
create policy mm_envelopes_delete on public.mm_message_envelopes for delete to authenticated
using (
  recipient_user_id = auth.uid()
  or exists (select 1 from public.mm_devices d where d.id = mm_message_envelopes.sender_device and d.user_id = auth.uid())
);

-- ------------------------------------------------------------------- RLS: receipts
alter table public.mm_message_receipts enable row level security;
drop policy if exists mm_receipts_select on public.mm_message_receipts;
create policy mm_receipts_select on public.mm_message_receipts for select to authenticated
using (
  user_id = auth.uid()
  or exists (select 1 from public.mm_messages m where m.id = message_id and m.sender_id = auth.uid())
);
drop policy if exists mm_receipts_insert on public.mm_message_receipts;
create policy mm_receipts_insert on public.mm_message_receipts for insert to authenticated
with check (user_id = auth.uid() and exists (
  select 1 from public.mm_messages m where m.id = message_id and public.mm_is_member(m.conversation_id, auth.uid())));
drop policy if exists mm_receipts_update on public.mm_message_receipts;
create policy mm_receipts_update on public.mm_message_receipts for update to authenticated
using (user_id = auth.uid()) with check (user_id = auth.uid());

-- ------------------------------------------------------------------ RLS: reactions
alter table public.mm_reactions enable row level security;
drop policy if exists mm_reactions_select on public.mm_reactions;
create policy mm_reactions_select on public.mm_reactions for select to authenticated
using (exists (
  select 1 from public.mm_messages m
  where m.id = message_id and (m.deleted_at is null or m.sender_id = auth.uid())
    and public.mm_is_member(m.conversation_id, auth.uid())));
drop policy if exists mm_reactions_all on public.mm_reactions;
create policy mm_reactions_all on public.mm_reactions for all to authenticated
using (user_id = auth.uid() and exists (
  select 1 from public.mm_messages m where m.id = message_id and public.mm_is_member(m.conversation_id, auth.uid())))
with check (user_id = auth.uid() and exists (
  select 1 from public.mm_messages m where m.id = message_id and public.mm_is_member(m.conversation_id, auth.uid())));

-- --------------------------------------------------------------------- RLS: typing
alter table public.mm_typing enable row level security;
drop policy if exists mm_typing_select on public.mm_typing;
create policy mm_typing_select on public.mm_typing for select to authenticated
using (public.mm_is_member(conversation_id, auth.uid()));
drop policy if exists mm_typing_all on public.mm_typing;
create policy mm_typing_all on public.mm_typing for all to authenticated
using (user_id = auth.uid() and public.mm_is_member(conversation_id, auth.uid()))
with check (user_id = auth.uid() and public.mm_is_member(conversation_id, auth.uid()));
