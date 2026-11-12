# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions refer to
`versionName` in `app/build.gradle.kts`.

## [Unreleased] — security hardening and chat hygiene

### Compatibility

* **Mesh protocol 2 is not compatible with protocol 1.** Devices running an older build and devices
  running this one do not exchange offline messages; both sides must update. Online messaging is
  unaffected. Packets that an older build left in its store-and-forward queue are discarded.

### Security — offline mesh (protocol 2)

* **Receipts can no longer be forged (M-1).** A delivery receipt was a plain header naming a
  message id, accepted from any device in radio range. One forged frame marked a message
  "delivered" and made the sender delete the ciphertext it was holding for the real recipient. A
  receipt is now an encrypted packet: it has to decrypt under the Signal session of the device it
  claims to come from, name a packet that was queued for exactly that device, and come from a
  participant of the conversation.
* **A relay can no longer change what a message means (M-2).** The group id, group name, message
  id, timestamp and attachment metadata travelled in the clear header and were trusted. A relaying
  device could move a message into another chat, rename a group, rewrite the time or redirect a
  receipt. All of it is now inside the ciphertext (`SealedPayload`); the clear header only routes.
* **A contact can no longer write into another chat (M-2).** A direct chat's id is the peer's
  device id, and a "group" packet naming that id was stored in the direct chat. Group packets are
  now accepted only for a stored mesh group, only from one of its members, and a new group must
  list both the sender and the recipient.
* **File receive is bounded (M-3).** The transfer id was used as a directory name without
  validation (`../..` left the staging directory), chunk index, count and size were not checked,
  and the declared size was not limited. All are validated; a transfer belongs to the conversation
  and message it started in; unfinished plaintext staging is purged.
* **A different identity key no longer takes over a device's route (M-6).** A HELLO claiming a
  known device id with another identity key is refused before the link is registered.
* **`FileProvider` exposes `cache/share/` only (FP-1).** It used to cover the whole cache
  directory, which also holds decrypted temporaries.
* **Only this app's own notification can answer a call (I-1).** The launcher activity is exported;
  any app could start it with the extras that answer a ringing call. The answer action now needs a
  per-installation token; ids from intents and `offlinemesh://` links are validated.

### Fixed — offline mesh

* Packets stay queued until the recipient confirms them and are repeated on the open link; a
  repeated packet gets its receipt again. Previously a frame written to a link that dropped a
  moment later was gone, and the message stayed at "sent" forever.
* Receipts are relayed, so a message that needed a relay can reach "delivered".
* The store-and-forward queue holds 2,000 packets (was 600: a file above ~19 MB could not be
  queued and the rest of it was dropped silently). A file that does not fit now fails visibly and
  can be retried. The retry pass no longer starves behind 200 packets for an absent device.
* A retry no longer re-encrypts a text that is still queued (each pass advanced the ratchet).
* Members of a group learn the member list with the first message and can reply to everybody;
  before, only the creator could write.
* Retrying a failed mesh-only attachment re-sends it over the mesh instead of scheduling an upload
  that cannot happen.

### Fixed — search and forwarding

* **Local search works again (R-3).** The contact query declared a two-character `ESCAPE`, which
  SQLite rejects when it compares the first row: every search failed for a user with at least one
  contact. `%` and `_` in a search are now matched literally in chats, messages and contacts.
* **Forwarding an attachment forwards the attachment (R-2).** It used to send the phone's locally
  encrypted copy, which the recipient cannot open, and removed the attachment from the message
  that was forwarded.

### Build

* The CI and release workflows pass the four Firebase client options (`FIREBASE_APP_ID`,
  `FIREBASE_API_KEY`, `FIREBASE_PROJECT_ID`, `FIREBASE_SENDER_ID`) from repository secrets to
  Gradle. They never did, so no published APK could receive a push. The secrets are not set yet.

### Fixed — online

* **Edit and delete-for-everyone no longer pretend.** Both changed the local copy first and
  ignored the server's answer. A delete older than 24 hours is filtered out by row-level security
  without an error, so the message vanished on the sender's phone and stayed on everybody else's.
  The server is now asked first, zero changed rows count as failure, and the user is told.
* Reactions: tapping an emoji somebody else had already used removed nothing instead of adding
  yours (the app compared against the per-emoji count, with a prefix match). The app now asks the
  server whether *this account* reacted.
