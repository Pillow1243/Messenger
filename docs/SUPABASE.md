# Supabase backend

Project: `lnhmbjqjfrnqkpsjpszb` (`Luma Messenger`), region as provisioned. The existing project was
inspected before anything was created; no new project was needed (and the org's free-project limit
is exhausted, so creating one is not an option).

## Migrations

Applied in order, all idempotent (`create table if not exists`, `create or replace function`):

| File | Contents |
| --- | --- |
| `20261111120000_mm_identity_graph.sql` | profiles, devices, pre-key pool, contacts, blocks, contact requests, signing keys |
| `20261111120100_mm_messaging.sql` | conversations, members, messages, per-device envelopes, receipts, typing, reactions, attachments, reports |
| `20261111120200_mm_notifications_calls.sql` | push tokens, notification prefs, calls, participants, call signals |
| `20261111120300_mm_api_storage_realtime.sql` | SECURITY DEFINER RPCs, rate limiter, storage buckets/policies, realtime publication |

Every table is prefixed `mm_` to avoid colliding with anything already in the project.

## Tables (21 in `mm_*`)

`mm_profiles`, `mm_devices`, `mm_device_prekeys`, `mm_contacts`, `mm_blocks`, `mm_contact_requests`,
`mm_conversations`, `mm_conversation_members`, `mm_messages`, `mm_message_envelopes`,
`mm_message_receipts`, `mm_typing`, `mm_reactions`, `mm_attachments`, `mm_reports`,
`mm_signing_keys`, `mm_push_tokens`, `mm_notification_prefs`, `mm_calls`, `mm_call_participants`,
`mm_call_signals`.

Key invariants:

* `mm_conversations.type in ('direct','group','channel')`; a direct conversation has exactly two
  members (enforced by `mm_after_conversation_insert` + RPC).
* `mm_messages` is unique on `(conversation_id, client_msg_id)` — the same client message can be
  retried without duplicates.
* `mm_message_envelopes` is unique on `(message_id, recipient_device_row_id)`; only the recipient
  user may select or delete a row.
* Indexes on every foreign key used for reads: `conversation_id`, `sender_id`, `recipient_user_id`,
  `created_at` where paginated.

## Security

* RLS enabled on all 21 tables, with per-operation policies. `anon` has **no** table privileges on
  `mm_*`, which is asserted in the test suite.
* Writes that need cross-row checks go through `SECURITY DEFINER` functions that re-verify the
  caller: `mm_start_direct`, `mm_create_group`, `mm_join_channel`, `mm_leave_conversation`,
  `mm_ensure_profile`, `mm_register_device`, `mm_claim_prekey`, `mm_mark_read`, `mm_add_reaction`,
  `mm_remove_reaction`, `mm_set_typing`, `mm_consume_rate_limit`, `mm_delete_me`.
* `mm_claim_prekey` hands out one bundle and deletes the one-time pre-key in the same statement, so
  a bundle cannot be used twice.
* `mm_consume_rate_limit(scope, max, window_seconds)` is called by the write-heavy RPCs; the third
  call inside the window is rejected (tested).

## Storage

Two private buckets: `mm-avatars` (public read, owner write) and `mm-attachments` (no public read;
objects are addressed `<conversation_id>/<uuid>` and access is checked with `mm_try_uuid` against
membership). No bucket is public and no object is served without a short-lived signed URL.

## Realtime

`mm_messages`, `mm_message_envelopes`, `mm_message_receipts`, `mm_typing`, `mm_reactions` are in the
`supabase_realtime` publication. Envelopes are the only channel that carries ciphertext; the row
tables carry ids and timestamps. The client treats every event as a hint and re-reads from its
cursor after a reconnect.

## Tests

`supabase/tests/rls_policies.sql` is a self-contained PL/pgSQL suite. It creates two users plus an
outsider, a direct conversation, a message and an envelope, then asserts:

* a member sees the message and its own envelope;
* an outsider sees zero messages, envelopes, conversations and profiles, cannot insert, cannot
  claim a pre-key bundle, and cannot forge a receipt;
* `anon` lacks the table privileges entirely (`insufficient_privilege`);
* a pre-key bundle is single use (the second claim returns `oneTimePreKey = null`);
* a blocked sender is rejected;
* the rate limiter rejects the third call in the window;
* `mm_try_uuid('not-a-uuid')` returns null instead of raising.

Result on the live project: **all assertions pass** (run twice; the first run surfaced a missing
grant that was fixed before the second run).

## Client configuration

`SupabaseConfig` reads `BuildConfig.SUPABASE_URL` and `BuildConfig.SUPABASE_API_KEY`, which are
supplied as `-PsupabaseUrl` / `-PsupabaseKey`, then environment variables, then the checked-in
defaults (URL + **anon** key only). Release builds that must point elsewhere set the Gradle
properties in CI secrets. The service-role key is never present in the APK, the repository or the
workflow files.
