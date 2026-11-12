# Supabase backend

Project: `lnhmbjqjfrnqkpsjpszb` (`Luma Messenger`, region `eu-central-1`). This project is shared
with another application: its `luma_*` tables, functions, buckets (`chat_media`, `avatars`,
`releases`) and Edge Functions (`health`, `account-delete`, `turn-credentials`, `send-push`) are
**not** Messenger's and must not be modified by Messenger work. Everything Messenger owns is
prefixed `mm_` (tables, functions, Edge Functions `mm-*`) or `mm-` (buckets).

The state below was read from the live project on 2026-11-12, not copied from earlier documents.

## Migrations

Applied to the live project in this order (`list_migrations`). Each file is idempotent or
additive.

| File | Contents |
| --- | --- |
| `20261111120000_mm_identity_graph.sql` | profiles, devices, pre-key pool, contacts, blocks, contact requests |
| `20261111120100_mm_messaging.sql` | conversations, members, messages, per-device envelopes, receipts, typing, reactions, attachments, reports |
| `20261111120200_mm_notifications_calls.sql` | push tokens, notification prefs, notifications, calls, participants, call signals |
| `20261111120300_mm_api_storage_realtime.sql` | SECURITY DEFINER RPCs, rate limiter, storage policies, realtime publication |
| `20261111180000_mm_phase2_media_push_senderkeys_calls_devices.sql` | `mm-media` private bucket policies, `mm_sender_key_distributions`, `mm_push_log`, 5-device cap and revocation rotation, pre-key rotation, call signalling RPCs |
| `20261112035022_mm_security_hotfix_anon_rpc_prekey.sql` | **Security hotfix.** Removes EXECUTE on every `mm_*` function from `PUBLIC` and `anon`; adds an authentication guard to `mm_claim_prekey_bundle` |

Migration names in the live history use the same suffixes as the files, without the timestamp.

## Tables (25 in `mm_*`)

All 25 have RLS enabled. Live list:

`mm_profiles`, `mm_devices`, `mm_device_prekeys`, `mm_contacts`, `mm_contact_requests`, `mm_blocks`,
`mm_conversations`, `mm_conversation_members`, `mm_messages`, `mm_message_envelopes`,
`mm_message_receipts`, `mm_typing`, `mm_reactions`, `mm_attachments`, `mm_reports`,
`mm_push_tokens`, `mm_push_log`, `mm_notification_prefs`, `mm_notifications`, `mm_user_settings`,
`mm_calls`, `mm_call_participants`, `mm_call_signals`, `mm_sender_key_distributions`, `mm_rate_limits`.

There is **no** `mm_signing_keys` table. Earlier versions of this document listed one; it was never
created.

Key invariants:

* `mm_conversations.type in ('direct','group','channel')`; a direct conversation has exactly two
  members (enforced by `mm_after_conversation_insert` and the RPC).
* `mm_messages` is unique on `(conversation_id, client_msg_id)`, so a retried client message cannot
  create a duplicate.
* `mm_message_envelopes` is unique on `(message_id, recipient_device_row_id)`; only the recipient
  user may select or delete a row.
* `mm_device_prekeys` rows are single use: `mm_claim_prekey_bundle` marks the row claimed in the same
  statement that returns it.
* `mm_devices` has length checks on `device_id` and `identity_key`; a device can be revoked but its
  row is kept, so historical envelopes stay attributable.

## Functions and privileges

All `mm_*` functions are `SECURITY DEFINER` with `search_path = public, pg_temp`. Client-callable RPCs
are reachable only by `authenticated` (signed-in users) and `service_role` (Edge Functions). Trigger
functions are not callable through the API at all.

Before migration `20261112035022`, PostgreSQL's default `PUBLIC` EXECUTE grant applied, so `anon`
(the publishable key shipped in every APK) could call them. That exposed `mm_claim_prekey_bundle`
to unauthenticated use. The hotfix closed it; a rolled-back probe confirmed `permission denied` for
`anon` afterwards and that the owner's signed-in claim still works.

**Rule for new migrations:** every new `mm_*` function must be followed by
`revoke all ... from public, anon` and a `grant execute ... to authenticated` (the hotfix's `do`
block shows the pattern). Otherwise the default grant returns.

