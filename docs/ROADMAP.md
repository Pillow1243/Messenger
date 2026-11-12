# Roadmap

Ordered by what makes Messenger *usable and trustworthy* first, features second. Every item states
whether it is done, in progress, or blocked, and on what.

Status legend: **Done** (shipped and verified) · **Partial** (works, needs the stated validation) ·
**Todo** · **Blocked** (needs an external decision/resource).

## Phase 0 — Foundations (done)

| Item | Status |
| --- | --- |
| Hybrid MESH / ONLINE / QUEUE transport selection with durable outbox | Done |
| SQLCipher database, Keystore-wrapped passphrase, no cloud backup | Done |
| Official libsignal PQXDH + Double Ratchet + Sender Keys v2 | Done |
| 1:1 WebRTC voice/video, TURN credential endpoint, foreground call service | Done |
| Encrypted resumable media (AES-256-GCM streaming, 128 MB, integrity hash) | Done |
| Zero-plaintext FCM wakeups with per-category preferences | Done |
| English + Persian (RTL) resources with parity enforcement in CI | Done |

## Phase 1 — Security hardening (this milestone)

| Item | Status | Evidence |
| --- | --- | --- |
| S-1 anonymous RPC access and pre-key claim | Done | migration `20261112035022`, suite 8b |
| S-2 social-graph enumeration through policy helpers | **Done** | migration `20261112070000`, `supabase/tests/rls_social_graph.sql` |
| S-3 gateway JWT verification on all Edge Functions | **Done** | `supabase/config.toml` (`verify_jwt = true`) |
| S-4 Edge Function rate limiting silently broken (wrong columns) | **Done** | `supabase/functions/_shared/rate_limit.ts` + `mm_consume_rate_limit_as()` |
| Backend suites run in CI on every push | **Done** | `.github/workflows/backend.yml` |
| Secret scanning + licence inventory in CI | **Done** | `tools/check_no_secrets.sh`, `tools/license_inventory.py` |
| Independent cryptographic review of the integration | Blocked | requires an external auditor |

## Phase 2 — Product features

| Item | Status | Notes |
| --- | --- | --- |
| Chat archive, delete-for-me, forward, saved messages | **Done** | this milestone |
| Drafts, replies, edits, delete-for-everyone, reactions, starring | Done | pre-existing |
| Message search (chats, contacts, messages) | Done | local, because plaintext never leaves the device |
| Search filters (by sender/date/type) | Todo | needs a DAO query + filter UI |
| Pinned messages inside a chat | Todo | needs a server column + Room migration |
| Scheduled / silent messages | Todo | needs outbox scheduling + server semantics |
| Disappearing messages | Todo | needs per-conversation TTL + both sides honouring it |
| Chat folders | Todo | local grouping over conversations |
| Per-chat notification level (`all` / `mentions` / `none`) | Partial | server column exists; UI maps mute only |
| Stories | Todo | needs a storage + expiry + privacy model |
| Polls (single/multi/quiz, server-enforced votes) | Todo | needs `mm_polls` tables + RLS |
| Bots and Mini Apps | Todo | needs a sandboxed execution design; never in-process |
| Group voice/video | Blocked | needs an SFU; no provider is provisioned |
| Stickers / GIFs / animated media | Todo | media pipeline already supports the transport |

## Phase 3 — Reliability and quality

| Item | Status | Notes |
| --- | --- | --- |
| 15 JVM unit-test classes (mesh, crypto, policy, realtime, formatting) | Done | `./gradlew testDebugUnitTest` |
| Backend authorisation suites (25 tables, two suites) | Done | run in CI |
| Android instrumentation tests | Partial | emulator-backed suite in CI covers SQLCipher at rest, Keystore passphrase reuse and the table inventory; navigation/notification/migration cases remain |
| Two-device validation of media, sync, calls, mesh↔online switching | Blocked | needs the owner's two physical phones |
| Background survival under OEM power managers | Partial | WorkManager + foreground service, no OEM-specific tuning |
| Crash reporting / telemetry | **Deliberately absent** | privacy by design; failures are surfaced in the UI instead |

## Phase 4 — Distribution

| Item | Status |
| --- | --- |
| Release pipeline with real signing, checksums, provenance | Done (needs the owner's keystore) |
| Third-party notices + AGPL analysis | Done (`THIRD_PARTY_NOTICES.md`) |
| Licence decision for the repository | **Blocked — owner decision** |
| In-app "Licences" / attribution screen (SQLCipher BSD notice, AGPL offer) | Done — Settings → About → Open-source licences; asset generated from `THIRD_PARTY_NOTICES.md`, drift gated in CI |
| In-app update check | Todo |

## Deliberately not planned

* Copying Telegram's branding, art or assets. Messenger has its own identity.
* Analytics, advertising SDKs or crash-reporting SDKs that would see message metadata.
* Homemade cryptography. Every primitive comes from libsignal or the platform Keystore/AES-GCM.
