# Two-Physical-Device Validation Guide & Validation Matrix

This guide provides a reproducible field-validation protocol for two physical Android 8.0+ (API 26+) phones, alongside the honest validation matrix separating automated CI/live-backend verification from physical radio/hardware testing.

---

## 1. Obtaining & Installing the Dedicated Test APK

Every CI run produces two installable APKs in the **`messenger-apks`** workflow artifact, and merges to `main` publish them to the **`demo-latest`** GitHub prerelease:

| Artifact | Package ID | R8 Minified | Intended Use |
| --- | --- | --- | --- |
| `offlinemesh-demo-signed.apk` | `com.offlinemesh.messenger.demo` | **Yes** (`isMinifyEnabled = true`, `isShrinkResources = true`) | **Recommended two-device validation APK** — exercises exact production R8 keep rules for `libsignal`, `org.webrtc`, `sqlcipher`, `Room`, and `FCM`. |
| `offlinemesh-debug.apk` | `com.offlinemesh.messenger.debug` | No | Debuggable build for `adb logcat` inspection. |

### Integrity & Installation

```bash
sha256sum -c SHA256SUMS.txt
adb -s <SERIAL_PHONE_A> install -r offlinemesh-demo-signed.apk
adb -s <SERIAL_PHONE_B> install -r offlinemesh-demo-signed.apk
```

Grant **Nearby devices / Bluetooth / Location**, **Notifications**, **Microphone**, and **Camera** when prompted.

---

## 2. Step-by-Step Two-Device Validation Protocol

### Suite A — Pure Offline Mesh (Zero Internet, Zero Accounts)

1. Enable **Airplane Mode** on both Phone A and Phone B, then re-enable **Bluetooth** and **Wi-Fi** (without connecting to any internet router).
2. Complete onboarding on Phone A ("Alice") and Phone B ("Bob").
3. Open **Contacts -> My QR** on Phone A; scan from Phone B (**Scan QR**), and vice versa. Verify the **60-digit Safety Number** matches identically on both phones and tap **Mark Verified**.
4. Open **Nearby** on both phones, wait 5–15 seconds for BLE/Wi-Fi Direct discovery, and tap to connect.
5. Exchange text messages, a recorded **Voice Note**, and an attached image/PDF (< 50 MB). Verify delivery status transitions `Queued -> Sent -> Delivered`.

### Suite B — Online Accounts, Multi-Device & Safety Numbers

1. Disable Airplane Mode (enable Wi-Fi or cellular internet) on both phones.
2. Open **Settings -> Online Account**, create/sign into Account A on Phone A and Account B on Phone B.
3. Verify **Account** displays `1 of 5 active devices` with the **This device** badge and platform/model metadata.
4. Tap **Rotate pre-keys now** on Phone A and verify no errors occur.
5. *(Optional 3rd device)* Sign into Account A on a secondary phone/tablet: verify `2 of 5 active devices` appears, verify per-device safety numbers on Phone B's Contact Detail screen, then tap **Revoke** on the secondary device from Phone A and confirm the revoked device immediately signs out with `This device was revoked from your account`.

### Suite C — Online E2EE Streaming Media & File Transfers

1. Start an online chat between Account A and Account B (keep Bluetooth off to force the `ONLINE` path).
2. Tap the **Attach** clip icon and select **multiple attachments at once** (e.g., 1 JPEG photo + 1 MP4 video + 1 PDF document).
3. Verify:
   * Instant inline encrypted thumbnail preview renders on both sender and receiver bubbles with dimensions (`W×H`) and file size.
   * Upload/download progress bar (`Encrypting & uploading…` / `Downloading & verifying…`) updates smoothly.
   * Tap **Play** on an audio/voice message to play inline; tap a photo/video/PDF to open via `FileProvider`.
4. Start uploading a large video (30–100 MB) and tap **Cancel (`×`)**: verify transfer stops (`Cancelled`) and tapping **Retry (`↻`)** completes cleanly.
5. In Supabase Studio -> Storage -> `mm-media`, inspect the stored object: verify the object path contains an opaque UUID (no original filename) and downloading raw bytes yields random high-entropy AES-256-GCM ciphertext.

### Suite D — Audited Signal Sender Keys (`v2`) Groups & Key Rotation

1. Create a new group from **Chats -> New Group** while signed in with linked contacts (or via online group creation).
2. Open the group chat and verify the subtitle reads **`Signal Sender Keys (v2) · Epoch #1`**.
3. Send messages and attachments: verify all members decrypt immediately.
4. Tap the **Shield** icon in the top bar (**Group security & members**):
   * If testing an older `v1` group, tap **Upgrade to Signal Sender Keys (v2)** and verify subtitle updates to `Epoch #1`.
   * Remove a member: verify `Epoch #2` appears immediately and subsequent messages cannot be decrypted by the removed user.

### Suite E — 1:1 WebRTC Voice & Video Calling

1. Open the 1:1 direct chat between Alice and Bob (verify Call icons are **present on 1:1 Direct chats** and **absent on Groups/Channels**).
2. Tap **Voice Call** on Phone A:
   * Verify Phone B displays the full-screen incoming call UI (or incoming call notification if backgrounded).
   * Tap **Decline** on Phone B -> verify Phone A transitions cleanly to `Call ended`.
