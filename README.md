# Offline Mesh Messenger

A hybrid messenger: **the mesh works with no internet, no server and no account**, and signing in
adds an online transport for people who are not within radio range. Both transports share one
encrypted database, one set of libsignal sessions and one chat list, so a conversation does not care
which path delivered a message.

* **Package:** `com.offlinemesh.messenger`
* **Min Android:** 8.0 (API 26) · **Target/compile:** API 34
* **Languages:** English + فارسی (full RTL)
* **Offline first:** BLE / Wi‑Fi Direct (Nearby Connections), QR pairing, store‑and‑forward relay
* **Online:** email accounts, realtime delivery, per‑device encryption, multi‑device, channels

`INTERNET` is requested **only** for the optional online transport. Nothing in the mesh path uses
it, and the app works to its full offline extent with no account and no network.

---

## What works today

### Mesh (protocol 2 — not compatible with builds before 2026-11-12)

* **Pairing** — QR code carrying device id, identity key and a one‑time pre‑key bundle, so the first
  message is a real X3DH/PQXDH session.
* **Discovery** — Nearby Connections on `P2P_CLUSTER` with a 15 s watchdog that re‑arms advertising,
  reconnects dropped endpoints and survives Wi‑Fi Direct teardown.
* **Store‑and‑forward** — packets wait in the encrypted DB for up to 24 hours, are relayed by any
  device holding them, and are repeated until the recipient confirms them with an encrypted receipt;
  duplicates are suppressed by a seen‑packet cache.
* **Authenticated packets** — everything the receiver acts on (chat, group, time, attachment
  metadata, receipts) is inside the Signal ciphertext; the clear header only routes. What this does
  and does not protect is in [`docs/SECURITY.md`](docs/SECURITY.md) §5.1.
* **Files and voice notes** — chunked, encrypted per frame, integrity hash on completion.
* **Diagnostics** — peer status, relay statistics and a topology view.

### Online (E2EE hybrid transport)

* **Accounts & lifecycle** — email sign‑up / sign‑in, Keystore‑wrapped refresh sessions, full
  server + Auth account deletion (`mm-account-delete` + `mm_delete_me`).
* **Multi‑device (up to 5 active devices)** — per‑installation identity & PQXDH pre‑keys, **This
  device** indicator, automatic signed/Kyber pre‑key rotation, remote device revocation, and
  per‑device 60‑digit safety numbers.
* **Streaming E2EE media & files** — client‑side 64 KiB streaming AES‑256‑GCM encryption before
  upload to private `mm-media` storage (resumable TUS/POST up to 128 MB, expiring signed URLs,
  multi‑attachment picker, inline encrypted thumbnails, audio/voice player, progress/cancel/retry,
  and SHA‑256 + AEAD integrity verification).
* **Audited Signal Sender Keys (`v2`) groups** — backed by `org.signal:libsignal-android:0.86.5`
  (`GroupSessionBuilder`, `GroupCipher`, `SenderKeyDistributionMessage`), preserving `v1` pairwise
  compatibility with one‑tap `v2` upgrade and automatic epoch/key rotation on member removal or
  device revocation.
* **1:1 WebRTC voice & video calling** — DTLS‑SRTP audio/video (`stream-webrtc-android`), incoming
  & outgoing call UI, mute/speaker/camera/flip controls, foreground call service, and env‑driven
  TURN credentials (`mm-turn-credentials`).
* **Zero‑plaintext push wakeups** — FCM data‑only `mm_wakeup` (`mm-push-dispatch` +
  `MessengerFirebaseMessagingService`) with local decryption, per‑category notification preferences,
  per‑chat mute, and deep links (see [`docs/OWNER_SETUP.md`](docs/OWNER_SETUP.md)).
* **Realtime + offline outbox** — WebSocket hints + cursor backfill + `WorkManager` background sync
  and durable cross‑transport reconciliation (`HybridReconciler`).
* **Chat hygiene** — drafts, replies, edits, delete‑for‑everyone, delete‑for‑me, forwarding to any
  chat the device already holds (works offline too), starring with a *Saved messages* screen,
  archived chats, per‑chat mute, pinned chats, reactions and device‑side search.

---

## How the two transports combine

`domain/TransportSelector` is a pure function of four observable facts (signed in, network, whether
this conversation exists on the server, whether the peer is in mesh range):

| Situation | Transport |
| --- | --- |
| signed in + network + linked + peer nearby | **BOTH** (first ciphertext wins, duplicates suppressed) |
| signed in + network + linked | **ONLINE** |
| peer nearby | **MESH** |
| neither | **QUEUE** (local queue, retried by the relay queue and the online outbox) |

A message row is always written *before* any encryption or upload, so a crash mid‑send leaves a
queued message, not a lost one. An online failure never prevents the mesh attempt in the same send.

