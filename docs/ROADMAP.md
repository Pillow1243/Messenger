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
| W-1 … W-19 direct writes around the RPCs | **Done in the repository; live rollout pending (owner)** | migration `20261112120000`, `supabase/tests/rls_write_path_hardening.sql`, `docs/AUDIT.md` A.6 |
| S-3 gateway JWT verification on all Edge Functions | **Done** | `supabase/config.toml` (`verify_jwt = true`) |
| S-4 Edge Function rate limiting silently broken (wrong columns) | **Done** | `supabase/functions/_shared/rate_limit.ts` + `mm_consume_rate_limit_as()` |
| Mesh protocol 2: sealed headers, authenticated receipts, group membership, bounded file receive (M-1 … M-6) | **Done** | `core/mesh/SealedPayload.kt`, `MeshProtocolInstrumentedTest` (emulator, real libsignal), `docs/SECURITY.md` 5.1 |
| P-1 … P-3 profile/device/membership visibility, Q-1 media quota | **Done in the repository; live rollout pending (owner)** | migration `20261113090000`, `supabase/tests/rls_privacy_quota_pins_timers_admin.sql` |
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
| Pinned messages inside a chat | **Backend done, client todo** | `mm_pin_message`, `mm_pinned_messages` (migration `20261113090000`, tested); the app has no pin UI and no local column yet |
| Scheduled / silent messages | Todo | needs outbox scheduling + server semantics |
| Disappearing messages | **Backend done, client todo** | server-stamped `expires_at`, RLS hides expired rows, purge job (migration `20261113090000`, tested). The app neither sets a timer nor deletes its local copy at expiry, so this is not a user-facing feature yet |
| Chat folders | Todo | local grouping over conversations |
| Group/channel administration (rename, roles, ownership, invite rotation, bans) | **Backend done, client todo** | RPCs in migration `20261113090000`, tested; no admin UI in the app |
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
| Android instrumentation tests | Partial | emulator-backed suite in CI covers SQLCipher at rest, Keystore passphrase reuse, the table inventory, the mesh protocol between several simulated devices, local search and attachment forwarding; UI, notification, online-sync and migration cases remain |
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