3. Tap **Video Call** on Phone A and **Accept** on Phone B:
   * Verify bidirectional audio, remote video full-screen feed, and local PiP camera preview.
   * Test **Mute/Unmute**, **Speakerphone**, **Camera On/Off**, **Flip Front/Back Camera**, and **End Call**.

### Suite F — Hybrid Offline <-> Online Transitions (All 10 Scenarios)

1. **Mesh -> Online**: Pair & chat offline first, then sign in online and link — verify one unified conversation thread (`Mesh + online`).
2. **Online -> Mesh**: Chat online first, then enable Airplane Mode + BT/Wi-Fi in proximity — verify messages continue in the same thread via `MESH`.
3. **Zero-Connectivity Queue**: Disable both internet and Bluetooth, send 3 messages (`Queued`), then restore internet or Nearby — verify automatic delivery in exact order.
4. **Mid-Chat Wi-Fi Drop**: While `Mesh + online`, turn off Wi-Fi router — verify uninterrupted delivery via Nearby mesh.
5. **Process Kill Recovery**: Force-stop the app mid-send/transfer and relaunch — verify `recoverInterruptedState()` resumes queued messages/transfers without duplicates.
6. **Cross-Transport Dedup**: Send while both `MESH` and `ONLINE` are active (`Transport.BOTH`) — verify recipient sees **exactly one** message row (`transport = BOTH`).
7. **Persian (`فارسی`) RTL**: Switch **Settings -> Language -> فارسی** on Phone A — verify full right-to-left mirroring, mirrored directional icons (`ArrowBack`, `ArrowForward`, `Reply`, `Send`), and zero raw untranslated keys.

---

## 3. Honest Validation Matrix

| Subsystem / Requirement | Automated Tested (CI + Live DB) | Emulator Tested | Physical-Device Tested | Notes / External Dependencies |
| --- | --- | --- | --- | --- |
| **Room v1 -> v2 -> v3 Additive Migration & KSP Schema (`3.json`)** | **Yes** (`MigrationTest`, `kspDebugKotlin`, CI schema check) | Not run in cloud sandbox | Ready via APK | Verified zero destructive SQL; all columns defaulted/nullable. |
| **Live Supabase Schema, RPCs & Storage RLS (`mm_*`, `mm-media`)** | **Yes** (Live PL/pgSQL suite on `lnhmbjqjfrnqkpsjpszb`) | N/A | Ready via APK | All 25 `mm_*` tables RLS-enabled; `anon` has zero table grants; `luma_*` untouched. |
| **Streaming AES-256-GCM Media Cipher & SHA-256 Integrity** | **Yes** (`MediaCipherTest`: 300 KiB multi-chunk, tamper, wrong key, cancel) | Not run in cloud sandbox | Ready via Suite C | Ephemeral 256-bit key + 96-bit IV never sent to server or FCM. |
| **Audited `libsignal` `0.86.5` Sender Keys (`v2`) & Epoch Rotation** | **Yes** (`SenderKeyGroupEngineTest` over real `libsignal-client` Rust JNI) | Not run in cloud sandbox | Ready via Suite D | Verified O(1) encrypt, out-of-order decrypt, replay rejection, member-removal & device-revocation forward secrecy. |
| **FCM Zero-Plaintext Push Policy, Dedup & Expiry** | **Yes** (`PushWakeupPolicyTest` + live `mm-push-dispatch` deployment) | Not run in cloud sandbox | Ready once owner sets Firebase secrets | Requires owner `FCM_SERVICE_ACCOUNT_JSON` + Firebase client config (`docs/OWNER_SETUP.md`) for killed-app background wakeup. |
| **1:1 WebRTC State Machine, Busy/Direct Guards & TURN Edge Fn** | **Yes** (`CallStateMachineTest` + live `mm-turn-credentials` deployment) | Not run in cloud sandbox | Ready via Suite E | Direct/STUN works out of the box; symmetric-NAT relay requires owner `TURN_*` secrets (`docs/OWNER_SETUP.md`). |
| **All 10 Hybrid Mesh <-> Online Transition Invariants** | **Yes** (`HybridTransitionTest`, `TransportSelectorTest`, `RoutingPolicyTest`, `MeshDeliveryTest`) | Not run in cloud sandbox | Ready via Suite A & F | Real BLE / Wi-Fi Direct radio handoffs require two physical Android phones. |
| **Multi-Device (5-Device Cap, Revocation, Pre-Key Rotation, Account Delete)** | **Yes** (Live SQL RPC verification + `mm-account-delete` Edge Function) | Not run in cloud sandbox | Ready via Suite B | Enforces max 5 active devices & rotates group Sender Keys on device revocation. |
| **EN + FA (`فارسی`) String Parity, RTL Icons & R8 Minification** | **Yes** (234/234 EN=FA keys, `lintDebug`, R8 `assembleDemo`) | Not run in cloud sandbox | Ready via Suite F | Zero literal directional arrows; full `Icons.AutoMirrored` usage. |