Details: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) ·
[`docs/SECURITY.md`](docs/SECURITY.md) · [`docs/SUPABASE.md`](docs/SUPABASE.md) ·
[`docs/OWNER_SETUP.md`](docs/OWNER_SETUP.md) ·
[`docs/TWO_DEVICE_VALIDATION.md`](docs/TWO_DEVICE_VALIDATION.md).

---

## Build

Requirements: JDK 17, Android SDK 34 + build‑tools 34.

```bash
git clone https://github.com/Pillow1243/Messenger.git
cd Messenger
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # unit tests (mesh, crypto, transport policy, realtime frames)
./gradlew lintDebug              # lint is failing the build on errors, by design
```

Point the online transport at your own project with Gradle properties (never commit them):

```bash
./gradlew assembleRelease -PsupabaseUrl=https://<ref>.supabase.co -PsupabaseKey=<anon-key>
```

Defaults in `app/build.gradle.kts` are the project's URL and **anon** key — public by design. The
service‑role key is never used by the app.

### Signing

Release builds are signed only from secrets; there is no fallback to the debug key.

```bash
keytool -genkeypair -v -keystore release.keystore -alias offlinemesh \
        -keyalg RSA -keysize 4096 -validity 10000
```

Provide `MESSENGER_KEYSTORE_BASE64`, `MESSENGER_KEYSTORE_PASSWORD`, `MESSENGER_KEY_ALIAS` and `MESSENGER_KEY_PASSWORD` as repository secrets for the release
workflow. `-PdemoSign=true` produces a debug‑signed **demo** variant (`.demo` application id) that is
what the CI publishes to the `demo-latest` prerelease; it is not an upgrade path for a real install.

### CI

* `ci.yml` — every push and PR: unit tests → lint → assemble debug/demo/release → Room schema drift
  check → artifact upload. All actions are pinned to commit SHAs.
* `release.yml` — `v*` tags only: signed APK, checksums, build metadata, release notes; concurrency
  is serialised per tag and signing is skipped (not faked) when secrets are absent.

## Permissions

| Permission | Why |
| --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Optional online transport only |
| `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` (API 31+) / `BLUETOOTH`, `BLUETOOTH_ADMIN` (≤30) | Nearby discovery and connections |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` (≤30) | Required by Nearby on older Android |
| `NEARBY_WIFI_DEVICES` | Wi‑Fi Direct transport on API 33+ |
| `CAMERA` | QR pairing codes |
| `RECORD_AUDIO` | Voice notes |
| `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Keeping the mesh alive in the background |
| `WAKE_LOCK` | Radio duty‑cycle under battery saver |

## Privacy

* Message bodies are encrypted **per recipient device** before they leave the process; the server
  stores ciphertext only and cannot read, index or search it.
* Search runs on the device because the plaintext never leaves it.
* No analytics, no crash reporting, no advertising SDK, no telemetry.
* `allowBackup=false`; the database is excluded from cloud backups by policy and by design.
* Deleting the account removes server‑side data; "Erase all data" removes local keys and database.

## Testing

```bash
./gradlew testDebugUnitTest          # 15 JVM test classes
./tools/check_string_parity.sh       # English/Persian keys must match
./tools/license_inventory.py         # every dependency has a licence entry
./tools/check_no_secrets.sh          # no keys/keystores/elevated JWTs in the tree
```

| Test class | Covers |
| --- | --- |
| `PacketCodecTest` | frame encode/decode, header round‑trip, TTL/hop handling, malformed input |
| `RoutingPolicyTest` | next‑hop selection, relay suppression, TTL expiry |
| `MeshDeliveryTest` | store‑and‑forward queue and seen‑packet cache expiry |
| `SealedPayloadTest` | sealed header round‑trip, header/ciphertext binding, group and timestamp rules, receipt authorisation |
| `FrameRoutingTest` | deliver / relay / drop decision for every frame type, duplicates, expiry and hop limits |
| `LaunchIntentPolicyTest`, `PushWakeupPolicyTest`, `LikePatternTest` | who may answer a call from an intent; push de‑duplication; search pattern escaping |
| `FileChunkerTest` | chunking/reassembly boundaries |
| `PairingCodecTest` | pairing code round‑trip and validation |
| `FormatTest` | timestamp/byte formatting, locale independence |
| `TransportSelectorTest` | the full MESH/ONLINE/BOTH/QUEUE matrix |
| `RealtimeProtocolTest` | join/heartbeat frames, change parsing, malformed input |
| `BackoffTest` | outbox retry schedule, cap and exhaustion |

On an emulator in CI (`connectedDebugAndroidTest`): the encrypted database at rest, the Keystore
passphrase, **the mesh protocol between two to four complete simulated devices**
(`MeshProtocolInstrumentedTest`: real Signal sessions, the radio replaced by a hub that can drop,
rewrite and inject frames), local search against a real SQLite, and attachment forwarding.