Writes that need cross-row checks go through these functions, which re-verify `auth.uid()`:
`mm_start_direct_conversation`, `mm_create_group`, `mm_create_channel`, `mm_join_channel`,
`mm_leave_conversation`, `mm_add_group_members`, `mm_remove_group_member`, `mm_ensure_profile`,
`mm_register_device`, `mm_replenish_prekeys`, `mm_rotate_signed_prekeys`, `mm_revoke_device`,
`mm_claim_prekey_bundle`, `mm_set_read`, `mm_set_call_state`, `mm_create_call`, `mm_send_call_signal`,
`mm_register_push_token`, `mm_revoke_push_token`, `mm_report`, `mm_upgrade_group_encryption`,
`mm_search_users`, `mm_search_public_channels`, `mm_delete_me`, `mm_consume_rate_limit`.

**Known gap (open):** the helper functions `mm_is_member(conversation, user)`,
`mm_shares_conversation(a, b)` and `mm_is_blocked(a, b)` are `SECURITY DEFINER` and take arbitrary
ids. Any signed-in user can call them for other people's ids, which reveals membership and blocking
relationships. They are also used inside RLS policies, so tightening them must be done with a test
suite in place. See [`AUDIT.md`](AUDIT.md) finding S-2.

## Storage

| Bucket | Public | Use |
| --- | --- | --- |
| `mm-media` | **no** | Encrypted attachments. Objects are `<conversation_id>/<uuid>`. Read and write require membership (`mm_is_member` on the path prefix). Access is via short-lived signed URLs. |
| `mm-avatars` | yes (public read) | Profile photos. Owner writes only. |

Attachment bytes are always ciphertext (client-side streaming AES-256-GCM, see
[`SECURITY.md`](SECURITY.md)). The bucket being private protects metadata and access patterns; the
encryption protects content.

## Realtime

These 13 tables are in the `supabase_realtime` publication:
`mm_contact_requests`, `mm_conversations`, `mm_conversation_members`, `mm_messages`,
`mm_message_envelopes`, `mm_message_receipts`, `mm_reactions`, `mm_typing`, `mm_notifications`,
`mm_calls`, `mm_call_participants`, `mm_call_signals`, `mm_sender_key_distributions`.

Envelopes are the only channel that carries ciphertext; the other tables carry ids, timestamps and
small state. The client treats every event as a hint and re-reads from its stored cursor after a
reconnect.

## Edge Functions

| Function | `verify_jwt` (live) | Behaviour |
| --- | --- | --- |
| `mm-account-delete` | false | Validates the caller with `auth.getUser()`, then deletes the account with the service role. |
| `mm-turn-credentials` | false | Validates the caller with `auth.getUser()`, then issues time-limited TURN credentials. |
| `mm-push-dispatch` | false | Validates the caller with `auth.getUser()`; sends zero-plaintext FCM wakeups. |

`verify_jwt` is off on the three functions while they perform their own check. Turning it on at the
gateway would add a second layer and is listed as a low-severity hardening item in `AUDIT.md`.

Secrets they read (names as in the source): `SUPABASE_URL`, `SUPABASE_ANON_KEY`,
`SUPABASE_SERVICE_ROLE_KEY` (provided by the platform), `FCM_SERVICE_ACCOUNT_JSON`, `TURN_URLS`,
`TURN_SHARED_SECRET`, `TURN_TTL_SECONDS`, `TURN_USERNAME`, `TURN_CREDENTIAL`, `CF_TURN_TOKEN_ID`,
`CF_TURN_API_TOKEN`, `METERED_DOMAIN`, `METERED_API_KEY`. The service-role key never leaves the
functions.

## Tests

`supabase/tests/rls_policies.sql` is a self-contained PL/pgSQL suite wrapped in `BEGIN … ROLLBACK`.
It creates users, a direct conversation, messages and envelopes, then asserts:

* a member sees the message and its own envelope; an outsider sees none of it;
* `anon` has no table privileges on `mm_*` (`insufficient_privilege`);
* no `mm_*` function is executable by `anon`, and a pre-key claim without a session fails (added
  with the hotfix);
* a pre-key bundle is single use;
* a blocked user cannot start a call;
* the rate limiter rejects the third call in its window;
* `mm_try_uuid('not-a-uuid')` returns null instead of raising.

Run it with the Supabase SQL editor or `psql` against a non-production branch. An earlier document
recorded a passing run; **that run was not repeated on 2026-11-12**. The hotfix assertions were
checked with separate rolled-back probes against the live project, but the full suite was not
re-run after they were added to the file, so it should be run on a branch before the next change.

## Client configuration

`SupabaseConfig` reads `BuildConfig.SUPABASE_URL` and `BuildConfig.SUPABASE_API_KEY`, supplied as
`-PsupabaseUrl` / `-PsupabaseKey`, then environment variables, then the checked-in defaults (URL and
**publishable (anon)** key only). The service-role key is not present in the APK, the repository or
the workflow files.
