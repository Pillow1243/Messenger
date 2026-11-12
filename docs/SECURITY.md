# Security model

This document is the threat model and the concrete controls for both transports. It describes what
is implemented in this repository; anything not implemented is called out explicitly at the end.

## 1. Assets

| Asset | Where it lives | Protection |
| --- | --- | --- |
| Message plaintext | Device only (`messages` table, SQLCipher) | Never uploaded; encrypted per recipient device before leaving the process |
| Identity private key | Device only (`SignalStore` in SQLCipher) | libsignal `IdentityKeyPair`, never exported |
| Pre-keys / signed pre-key / Kyber pre-key | Private halves device only; public halves on the server | libsignal PQXDH |
| Ratchet session state | Device only | libsignal `SessionRecord` |
| Database passphrase | Android Keystore | 32 random bytes wrapped by a non-exportable AES-GCM key (`mesh_db_kek_v1`) |
| Auth session (access + refresh token) | `SecretStore` (`messenger_auth_v1`) | AES-GCM under a Keystore key (`<name>.kek`) |
| Pre-key bundle of a peer | Server + local cache | Public data only |
| Attachment bytes | Private `mm-media` bucket (ciphertext only) + sealed local `FileVault` | Streaming AES-256-GCM with ephemeral 256-bit key + 96-bit IV carried inside the Signal envelope; SHA-256 verified before and after decryption |
| Group Sender Keys (`v2`) | Device only (`signal_sender_keys`, SQLCipher) | `libsignal` `SenderKeyRecord`; rotated (`sender_key_epoch++`, new `distribution_id`) on member removal or device revocation |

The server never receives plaintext, plaintext-derived search indexes, contact graphs in clear or
message metadata beyond what routing requires (`sender_id`, `conversation_id`, `sent_at_ms`,
`kind`).

## 2. Cryptography

* **No hand-rolled primitives.** Signalling uses `org.signal:libsignal-android` only: X3DH/PQXDH key
  agreement (Kyber-1024 included), Double Ratchet, `NumericFingerprintGenerator` v2 for safety
  numbers (60 digits, 5200 iterations).
* **Session establishment** happens in exactly two places, both with the same audited path:
  `CryptoManager.processBundle(...)` for a mesh HELLO and `CryptoManager.processBundleJson(...)`
  for a bundle claimed over HTTPS. Both go through `SessionBuilder.process(PreKeyBundle)`.
* **One-time pre-keys are single use.** The claiming RPC deletes the row it hands out
  (`mm_claim_prekey`); the local pool is never consumed when *publishing* a bundle
  (`buildBundle(consumePreKey = false)`), so publishing cannot desynchronise the pool.
* **Identity changes are fatal for the session, not silent.** `IdentityChangedException` aborts the
  send and marks the message failed; the UI surfaces it and only an explicit
  "reset trust" action re-establishes.
* **Group messaging** uses explicit protocol versioning:
  * `encryption_version = 2` (default for new groups and one-tap upgradeable for legacy groups):
    audited `libsignal` **Signal Sender Keys** (`GroupSessionBuilder`, `GroupCipher`,
    `SenderKeyDistributionMessage`). Each sender distributes its `SenderKeyDistributionMessage`
    pairwise-encrypted via `SessionCipher` to current non-revoked member devices, then encrypts
    group messages in O(1). Removing a member or revoking any member's device atomically increments
    `sender_key_epoch` and rotates `distribution_id` on the server so removed members and revoked
    devices cannot decrypt future messages.
  * `encryption_version = 1`: legacy pairwise per-device fan-out preserved for backward
    compatibility until upgraded.
* **Online media encryption**: attachments are encrypted on-device in 64 KiB streaming chunks via
  `MediaCipher` (`AES/GCM/NoPadding`, random 256-bit key, 96-bit IV, 128-bit tag) before upload to
  private Storage (`mm-media`) using resumable TUS/POST uploads and short-lived signed URLs. The
  ephemeral key, IV, plaintext SHA-256, ciphertext SHA-256, filename, MIME type, dimensions, and
  thumbnail travel exclusively inside `OnlinePayloadCodec` within the Signal/SenderKey ciphertext.
