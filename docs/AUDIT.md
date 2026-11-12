# Repository audit

This file has two parts. **Part A** is the current state (2026-11-12) and is the one to act on.
**Part B** is the historical audit of 2026-11-11 (base `5880f98`). Its findings were written against
that older code and are kept only as a record; some have since been fixed, and the status of each
is stated in Part A where it still matters.

---

## Part A — Current findings (2026-11-12, second pass)

Method: the source tree on this branch, every migration, the three Edge Function sources, the CI
workflows, and — because the audit environment has no route to `supabase.co` — a **real PostgreSQL 16
instance** loaded with the Supabase platform stub (`supabase/tests/local/`), all migrations, and both
authorisation suites. Android compilation and unit tests were executed by GitHub Actions on this
branch, not locally (see A.5).

### A.1 Findings by severity

| ID | Severity | Status | Finding |
| --- | --- | --- | --- |
| S-1 | **High** | **Fixed** (`20261112035022`) | Every `mm_*` function was EXECUTE-able by `anon`, and `mm_claim_prekey_bundle` returned a one-time pre-key to an anonymous caller because its ownership check evaluated to NULL when `auth.uid()` was null. |
| S-2 | **High** (raised from Medium) | **Fixed** (`20261112070000`) | `mm_is_member`, `mm_role`, `mm_is_admin`, `mm_can_post`, `mm_shares_conversation`, `mm_is_blocked` and `mm_is_call_participant` accepted arbitrary identifiers and were executable by every signed-in user, so any account could enumerate who shares a conversation with whom, group roles, posting rights, block relationships and call participation through PostgREST — without touching a message. Severity raised to High: this is a social-graph disclosure primitive that affects every user, not a theoretical leak. Fix: implementations in the non-exposed `mm_private` schema + caller-scoped public wrappers, with a dedicated negative suite. The suite fails on a pre-fix database at the first enumeration assertion and passes afterwards. |
| S-3 | Low | **Fixed** (`supabase/config.toml`) | The three Edge Functions were deployed with `verify_jwt = false`; the gateway accepted anonymous requests and only the in-body `auth.getUser()` stood in the way. Now `verify_jwt = true` in the repository's function configuration, with the in-body authorisation kept as the second layer. |
| S-4 | **Medium** | **Fixed** (`_shared/rate_limit.ts`, `20261112070000`) | The rate-limit blocks in `mm-push-dispatch` and `mm-turn-credentials` wrote into `mm_rate_limits` with columns that do not exist (`user_id`, `bucket`, `count` vs `subject`, `action`, `window_start`, `hits`) and discarded the error, so **neither function was throttled** despite documenting a limit. Replaced with an atomic `mm_consume_rate_limit_as()` (service-role only, allow-listed actions) that fails closed; `mm-account-delete` gains a limit too. |
| D-1 | Medium | **Fixed** (earlier pass) | Documentation drift (README/ARCHITECTURE/SUPABASE/OWNER_SETUP vs the code). |
| D-2 | Low | **Fixed** (earlier pass) | Release signing secret names did not match `release.yml`. |
| T-1 | Medium | **Partly fixed** | 15 JVM test classes run in CI, and the first **Android instrumentation tests** now run in CI on a KVM-accelerated emulator: the SQLCipher database file is asserted to have no SQLite header and no plaintext canary, the Keystore-wrapped passphrase survives close/reopen, and the schema contains the expected tables. Still missing: navigation, notification and migration instrumentation tests. |
| T-2 | Medium | **Open** | No physical two-device validation of the current build. Requires the owner's phones; the validation pack is in [`TWO_DEVICE_VALIDATION.md`](TWO_DEVICE_VALIDATION.md). |
| T-3 | Low | **Fixed** | No automated check that the English and Persian resource files cover the same keys, and no check that every declared dependency has a licence entry. Both are now CI steps (`tools/check_string_parity.sh`, `tools/license_inventory.py`). |
| B-1 | Medium | **Resolved for this milestone** | Android compilation, unit tests, lint, debug/demo packaging and the Room schema check cannot run in the audit sandbox (no JDK, no Android SDK, no Maven/Gradle network). They run on GitHub Actions; this branch's `CI` run is the build evidence, and the backend work was verified locally against real PostgreSQL. |
| L-1 | Medium | **Analysed, owner decision open** | `libsignal-android` is AGPL-3.0 and is linked into the APK. `THIRD_PARTY_NOTICES.md` now contains the full inventory, the obligations, and the decisions the owner must take. No licence was chosen on the owner's behalf. |
| L-2 | Medium | **Open** | There is no in-app licences/attribution screen, so the SQLCipher BSD notice and the AGPL offer are only satisfied by the repository documents. Required before public distribution; tracked in `docs/ROADMAP.md`. |
| F-1 | — | Not implemented | Group calls (need an SFU), stories, polls, scheduled/silent messages, bots and Mini Apps, search filters, pinned messages, disappearing messages, chat folders. Listed honestly in `docs/ROADMAP.md`. |

