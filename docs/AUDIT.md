# Repository audit — initial state

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
20. No instrumentation tests, no screenshot tests, no macrobenchmark.
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
