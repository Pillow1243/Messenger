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

### Mesh (unchanged, still the core)

* **Pairing** — QR code carrying device id, identity key and a one‑time pre‑key bundle, so the first
  message is a real X3DH/PQXDH session.
* **Discovery** — Nearby Connections on `P2P_CLUSTER` with a 15 s watchdog that re‑arms advertising,
  reconnects dropped endpoints and survives Wi‑Fi Direct teardown.
* **Store‑and‑forward** — undeliverable packets wait in the encrypted DB with a TTL and are relayed
  by any device holding them; duplicates are suppressed by a seen‑packet cache.
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

Provide `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` to the release
workflow. `-PdemoSign=true` produces a debug‑signed **demo** variant (`.demo` application id) that is
what the CI publishes to the `demo-latest` prerelease; it is not an upgrade path for a real install.

### CI

* `ci.yml` — every push and PR: unit tests → lint → assemble debug/demo/release → Room schema drift
  check → artifact upload. All actions are pinned to commit SHAs.
* `release.yml` — tags only: signed APK + AAB, checksums, build metadata, release notes; concurrency
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
./gradlew testDebugUnitTest
```

| Test class | Covers |
| --- | --- |
| `PacketCodecTest` | frame encode/decode, header round‑trip, TTL/hop handling, malformed input |
| `RoutingPolicyTest` | next‑hop selection, relay suppression, TTL expiry |
| `MeshDeliveryTest` | store‑and‑forward queue and seen‑packet cache expiry |
| `FileChunkerTest` | chunking/reassembly boundaries |
| `PairingCodecTest` | pairing code round‑trip and validation |
| `FormatTest` | timestamp/byte formatting, locale independence |
| `TransportSelectorTest` | the full MESH/ONLINE/BOTH/QUEUE matrix |
| `RealtimeProtocolTest` | join/heartbeat frames, change parsing, malformed input |
| `BackoffTest` | outbox retry schedule, cap and exhaustion |

Plus a **server‑side authorization suite** (`supabase/tests/rls_policies.sql`) that runs against the
live project and asserts the whole permission matrix.

## Known limitations

1. **Online media upload is not wired.** Mesh files work; sending a file over the online transport is
   refused with `media_online_unsupported` rather than silently lost. The bucket, RLS and signed‑URL
   path exist.
2. **Calls are not implemented.** No WebRTC stack and no call button that pretends otherwise.
3. **Group encryption is per‑device fan‑out**, not Sender Keys: correct, but O(devices) ciphertexts.
4. **R8/minify stays off** until a shrunk build has been exercised on physical devices (libsignal's
   JNI bridge resolves symbols by name).
5. **Background service** can still be killed by aggressive OEM power managers.
6. **No physical‑device validation of the online path yet** — it compiles, unit tests pass and the
   backend assertions pass, but end‑to‑end realtime behaviour needs two real devices.

## Roadmap

* Online media: encrypted upload, resumable transfer, thumbnails, progress/cancel.
* Sender Keys for large groups.
* WebRTC calls with the `mm_calls` signalling tables already in place.
* Media playback, message pinning UI, translation (opt‑in), scheduled messages.

## License

The application code is provided as‑is for the demo. It links against
[libsignal](https://github.com/signalapp/libsignal), which is **AGPL‑3.0** — distributing a binary
that includes it means complying with the AGPL. Read the licence before shipping this app.
