# Supabase backend

Project: `lnhmbjqjfrnqkpsjpszb` (`Luma Messenger`, region `eu-central-1`). This project is shared
with another application: its `luma_*` tables, functions, buckets (`chat_media`, `avatars`,
`releases`) and Edge Functions (`health`, `account-delete`, `turn-credentials`, `send-push`) are
**not** Messenger's and must not be modified by Messenger work. Everything Messenger owns is
prefixed `mm_` (tables, functions, Edge Functions `mm-*`) or `mm-` (buckets).

The state below was read from the live project on 2026-11-12, not copied from earlier documents.

## Migrations

In timestamp order. Each file is idempotent or additive. The **Live** column is what
`list_migrations` returned on 2026-11-12: the last two files exist only in the repository and must
be applied by the owner (`supabase db push`, or the SQL editor), in this order.

| File | Live | Contents |
| --- | --- | --- |
| `20261111120000_mm_identity_graph.sql` | yes | profiles, devices, pre-key pool, contacts, blocks, contact requests |
| `20261111120100_mm_messaging.sql` | yes | conversations, members, messages, per-device envelopes, receipts, typing, reactions, attachments, reports |
| `20261111120200_mm_notifications_calls.sql` | yes | push tokens, notification prefs, notifications, calls, participants, call signals |
| `20261111120300_mm_api_storage_realtime.sql` | yes | SECURITY DEFINER RPCs, rate limiter, storage policies, realtime publication |
| `20261111180000_mm_phase2_media_push_senderkeys_calls_devices.sql` | yes | `mm-media` private bucket policies, `mm_sender_key_distributions`, `mm_push_log`, 5-device cap and revocation rotation, pre-key rotation, call signalling RPCs |
| `20261112035022_mm_security_hotfix_anon_rpc_prekey.sql` | yes | **Security hotfix.** Removes EXECUTE on every `mm_*` function from `PUBLIC` and `anon`; adds an authentication guard to `mm_claim_prekey_bundle` |
| `20261112070000_mm_social_graph_authz.sql` | **no** | **S-2.** Policy helpers become caller-scoped wrappers over `mm_private`; service-role rate limiter for the Edge Functions; counter pruning |
| `20261112120000_mm_write_path_hardening.sql` | **no** | **W-1 … W-19.** Least-privilege table and column grants, tightened write policies, device/session revocation, message insert guard, media quota, corrected RPCs. See `docs/AUDIT.md` A.6 |

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

### Caller-scoped policy helpers (S-2, fixed)

`mm_is_member`, `mm_role`, `mm_is_admin`, `mm_can_post`, `mm_shares_conversation`, `mm_is_blocked`
and `mm_is_call_participant` remain callable (RLS policy expressions run as the caller, so they must
be), but as of migration `20261112070000_mm_social_graph_authz.sql` they only answer questions that
involve the caller:

* their own membership/role/admin state,
* a co-member of a conversation they belong to (needed by the device and SKDM fan-out policies),
* a block where they are one of the two parties,
* call participation where they are a participant.

Anything else returns `false`/`null` rather than the truth, so the functions can no longer be used
to enumerate other people's relationships. The implementations live in **`mm_private`**, a schema
that is not in `supabase/config.toml`'s `schemas = ["public"]` list and on which no API role holds
`USAGE` or `EXECUTE`; the public wrappers reach it because they are `SECURITY DEFINER`.

### Rate limiting and internal helpers

| Function | Callable by | Purpose |
| --- | --- | --- |
| `mm_consume_rate_limit(action, max, window)` | **internal** (`service_role` only since `20261112120000`) | per-`auth.uid()` limiter, called from inside the security-definer RPCs and triggers. Clients can no longer choose an action name or a limit |
| `mm_rotate_user_groups(user)` | **internal** | re-keys every v2 group of a user; called by device registration and revocation |
| `mm_guard_client_message()` | trigger only | validates rows that an API role inserts into `mm_messages` (kinds, device, reply, attachment, author-only edits); RPC-written system rows bypass it |
| `mm_media_upload_allowed(name)` | `authenticated` | storage INSERT policy for `mm-media`: name shape, posting right, per-user quota (5 GiB / 20 000 objects) |
| `mm_consume_rate_limit_as(subject, action, max, window)` | **`service_role` only** | limiter for Edge Functions, which run with the service key and have no `auth.uid()`; actions are allow-listed |
| `mm_prune_rate_limits(older_than)` | **`service_role` only** | removes expired counter windows |