* **Push wakeups**: `mm-push-dispatch` and `PushWakeupPolicy` enforce zero-plaintext data-only
  wakeups (`mm_wakeup`). Any payload containing forbidden plaintext/key fields is rejected on both
  server and client; notifications are rendered only after local SQLCipher + libsignal decryption.
* **Replay and duplication**: received messages are keyed by server id (`remoteId`) and by
  `clientMsgId`; an envelope that has already been ingested is deleted from the server without
  being processed again. Mesh packets carry ids and are de-duplicated by the seen-packet cache.

## 3. Android platform controls

* **Storage encryption**: SQLCipher with a passphrase that only exists wrapped by a Keystore key.
  If the Keystore key is lost (backup restore, factory reset residue), the database is quarantined
  rather than silently wiped: the corrupt file is renamed and a fresh database is created, so
  nothing is destroyed behind the user's back.
* **Backup exclusion**: `android:allowBackup="false"` and `android:fullBackupContent="false"`.
  Keystore-wrapped secrets would be useless after restore anyway; excluding the DB prevents
  shipping ciphertext off-device through Google backups.
* **No cleartext traffic** to the backend: HTTPS only, no `usesCleartextTraffic`.
* **Logs** contain ids and error classes, never bodies, tokens or keys.
* **Secrets in the APK**: only the Supabase URL and the *anon* key are build config fields. The
  service-role key exists nowhere in the repository, the CI configuration or the app.

## 4. Backend controls

* **RLS on every `mm_*` table.** Filtered policies per table, no `USING (true)` anywhere.
* **Grants**: `anon` has no privileges on `mm_*` at all (verified: assertions expect
  `insufficient_privilege`). `authenticated` holds an explicit allow-list of statements and columns
  per table (`docs/SUPABASE.md`, "Direct table access"); conversations, memberships, devices,
  pre-keys, calls, reports and push tokens are writable through RPCs only. Until migration
  `20261112120000` this line was not true: the tables carried the platform default `GRANT ALL`.
* **The RPCs cannot be bypassed.** The rules they enforce (block list, device cap, sender-key
  rotation on member removal, terminal call states, rate limits) used to be skippable by writing
  the row directly; see `AUDIT.md` A.6.
* **Device revocation ends the session.** The auth session that registered a device is recorded;
  revoking the device tombstones that session, which can then neither register a device nor read
  or write conversation data. Linking the installation again needs a fresh sign-in.
* **Messages written by clients are validated on insert**: allow-listed kinds (no forged system
  notices), server-assigned `sent_at`, the caller's own live device, replies and attachments from
  the same conversation, and edits only by the author of the target. The app re-checks edit
  authorship on receipt, because the server is not trusted with content.
* **Encrypted media uploads are bounded**: 5 GiB and 20 000 objects per account, object names of
  the form `<conversation>/<object>` only.
* **Authorization is re-checked server-side** inside `SECURITY DEFINER` functions
  (`mm_start_direct`, `mm_create_group`, `mm_join_channel`, `mm_mark_read`, `mm_add_reaction`,
  `mm_claim_prekey`, `mm_consume_rate_limit`, ...). The client never decides permissions.
* **Rate limiting** is enforced in the database (`mm_consume_rate_limit(scope, max, window)`), so a
  modified APK cannot bypass it.
* **Envelope access** is restricted to the recipient user; a sender cannot read the envelopes of
  other devices, and outsiders see zero rows (tested).
* **Blocking** is enforced by the policies (a blocked sender cannot insert a message).
* **The policy helpers are caller-scoped.** `mm_is_member` / `mm_role` / `mm_is_admin` /
  `mm_can_post` / `mm_shares_conversation` / `mm_is_blocked` / `mm_is_call_participant` are
  `SECURITY DEFINER` functions used inside RLS. They answer only about the caller or about a
  co-member of a conversation the caller belongs to; any other question returns `false`/`null`.
  Their implementations live in `mm_private`, which is outside the PostgREST exposed schema and on
  which no API role holds `USAGE`/`EXECUTE`. This removes the social-graph enumeration primitive
  described in `AUDIT.md` S-2 without weakening a single policy.