* Deleting an online group or channel requires the leave to succeed; otherwise the chat reappeared
  after the next sync. Attachments, queued packets and outbox rows are removed with the chat.
* A batch of attachments no longer stops at the first unusable file after part of it was sent.
* A push wake-up that could not be handled (no session yet, no network) is accepted again when the
  server repeats it, instead of being discarded as a duplicate for ten minutes.
* `OutboxWorker` retries while the outbox is not empty; it used to report success regardless.
  `SyncWorker` runs every step and reports a failed one.

### Backend (migration `20261113090000`, **not yet applied to the live project**)

* **Profile, device and membership visibility (P-1 … P-3).** Adding somebody to your own contact
  list, sending them a contact request or joining the same public channel no longer reveals their
  profile, their device list or their membership rows. `last_seen_at` and other members'
  notification settings are not selectable at all.
* **Media quota from counters (Q-1)**, 5 GiB and 20,000 objects per account, instead of a scan of
  the whole bucket on every upload.
* **Server-side message timers (DM-1)**, **pinned messages**, and **group/channel administration**
  (title, roles, ownership transfer, invite rotation, bans). These are backend capabilities; the
  Android client does not use them yet.
* Edge Functions fail closed on failed lookups; account deletion removes the account's media from
  storage before it deletes rows. Details in `docs/SUPABASE.md`.

### Security

* **W-1 … W-19 — the direct-write path around the RPCs is closed** (migration
  `20261112120000_mm_write_path_hardening.sql`, details and reproduction notes in
  `docs/AUDIT.md` A.6). Before this change a signed-in user with a modified client could, among
  other things: keep using a revoked device; delete another user's pre-keys and undelivered
  envelopes; add a silent third member to a direct chat; remove a group member without rotating
  the group key; forge system messages and "edit" other people's messages; write read receipts on
  messages of conversations they are not in; and upload without limit. Tables now carry explicit
  per-statement, per-column grants, the affected RPCs are corrected, and client-inserted messages
  pass a guard trigger. Regression suite: `supabase/tests/rls_write_path_hardening.sql`.
  **Not yet applied to the live project.**
* The app refuses an incoming edit that was not written by the author of the message
  (`EditPolicy`), independently of the server-side rule.
* **S-2 — social-graph enumeration closed.** `mm_is_member`, `mm_role`, `mm_is_admin`,
  `mm_can_post`, `mm_shares_conversation`, `mm_is_blocked` and `mm_is_call_participant` are
  `SECURITY DEFINER` helpers behind the RLS policies. Because PostgreSQL grants `EXECUTE` to
  `PUBLIC` by default and migration `20261112035022` only revoked it from `anon`, any signed-in
  user could call them through PostgREST with arbitrary ids and learn who talks to whom, who
  administers a group, who blocked whom, and who is on a call. The implementations moved to
  `mm_private` (no API-role access) and the public wrappers are now caller-scoped: they answer
  about the caller, or about a co-member of a conversation the caller is in, and return
  `false`/`null` for everything else. Migration `20261112070000_mm_social_graph_authz.sql`.
* **S-3 — gateway JWT verification enabled** for `mm-account-delete`, `mm-turn-credentials` and
  `mm-push-dispatch` (`supabase/config.toml`, previously deployed with `--no-verify-jwt`). The
  in-function `auth.getUser()` authorisation is retained as the second layer.
* **S-4 — Edge Function rate limiting was a no-op.** Both `mm-push-dispatch` and
  `mm-turn-credentials` wrote the counter into `mm_rate_limits` using columns that do not exist
  (`user_id`, `bucket`, `count` instead of `subject`, `action`, `window_start`, `hits`) and ignored
  the error, so neither function was throttled. They now consume an atomic slot through the new
  `mm_consume_rate_limit_as()` (service-role only, allow-listed actions) and fail closed.
  `mm-account-delete` additionally gets a 3-per-hour limit.
* Secret scanning (`tools/check_no_secrets.sh`) now runs in CI on every push and fails on committed
  private keys, keystores or elevated-role JWTs.

### Added

* **Archived chats** — archive/unarchive from the chat menu, a counter row in the chat list, and a
  dedicated screen. Archive state is local by design (the server column is owner-scoped).