`mm_private.member_exists`, `mm_private.member_role`, `mm_private.can_post`,
`mm_private.shares_conversation`, `mm_private.is_blocked` and `mm_private.is_call_participant` are the
unrestricted implementations. They are never exposed and no API role can execute them.

### Direct table access (since `20261112120000`)

`authenticated` holds `SELECT` on the readable tables (RLS decides the rows) and only these writes;
everything else is done by RPCs. `anon` holds nothing.

| Table | Direct writes |
| --- | --- |
| `mm_messages` | INSERT of the client columns (not `sent_at`, `edited_at`, `deleted_at`); UPDATE of `edited_at`, `deleted_at` on own rows |
| `mm_message_envelopes`, `mm_sender_key_distributions`, `mm_attachments` | INSERT, DELETE — rows are immutable, so the client inserts with `resolution=ignore-duplicates` |
| `mm_message_receipts` | INSERT, UPDATE (own row, message of a conversation the caller is in) |
| `mm_typing` | INSERT, UPDATE, DELETE (own row, expiry at most 30 s ahead) |
| `mm_reactions`, `mm_blocks` | INSERT, DELETE |
| `mm_contacts` | INSERT, UPDATE `alias`, DELETE |
| `mm_contact_requests` | INSERT (sender), UPDATE `status`, `responded_at` (recipient only), DELETE |
| `mm_conversation_members` | UPDATE `muted_until`, `notification_level`, `last_read_message_id` on the caller's own row |
| `mm_profiles` | UPDATE `display_name`, `avatar_url`, `bio`, `locale` |
| `mm_notification_prefs`, `mm_user_settings` | INSERT, UPDATE (own row) |
| `mm_notifications` | UPDATE `read_at`, DELETE |
| `mm_call_signals` | DELETE (own, consumed) |
| `mm_conversations`, `mm_devices`, `mm_calls`, `mm_call_participants`, `mm_reports`, `mm_push_tokens` | none — RPC only |
| `mm_device_prekeys`, `mm_rate_limits`, `mm_push_log`, `mm_revoked_sessions` | none, not readable either (internal) |

Error names added by this migration (returned in the PostgREST `message`): `device_revoked`
(P0019), `too_many_prekeys` (P0020), `conversation_corrupt` (P0021), `message_not_found` (P0022),
`target_not_found` (P0023), `kind_not_allowed` (P0024), `attachment_not_found` (P0025),
`edit_not_allowed` (P0026), `meta_too_large` (P0027).

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

| Function | `verify_jwt` | Behaviour |
| --- | --- | --- |
| `mm-account-delete` | **true** | Gateway checks the JWT; the body re-resolves the caller with `auth.getUser()`, consumes an `account_delete` rate-limit slot (3/hour), then deletes the account with the service role. |
| `mm-turn-credentials` | **true** | Gateway checks the JWT; the body re-resolves the caller, consumes a `turn_creds` slot (30/5 min) and issues time-limited TURN credentials. |
| `mm-push-dispatch` | **true** | Gateway checks the JWT; the body re-resolves the caller, consumes a `push_dispatch` slot (90/min), verifies the caller owns the message/call and is a member, then sends zero-plaintext FCM wakeups. |

`verify_jwt` is declared in `supabase/config.toml` (`[functions.<name>] verify_jwt = true`), so it is
part of the repository and not a deploy-time flag. The in-body authorisation is kept deliberately:
a valid JWT proves *who* the caller is, not *what* they may do. `mm-push-dispatch` is invoked with a
project JWT (service role), which passes the gateway check, and still derives the actor from the
user, never from the token.