Plus the **server-side suites**, which run against a disposable PostgreSQL database — locally with
`python3 supabase/tests/local/embedded_pg.py` (needs only `pip install pgserver`), in CI on every
push with a `postgres:16` service container:

| Suite | Covers |
| --- | --- |
| `supabase/tests/rls_policies.sql` | the whole permission matrix: membership, envelopes, pre-keys, Sender Keys rotation, calls, storage prefixes, blocks, rate limiting, `anon` denial |
| `supabase/tests/rls_write_path_hardening.sql` | findings W-1 … W-19: replays the app's own writes (must succeed) and the attack matrix around the RPCs (must be refused), plus privilege invariants |
| `supabase/tests/rls_privacy_quota_pins_timers_admin.sql` | who may read a profile, a device list or a membership row; quota counters; message timers; pins; group and channel administration |
| `supabase/tests/rls_social_graph.sql` | the S-2 hardening: no relationship may be probed for a third party, `mm_private` is unreachable by API roles, legitimate co-member access still works, plus an RLS-policy-coverage audit |

The harness also re-applies every migration to prove idempotency, and `backend.yml` type-checks the
three Edge Functions with Deno.

## Known limitations

Verified against the source tree and the backend on 2026-11-12. Items are listed because they are
genuinely open, not because they are hidden. `docs/AUDIT.md` Part A has the full finding list.

1. **Online media is implemented but needs two-device validation.** Encrypted upload to the private
   `mm-media` bucket, resumable transfer, thumbnails and progress/cancel/retry exist in code; the
   end-to-end path has not been exercised on physical phones yet (see
   [`docs/TWO_DEVICE_VALIDATION.md`](docs/TWO_DEVICE_VALIDATION.md)).
2. **Group calls are not implemented.** 1:1 WebRTC voice and video are. Group calls require an SFU;
   none is deployed, so no group call button exists.
3. **Instrumentation coverage is partial.** Storage, the Keystore, the mesh protocol (with a
   simulated radio), local search and forwarding have emulator-backed tests in CI; the UI,
   notifications, online sync against a server and Room migrations do not. Listed in
   `docs/ROADMAP.md`.
4. **Mesh protocol 2 has never run on two phones.** It replaced protocol 1 on 2026-11-12 to close
   forged receipts and relay tampering (`docs/AUDIT.md` A.7). Its logic is tested end to end on an
   emulator; discovery, link loss, throughput and battery use over real radios are not. Devices on
   older builds cannot talk to it over the mesh. HELLO is still unauthenticated (trust on first
   use), and mesh routing metadata is visible to devices in range.
5. **Background service** can still be killed by aggressive OEM power managers.
6. **Stories, polls, scheduled/silent messages, disappearing messages, chat folders, bots and Mini
   Apps are not implemented.** They are roadmap items in [`docs/ROADMAP.md`](docs/ROADMAP.md), not
   partial features, and no button pretends otherwise.
7. **No independent cryptographic audit has been performed.** The protocols are the audited
   libsignal implementation, but the integration is not third-party reviewed.
8. **Deployment lag.** Migrations `20261112070000`, `20261112120000` and `20261113090000` and the
   updated Edge Functions are in the repository but must still be pushed to the live Supabase
   project by the owner (`docs/OWNER_SETUP.md` §4). Until then the live deployment keeps the old
   behaviour described in `docs/AUDIT.md`, including the profile and presence exposure (P-1, P-2).
9. **Push is not configured in any published APK.** The Firebase client options are read from
   repository secrets that are not set (`docs/OWNER_SETUP.md` §1B).
10. **Server-side message timers, pins and group administration have no client.** The backend for
   them exists and is tested; the app neither offers them nor deletes a local copy at expiry.
11. **Licence decision outstanding.** `libsignal` is AGPL-3.0 and the repository has no `LICENSE`.
   The in-app attribution screen now exists (Settings → About → Open-source licences), but choosing
   the project licence is the owner's decision. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Roadmap

[`docs/ROADMAP.md`](docs/ROADMAP.md) is the maintained list. In short: physical two-device
validation, the licence decision, the remaining instrumentation coverage, then search filters,
pinned and scheduled messages, disappearing messages, chat folders, SFU group calls, polls and
stories.

## License

No licence has been chosen for this repository's own code yet — that is an owner decision, not
something to invent here. What is certain is the obligation created by the dependencies: the app
links [libsignal](https://github.com/signalapp/libsignal), which is **AGPL-3.0**, so distributing a
binary that includes it means complying with the AGPL (§13 included), and the SQLCipher BSD notice
must be reproduced in a user-accessible place. [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)
lists every component, its licence, and the three decisions that remain with the owner.