* **Server-side rate limiting is real.** Client RPCs use `mm_consume_rate_limit` (keyed on
  `auth.uid()`); the Edge Functions use `mm_consume_rate_limit_as`, which only `service_role` may
  execute and which allow-lists its actions. A limiter that cannot be reached fails the request
  closed instead of letting it through (the earlier Edge Function limiter wrote into non-existent
  columns and silently did nothing).
* **Deletion**: `mm_delete_me` removes the caller's data; deleting a message sets `deleted_at` and
  clients stop rendering it.

The full authorization matrix is executable: `supabase/tests/rls_policies.sql` creates two users,
a conversation, a message and then asserts positive and negative cases (outsider reads 0 rows,
forged receipts rejected, pre-key single use, rate limiter rejects the third call, anon has no
grants). Together with `rls_social_graph.sql` and `rls_write_path_hardening.sql` it runs against a
disposable PostgreSQL in CI on every push; it is not run against the live project.

## 5. Transport policy

`domain/TransportSelector` is the only place that decides MESH / ONLINE / BOTH / QUEUE. Its inputs
are observable at send time; the decision is pure and unit-tested. Rules:

1. signed in + network + linked conversation + peer in mesh range → `BOTH`
2. signed in + network + linked conversation → `ONLINE`
3. peer in mesh range → `MESH`
4. otherwise → `QUEUE` (kept locally, retried by the mesh relay queue and the online outbox)

The mesh never consults Supabase, and losing the network never disables a radio.

## 6. Operational notes & external credentials

* **Search is local**: the server cannot search ciphertext, and no plaintext index is uploaded.
* **Local-only metadata.** Chat archive flags, starred ("saved") messages and the local message
  search index never leave the device. Archive is deliberately device-local because the server's
  `mm_conversations.archived_at` is owner-scoped and a plain member cannot write it; mirroring the
  flag would have produced a silent server failure.
* **1:1 WebRTC voice & video calling** uses `io.getstream:stream-webrtc-android` (DTLS-SRTP) with
  authenticated signaling over `mm_calls` / `mm_call_signals` and short-lived TURN credentials from
  `mm-turn-credentials`. Group calls are intentionally disabled at both UI and database RPC layers.
  Without owner-supplied TURN secrets (`docs/OWNER_SETUP.md`), calls operate in direct P2P / STUN
  mode and surface that status in the call UI.
* **Background FCM push wakeups** require owner Firebase credentials (`FCM_SERVICE_ACCOUNT_JSON` on
  Supabase + Firebase client options at build time; see `docs/OWNER_SETUP.md`). Without them, the
  app honestly reports `UNCONFIGURED` and relies on Realtime + periodic `SyncWorker` backfill.
* **Telemetry is opt-in by absence**: nothing is collected, no analytics SDK is bundled.

## 7. Review of 2026-11-12

* **Fixed:** `anon` could execute every `mm_*` RPC, including the one-time pre-key claim, because
  PostgreSQL grants EXECUTE to `PUBLIC` by default. Migration `20261112035022` revokes that and adds
  an authentication guard to the claim.
* **Fixed (this milestone):** the signed-in social-graph enumeration (S-2, migration
  `20261112070000`, negative suite `supabase/tests/rls_social_graph.sql`), gateway JWT verification
  on the three Edge Functions (S-3, `supabase/config.toml`) and the silently broken Edge Function
  rate limiter (S-4, `_shared/rate_limit.ts` + `mm_consume_rate_limit_as`).
* **Fixed (third pass, repository only):** the direct-write path around the RPCs, findings
  W-1 … W-19 in `AUDIT.md` A.6 (migration `20261112120000`, suite
  `supabase/tests/rls_write_path_hardening.sql`), and four client defects found by replaying the
  app's requests. **Neither this migration nor `20261112070000` is applied to the live project
  yet**; that is an owner action.
* **Now enforced continuously:** all three authorisation suites, migration idempotency, Deno type-check
  of the functions, English/Persian string parity, licence inventory and a credential scan run in CI
  on every push (`.github/workflows/ci.yml`, `backend.yml`). Details in [`AUDIT.md`](AUDIT.md), Part A.
* **Not an independent audit of the cryptography.** This review was performed by the project's own engineering process.
  It is not a third-party cryptographic or security audit, and the project does not claim one.
* **Group calls** are not implemented (the sentence above about them being disabled at both layers
  is accurate for the UI and the call RPCs; there is no SFU).