### A.2 Verified as correct (no change needed)

* Release signing refuses to publish a debug-signed artefact: `release.yml` requires all four
  secrets and rejects a certificate whose subject is `CN=Android Debug`.
* The release build is R8-minified; the demo build is a release-type build, so R8 runs on every push.
* The Room schema is exported (`app/schemas`) and CI fails on schema drift.
* All 25 `mm_*` tables have RLS enabled; only `mm_rate_limits` and `mm_push_log` have no policy, and
  both are internal bookkeeping (deny-all for API roles). This is now asserted, not assumed.
* The `mm-account-delete`, `mm-turn-credentials` and `mm-push-dispatch` sources validate the caller
  before acting; the service-role key is read only from the function environment and never shipped.
* `mm-claim_prekey_bundle` is single-use and refuses anonymous callers.
* Group member removal rotates `sender_key_epoch` and `distribution_id`; device revocation rotates
  the group keys and revokes push tokens (asserted in `rls_policies.sql`).
* Call state is terminal: an ended call cannot be resurrected by a late `active` update.
* Client-side media encryption is streaming AES-256-GCM with a per-file key; the bucket is private.

### A.3 What was changed in this milestone

* `supabase/migrations/20261112070000_mm_social_graph_authz.sql` — S-2 fix plus the service-role
  rate limiter and counter pruning.
* `supabase/tests/rls_social_graph.sql` — negative/positive regression suite for S-2.
* `supabase/tests/local/*` — disposable-database harness + Supabase platform stub.
* `.github/workflows/backend.yml` — migrations, both suites, idempotency, Deno type-check, licence
  inventory, secret scan.
* `supabase/config.toml` — exposed schema list (`public` only) and `verify_jwt = true`.
* `supabase/functions/_shared/rate_limit.ts` + edits to the three functions — S-3/S-4.
* `tools/check_no_secrets.sh`, `tools/check_string_parity.sh`, `tools/license_inventory.py`.
* Chat UX: archive, delete-for-me, forward, saved messages (+16 strings, both locales).
* Documentation: `README.md`, `docs/AUDIT.md`, `docs/SECURITY.md`, `docs/SUPABASE.md`,
  `docs/RELEASE.md`, `docs/ROADMAP.md`, `docs/CHANGELOG.md`, `THIRD_PARTY_NOTICES.md`.

### A.4 Remaining work, in priority order

1. T-2: two-device validation of online media, sync, calls and mesh↔online switching.
2. L-2: in-app licences screen (SQLCipher BSD notice + AGPL offer), then L-1: the owner's licence
   decision.
3. T-1: the remaining instrumentation coverage (navigation, notifications, Room migrations);
   the storage and Keystore cases are already in CI.
4. F-1: product features, in the order given in `docs/ROADMAP.md` (search filters, pinned messages,
   scheduled/silent messages, disappearing messages, chat folders, then polls/stories).