* **Delete for me** — removes a message from this device only and cancels its pending upload, as
  the counterpart of delete-for-everyone.
* **Forward** — forward a message into any chat this device already holds; text is re-sent as text,
  local media from the original file, so forwarding also works offline over the mesh.
* **Saved messages** — a screen over the existing starred flag (previously settable but never
  viewable).
* `supabase/tests/local/` — a self-contained PostgreSQL harness (Supabase stub + migration runner +
  both suites + idempotency check) that runs locally with `pgserver` or in CI with a service
  container.
* `supabase/tests/rls_social_graph.sql` — negative tests for the S-2 fix, privilege assertions and
  an RLS-policy-coverage audit.
* `supabase/functions/_shared/rate_limit.ts` — one implementation of the server-side limiter.
* `.github/workflows/backend.yml` — migrations + both authorisation suites, Deno type-check of the
  Edge Functions, licence inventory, secret scan.
* `tools/check_string_parity.sh` (English/Persian keys must match), `tools/license_inventory.py`
  (every declared dependency must be listed in the notices), `THIRD_PARTY_NOTICES.md`,
  `docs/RELEASE.md`, `docs/ROADMAP.md`, this changelog.
* **In-app open-source licences screen** — Settings → About → *Open-source licences* renders the
  third-party notices that ship in the build. The asset is generated from `THIRD_PARTY_NOTICES.md`
  by `tools/build_licence_asset.py`, and CI fails if the two drift, so the attribution a user can
  read is always the inventory of the build they are running (SQLCipher's BSD notice included).
* **Android instrumentation tests in CI** — a KVM-accelerated emulator job runs the first
  device-level tests: the database file has no SQLite header and no plaintext canary, the
  Keystore-wrapped passphrase is reused across close/reopen, and the expected tables exist. The job
  drives the emulator directly (`sdkmanager` → `avdmanager` → boot wait → `connectedDebugAndroidTest`)
  and turns boot, SDK and JUnit failures into check annotations, so a green run is verifiable.
* 19 new UI strings in English and Persian.

### Fixed

* **Push notifications for messages never fired.** `mm-push-dispatch` selected a column that does
  not exist (`mm_messages.created_at`), treated the failed query as "message not found" and
  answered 403 to every message wake-up. Fixed, and guarded by a new CI check
  (`tools/check_edge_function_schema.py`) that compares the tables, columns and RPC arguments
  named in the Edge Functions with the migrated schema. Takes effect when the function is
  redeployed.
* Photos, videos, audio files and message edits were rejected by the backend: the `kind` check on
  `mm_messages` only allowed `text`, `file`, `voice`, `system`, `call` and `poll`. The constraint now lists
  the kinds the app sends.
* Retrying an interrupted send no longer fails on the second attempt: envelope, sender-key
  distribution and attachment records are written as idempotent inserts.
* The "edited" marker now reaches the server (the request also tried to change `kind`, which was
  never allowed, so the whole update was refused).
* Hanging up signals `bye`; the previous `hangup` kind was rejected by the database and the other
  side only noticed through the call-state row.
* `mm_set_call_state` had two overloads that made a call without an end reason ambiguous.

### Changed

* `supabase/config.toml` is now the source of truth for the exposed schema (`public` only, so
  `mm_private` is unreachable) and for `verify_jwt`.
* Release artefacts additionally include `THIRD_PARTY_NOTICES.md` and the resolved dependency graph.

## [3.1.0] — Phase 2: E2EE media, Sender Keys v2, 1:1 calls, push, hybrid hardening

* Streaming client-side encrypted media upload/download with resume, progress, cancel and retry.
* Audited Signal Sender Keys (`v2`) groups with epoch rotation on membership/device changes.
* 1:1 WebRTC voice and video calling with foreground service and TURN credentials.
* Zero-plaintext FCM wakeups with notification preferences and deep links.
* Multi-device management (5 devices), per-device safety numbers, device revocation.
* Security hotfix `20261112035022`: anonymous RPC access and anonymous pre-key claim closed.

## [3.0.0] — Online transport alongside the offline mesh

* Email accounts, realtime hints, cursor backfill, durable outbox, multi-device.
* Online channels, contact requests, blocks, reports.
* `HybridReconciler` for cross-transport de-duplication.