**Rate limiting history (S-4).** The first implementation of both limiter blocks wrote into
`mm_rate_limits` with columns that do not exist (`user_id`, `bucket`, `count`) and ignored the
error, so neither function was throttled at all. The counter now goes through
`mm_consume_rate_limit_as()` and a limiter failure fails the request closed
(`supabase/functions/_shared/rate_limit.ts`).

Secrets they read (names as in the source): `SUPABASE_URL`, `SUPABASE_ANON_KEY`,
`SUPABASE_SERVICE_ROLE_KEY` (provided by the platform), `FCM_SERVICE_ACCOUNT_JSON`, `TURN_URLS`,
`TURN_SHARED_SECRET`, `TURN_TTL_SECONDS`, `TURN_USERNAME`, `TURN_CREDENTIAL`, `CF_TURN_TOKEN_ID`,
`CF_TURN_API_TOKEN`, `METERED_DOMAIN`, `METERED_API_KEY`. The service-role key never leaves the
functions.

## Tests

The suites run against a **disposable** database, never the shared production project:

```bash
# local, no Docker needed (pip install pgserver)
python3 supabase/tests/local/embedded_pg.py

# or against any empty PostgreSQL (CI uses a postgres:16 service container)
DATABASE_URL=postgresql://postgres:postgres@localhost:5432/postgres ./supabase/tests/local/run.sh
```

`run.sh` applies `supabase/tests/local/00_supabase_stub.sql` (roles, `auth.uid()` from
`request.jwt.claims`, `auth.users`, `storage.*`, the `supabase_realtime` publication, and the
platform's default grant posture), then every migration in timestamp order, then every suite in
`supabase/tests/`, then re-applies the migrations to prove idempotency. It runs in CI on every push
(`.github/workflows/backend.yml`, job *Migrations + RLS suites*).

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

`supabase/tests/rls_social_graph.sql` is the second suite: fixtures for four users, negative tests
for every enumeration path (two other users' shared conversation, group membership/roles/posting
rights, block state, call participation), privilege assertions (`anon`/`authenticated` have no USAGE
on `mm_private` and no EXECUTE on its functions, `service_role` alone can use the subject limiter),
legitimate-access tests (a co-member still sees membership and the SKDM fan-out path still works),
null-safety, and an RLS-policy-coverage audit that fails if an `mm_*` table has no policy and is not
on the internal allow-list (`mm_rate_limits`, `mm_push_log`, `mm_device_prekeys`,
`mm_revoked_sessions`).

`supabase/tests/rls_write_path_hardening.sql` is the third suite (findings W-1 … W-19). Its first
half replays the statements the Android client sends through PostgREST, including the exact
`ON CONFLICT` forms of `resolution=ignore-duplicates` and `resolution=merge-duplicates`, and
requires them to succeed. Its second half is the attack matrix: direct writes to conversations,
memberships, devices, pre-keys, calls and reports; forged system messages and edits of other
people's messages; mis-addressed envelopes; forged receipts; revoked-session access; quota and path
checks on the media bucket; and privilege invariants (no `anon` privilege, no direct write on an
RPC-only table, every `SECURITY DEFINER` function pins its `search_path`).

All three suites were executed on 2026-11-12 against PostgreSQL 16 through the harness above:
migrations applied, suites passed, migrations re-applied cleanly. The S-2 suite was additionally run
against a deliberately un-hardened database (migrations up to `20261112035022`) where it fails at
"an outsider must not learn that alice and bob share a conversation" - evidence that the test
actually detects the vulnerability rather than passing vacuously.

## Client configuration

`SupabaseConfig` reads `BuildConfig.SUPABASE_URL` and `BuildConfig.SUPABASE_API_KEY`, supplied as
`-PsupabaseUrl` / `-PsupabaseKey`, then environment variables, then the checked-in defaults (URL and
**publishable (anon)** key only). The service-role key is not present in the APK, the repository or
the workflow files.