5. Group calls once an SFU is provisioned; the 1:1 path is not a substitute.

### A.5 Limits of this audit

* The sandbox has **no JDK, no Android SDK, no Docker and no route to Maven/Google/`supabase.co`**.
  Anything Android is therefore verified by GitHub Actions on this branch, and anything about the
  live project is verified against a faithful local PostgreSQL reproduction. No query was executed
  against the live Supabase project in this milestone; the production database is untouched.
* The new migration has **not been pushed to the live project**. It is additive and data-preserving,
  and it must be applied with `supabase db push` (or the SQL editor) by the owner — see
  `docs/SUPABASE.md`.
* The Edge Functions have not been redeployed; until they are, the live deployment keeps the old
  rate-limit behaviour and `verify_jwt = false`. `docs/OWNER_SETUP.md` lists the deploy command.
* Security review is internal. No third-party cryptographic or application-security audit exists.

## Part B — Historical audit (2026-11-11, base `5880f98`)


Date: 2026-11-11 · Base commit: `5880f98` (`main`) · Branch: `arena/939f33dc-messenger`

This is the state the mission started from, established by reading the source, the Gradle
configuration, the CI workflow, the GitHub state (branches, PRs, runs, releases) and the
Supabase project, not by trusting the README.

## 1. Repository facts

| Item | Finding |
| --- | --- |
| `main` HEAD | `5880f98` "Merge pull request #2 from Pillow1243/arena/9699413f-messenger" |
| Branches | `main`, two merged `arena/*` branches |
| Tags / releases | none; a single `demo-latest` **prerelease** is force-updated by CI |
| PRs | #1 and #2, both merged |
| CI | one workflow, `Android CI`, last run green in 5m52s |
| Tests | 6 JVM test classes, all pure-logic (packet codec, routing, chunking, pairing, formatting) |
| Working tree | clean at start; all existing work preserved |
| Licensed code | `org.signal:libsignal-android` (AGPL-3.0), SQLCipher (BSD-style), Nearby (Google Play Services) |

Product state: a complete, honest **offline mesh** prototype — 1:1 and group chat over Nearby
Connections, Signal Protocol per peer device, SQLCipher at rest, store-and-forward relay,
QR pairing, EN + FA UI. No online mode, no accounts, no server.

## 2. Findings, by severity

### P0 — correctness / data integrity

1. **Destructive migrations.** `DatabaseFactory` builds Room with
   `fallbackToDestructiveMigration()` while `exportSchema = false`. Any schema change silently
   wipes every message, contact and key on upgrade. There is no migration and no test.
2. **Database loss is a normal code path.** If the KeyStore-wrapped passphrase cannot be
   unwrapped, the code *deletes the encrypted database* and starts over. A transient Keystore
   error (key invalidation, backup restore) destroys the user's history without consent.
3. **No delivery guarantee across process death for outgoing files.** `dispatchFile` streams
   chunks from a plaintext cache file and deletes it in `finally`; if the process dies mid-loop
   the sealed attachment remains but the chunks that were never queued are gone, and the message
   is marked `SENT`/`QUEUED` without any resume logic. A partial transfer can never complete.
4. **Receiver-side transfers are not idempotent per chunk index.** `onChunk` counts a chunk as
   new when the file is absent, but duplicate chunks after a partial assemble, and chunk index
   gaps, are not validated — a truncated file can be "assembled" and sealed as complete.
   `FileChunker.assemble` only checks existence of `chunk_N.bin`, not its length or the total
   size, and no hash is verified.
5. **`retryPending()` can rewrite message state.** It re-dispatches every `QUEUED` outgoing text
   message with `replaceQueued = true`, which deletes relay rows for the new packet ids only —
   previously handed-off ciphertexts stay queued and are re-sent under *new* packet ids,
   duplicating delivery work; on the receiving side the `msgId` guard saves correctness, but the
   relay table grows and the mesh floods.

### P1 — security

6. **Release artefacts are signed with the public Android debug key**, and CI republishes
   `demo-latest` from *any* branch push. Anyone can forge an update for the published package.
