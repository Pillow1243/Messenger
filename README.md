# Offline Mesh Messenger

A peer‑to‑peer messenger that needs **no internet, no server and no account**. Devices find each
other over Bluetooth LE / Wi‑Fi Direct using Google **Nearby Connections**, pair out‑of‑band with a
QR code, and exchange **end‑to‑end encrypted** messages (official Signal Protocol) that are stored
**encrypted at rest** (SQLCipher).

* **Package:** `com.offlinemesh.messenger`
* **Min Android:** 8.0 (API 26) · **Target/compile:** API 34
* **No `INTERNET` permission** — the app cannot reach the network even in principle
* **Languages:** English + فارسی (full RTL layout)

---

## Download

CI publishes a demo build on every push:

| File | What it is |
| --- | --- |
| [`offlinemesh-demo-signed.apk`](https://github.com/Pillow1243/Messenger/releases/download/demo-latest/offlinemesh-demo-signed.apk) | Release variant, signed with the **public Android debug key** |
| [`offlinemesh-debug.apk`](https://github.com/Pillow1243/Messenger/releases/download/demo-latest/offlinemesh-debug.apk) | Debug variant (`…messenger.debug` package, can sit next to the release build) |

Install: allow *"install unknown apps"* for your browser/file manager, open the APK, accept the
Play Protect prompt (the signing key is the well‑known debug key, so the warning is expected).

> **Demo signing only.** The debug keystore is public, so anybody can produce a package that
> Android considers a valid update of this one. For real use, generate your own keystore and wire it
> into `signingConfigs` — see [Signing](#signing).

The APK bundles only `arm64-v8a` and `armeabi-v7a` native libraries (no emulator ABIs), which keeps
the download to roughly a third of a universal build.

---

## What works today

* **Onboarding** — pick a display name and language, then land in the app; everything is local.
* **Pairing** — show your pairing code as QR, or import a peer's code by scanning. The code carries
  the device id, identity key and a one‑time pre‑key bundle, so the first message can be a real
  X3DH/PQXDH session instead of trust‑on‑first‑use.
* **Discovery** — Nearby Connections advertises and discovers on `P2P_CLUSTER`; a watchdog
  re‑arms advertising/discovery every 15 s, reconnects dropped endpoints and survives Wi‑Fi Direct
  teardown.
* **Chats** — 1:1 and group conversations with per‑message delivery state, retry, text and file
  attachments (files are chunked, capped at 90 KB per frame and encrypted with the file key).
* **Store‑and‑forward** — undeliverable packets are queued in the encrypted database with a TTL and
  relayed by any device that holds them (flooding suppressed by a seen‑packet cache), so a message
  can hop through an intermediate phone to reach someone out of radio range.
* **Connectors / Settings** — toggles for Bluetooth, Wi‑Fi, battery saver (lower duty cycle),
  background service, theme and language; "erase all data" wipes the key material and database.
* **Security** — Signal Protocol via `org.signal:libsignal-android`, keys generated in the Android
  Keystore where the platform allows it, SQLCipher for every table, no plaintext message on disk, no
  backups (`allowBackup=false`), no cleartext data in logs.

## Known limitations (read before judging the demo)

1. **Demo signing key** — see above.
2. **R8/minify is off** in the release variant. libsignal's JNI bridge resolves Java symbols by name
   at runtime; shrinking is deliberately disabled until the app has been exercised on physical
   devices with a shrunk build. Enable it in `app/build.gradle.kts` after device testing.
3. **No update checker.** A previous version phoned home for updates, which contradicts the
   offline promise; it was removed along with the `INTERNET` permission. If you want one, keep it
   **opt‑in and documented**: it must be a static manifest on your own host (or a Vercel/Supabase
   static file), never used for message routing.
4. **Group sessions** are fan‑out 1:1 Signal sessions (one session per member), not Sender Keys —
   correct but O(members) traffic per message.
5. **File transfers** are limited by Nearby payload size and are not resumable across reconnects.
6. **Background service** keeps the mesh alive but Android may still kill it on aggressive OEMs.

---

## Architecture

```
app/src/main/java/com/offlinemesh/messenger/
├── core/
│   ├── crypto/     KeystoreHelper, SignalStore, PreKeyBundleCodec, CryptoManager, FileVault
│   ├── db/         Room entities/DAOs over SQLCipher (12 tables, no plaintext fallback)
│   └── mesh/       Packet (framing + header codec), FileChunker, MeshManager, RadioController
├── data/           SettingsRepository, PairingCodec, ContactRepository, ChatRepository, FileRepository
├── service/        MeshService (foreground, connected‑device type)
├── work/           SyncWorker (periodic store‑and‑forward flush)
└── presentation/   Material 3 Compose UI: AppViewModel, 7 screens, NavGraph, theme, Format
```

**Message path.** UI → `AppViewModel` → `ChatRepository` (encrypt via `CryptoManager`, persist
ciphertext) → `MeshManager` (frame into packets, flood) → Nearby payload → peer `MeshManager` →
`CryptoManager.decrypt` → database → Compose UI.

**Wire format.** Every frame is `[magic 'O','M'][version][type][msgId][ttl][hops][headerLen]…`.
Discovery only exposes an endpoint *name*, so identity material travels in an authenticated `HELLO`
packet rather than in the advertisement.

**Crypto.** libsignal `PreKeyBundle` → `SessionBuilder`/`SessionCipher`; ciphertext type constants
(2 = pre‑key, 3 = whisper, 7/8 = sender key) are stored alongside the blob so the receiver knows
which decrypt path to use. Trust is shown as a safety number derived from both identity keys.

---

## Build

Requirements: JDK 17, Android SDK 34 + build‑tools 34.

```bash
git clone https://github.com/Pillow1243/Messenger.git
cd Messenger
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # 6 test classes: crypto codec, mesh routing, chunking, pairing, formatting
```

CI (`.github/workflows/build.yml`) runs on every push: unit tests → `assembleDebug`/`assembleRelease`
→ artifact upload → `demo-latest` prerelease. Each run also posts a commit comment with APK sizes and
the interesting part of the Gradle log.

### Signing

The release variant currently reuses the debug signing config. Replace it with your own:

```bash
keytool -genkeypair -v -keystore release.keystore -alias offlinemesh \
        -keyalg RSA -keysize 4096 -validity 10000
```

```kotlin
signingConfigs {
    create("release") {
        storeFile = file(System.getenv("KEYSTORE_PATH") ?: "release.keystore")
        storePassword = System.getenv("KEYSTORE_PASSWORD")
        keyAlias = "offlinemesh"
        keyPassword = System.getenv("KEY_PASSWORD")
    }
}
// then: buildTypes { release { signingConfig = signingConfigs.getByName("release") } }
```

## Permissions

| Permission | Why |
| --- | --- |
| `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` (API 31+) / `BLUETOOTH`, `BLUETOOTH_ADMIN` (≤30) | Nearby discovery and connections |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` (≤30) | Required by Nearby on older Android |
| `NEARBY_WIFI_DEVICES` | Wi‑Fi Direct transport on API 33+ |
| `CAMERA` | QR scanning of pairing codes |
| `RECORD_AUDIO` | Reserved for voice notes |
| `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Keeping the mesh alive in the background |
| `WAKE_LOCK` | Radio duty‑cycle under battery saver |

Deliberately **absent:** `INTERNET`, `ACCESS_NETWORK_STATE`. The app cannot send or receive anything
over the internet.

## Privacy

No analytics, no crash reporting, no account, no server. Message bodies, keys and metadata stay in the
SQLCipher database on the device; `allowBackup=false` and `dataExtractionRules` keep them out of
cloud backups. "Erase all data" in Settings deletes the database and the key material.

## Testing

```bash
./gradlew testDebugUnitTest
```

| Test class | Covers |
| --- | --- |
| `PacketCodecTest` | frame encode/decode, header round‑trip, TTL/hop handling, malformed input |
| `RoutingPolicyTest` | next‑hop selection, relay suppression, TTL expiry |
| `MeshDeliveryTest` | store‑and‑forward queue and seen‑packet cache expiry |
| `FileChunkerTest` | chunking/reassembly boundaries (`MAX_BYTES_DATA_SIZE`) |
| `PairingCodecTest` | pairing code round‑trip and validation |
| `FormatTest` | timestamp/byte formatting, locale independence |

Instrumented/on‑device tests are not wired up yet (`androidTest` scaffolding only).

## Roadmap

* On‑device validation with a shrunk (R8) release build, then re‑enable minification.
* Sender Keys for group chats instead of per‑member sessions.
* Resumable file transfers; media/voice notes wiring.
* Optional, opt‑in update manifest (static file only).

## License

The application code in this repository is provided as‑is for the demo. It links against
[libsignal](https://github.com/signalapp/libsignal), which is **AGPL‑3.0** — distributing a binary that
includes it means complying with the AGPL. Read the licence before shipping this app.