7. **CI installs `android-34` with `|| true`**, so a broken SDK step is silently ignored, and the
   workflow has `contents: write` for all events (including `pull_request`).
8. **Lint is disabled** (`abortOnError = false`, `checkReleaseBuilds = false`), so static analysis
   cannot fail the build.
9. **Rate limiting / abuse controls do not exist** — irrelevant for a serverless app, but a hard
   blocker for the online mode that was requested.
10. **No database backup exclusion for key material review:** `allowBackup=false` and
    `dataExtractionRules` are correct; noted as verified, not a finding.
11. **Identity change handling is correct but invisible in delivery:** a peer whose identity key
    changed is flagged in the contact row, yet queued ciphertext keeps retrying forever instead of
    surfacing an actionable error.
12. **Relay queue is unbounded in age** (bounded at 600 rows) and `onRelay` accepts packets from
    any peer without verifying that the packet's `from` shares a conversation with the target —
    a malicious neighbour can use the device as free storage for arbitrary ciphertext.

### P2 — architecture / maintainability

13. **Single `:app` module, 34 files**, with `ChatRepository` (616 lines) mixing encryption,
    transport framing, persistence, file transfer and relay policy.
14. **Global mutable singletons** (`DatabaseFactory`, `KeystoreHelper`) and a `lateinit` service
    locator on `Application`; no interfaces between layers, so nothing is unit-testable.
15. **`AppViewModel` is a 242-line god object** doing settings, chats, contacts, pairing, files.
16. **Raw strings for message status/type** (`"QUEUED"`, `"TEXT"`) instead of sealed types.
17. **`SeenPacketCache` in memory only** for the process; the Room `seen_packets` table exists but
    is never used, so a restart forgets duplicates and can re-deliver.
18. **No `LocalesConfig` coverage for new strings** (must add every string to both locales).

### P3 — polish

19. Persian translations cover the existing UI, but the app hard-codes some English in
    `Format.kt`-adjacent strings (verified: locale-aware formatting is fine).
20. Storage and Keystore now have instrumented tests; screenshot tests, macrobenchmark and
    navigation/notification instrumentation tests do not exist yet.
21. `README` documents limitations honestly; several are now addressed.

## 3. What was missing entirely (requested by the mission)

* Accounts, authentication, sessions, device registration, account deletion.
* Any server, any online transport, any sync engine, presence, typing, receipts.
* Channels/communities, discovery, moderation, reporting.
* Database migrations and migration tests.
* Meaningful lint/static analysis, production signing, release channels.
* Tests beyond pure functions (no crypto workflow, no repository, no policy tests).

## 4. Decisions taken because of these findings

1. **Keep one Gradle module, enforce layers by package** (`core/*`, `network/*`, `domain/*`,
   `data/*`, `presentation/*`) with explicit construction in a single composition root, and
   document the split into modules as future work. Rationale: a 15-module build cannot be
   compiled in this environment (no local JDK/Android SDK/Maven access), so introducing it would
   trade real risk for cosmetic structure. Detail: `docs/ARCHITECTURE.md`.
2. **Reuse the existing Supabase project** instead of creating a new one: creating a third free
   project is blocked by the organisation's plan limit. The project already hosts another
   application's tables, so every Messenger object is namespaced with `mm_` and fully isolated
   by its own RLS. Detail: `docs/SUPABASE.md`.
3. **Server stores ciphertext only.** Messages are split into a metadata row
   (`mm_messages`) and one Signal ciphertext per recipient device (`mm_message_envelopes`).
   There is no server-side plaintext search, so search stays local.
4. **Preserve the mesh as a first-class transport.** Every online feature is additive; the app
   still works with the radio off and no account, and the mesh path keeps its own store-and-forward.
5. **Never ship a fake feature.** Call *signalling* (rings, state, SDP/ICE relay) is implemented,
   but the WebRTC media stack is not bundled — documented as a blocker, not faked.
