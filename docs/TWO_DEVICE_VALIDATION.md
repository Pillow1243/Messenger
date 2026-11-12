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
3. Open **Contacts → My contact code** on Phone A; scan it from Phone B with **Scan code** (or paste the code under **Add contact**), and vice versa. Verify the **60-digit Safety Number** matches identically on both phones, then tap **Mark as verified**.
4. Open **Nearby** on both phones, wait 5–15 seconds for BLE/Wi-Fi Direct discovery, and tap to connect.
5. Exchange text messages, a recorded **Voice Note**, and an attached image/PDF (< 50 MB). Verify delivery status transitions `Queued -> Sent -> Delivered`.

### Suite B — Online Accounts, Multi-Device & Safety Numbers

1. Disable Airplane Mode (enable Wi-Fi or cellular internet) on both phones.
2. Open the **Account** tab, use **Create account** (or **Sign in**) for Account A on Phone A and Account B on Phone B.
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

1. On the **Chats** screen, tap the extended **New group** button (shown once you have at least one contact). Use contacts that are linked to an online account if you are testing online group delivery.
2. Open the group chat and verify the subtitle reads **`Signal Sender Keys (v2) · Epoch #1`**.
3. Send messages and attachments: verify all members decrypt immediately.
4. Tap the **Shield** icon in the top bar (**Group security & members**):
   * If testing an older `v1` group, tap **Upgrade to Signal Sender Keys (v2)** and verify subtitle updates to `Epoch #1`.
   * Remove a member: verify `Epoch #2` appears immediately and subsequent messages cannot be decrypted by the removed user.

### Suite E — 1:1 WebRTC Voice & Video Calling

1. Open the 1:1 direct chat between Alice and Bob (verify Call icons are **present on 1:1 Direct chats** and **absent on Groups/Channels**).
2. Tap **Voice call** on Phone A:
   * Verify Phone B displays the full-screen incoming call UI (or incoming call notification if backgrounded).
   * Tap **Decline** on Phone B -> verify Phone A transitions cleanly to `Call ended`.
3. Tap **Video Call** on Phone A and **Accept** on Phone B:
   * Verify bidirectional audio, remote video full-screen feed, and local PiP camera preview.
   * Test **Mute** / **Unmute**, **Speaker**, **Camera** (on/off) and **Flip**, then **End**.

### Suite G — Chat hygiene (archive, forward, delete-for-me, saved)

Run on both phones, signed in and offline at turns.

1. **Archive** — chat menu → *Archive chat*. The chat leaves the list; the *Archived chats* row at the
   top shows `1`. Open *Archived chats* → tap the chat (opens, stays archived) → *Unarchive* → the
   chat returns to the main list and the counter row disappears.
2. **Delete for me** — long-press a **received** message → *Delete for me*. It disappears only on
   that phone; the sender's copy stays. Repeat with an **outgoing** queued message while offline and
   confirm it never uploads later (the outbox entry is cancelled).
3. **Delete for everyone** — long-press an outgoing message → *Delete for everyone*; both phones show
   the deleted placeholder.
4. **Forward online** — long-press a text message → *Forward* → pick a second chat. The message
   arrives in the target chat and in its own thread order.
5. **Forward offline (mesh)** — with both phones in Airplane Mode + Nearby enabled, forward a text
   message and a voice note to the mesh chat. Both arrive over the radio, exactly once.
6. **Saved messages** — star a message, open chat-list overflow → *Saved messages*: the entry appears
   with the chat title and timestamp; tap it to jump to the chat; *Remove* drops it from the list.
7. **Persian** — switch to `فارسی` and repeat 1–6: labels are translated, the back arrow mirrors, and
   forwarded content keeps its direction inside the bubble.

### Suite H — Write-path hardening (run after migrations `20261112070000` + `20261112120000` are live)

These are the first checks of the hardened backend against the real platform; the automated suite
runs against a stub of it. Any failure here is a blocker — note the exact error text shown.

1. **Photo, video, audio** — send one of each online (Suite C covers transfer; here the point is that
   the message row is accepted at all). Before the migration these three kinds were rejected.
2. **Retry is harmless** — start sending a text to a contact, switch to Airplane Mode before the
   single tick appears, wait ten seconds, switch back. The message arrives exactly once and does not
   end in a failed state.
3. **Edit** — edit a sent text. The other phone shows the new text and the edited marker. Edit the
   same message a second time; the second edit also arrives.
4. **Hang up** — call, answer, hang up from the caller. The callee's screen closes within about two
   seconds. Repeat hanging up from the callee, and repeat with a declined call.
5. **Mute** — mute a group on Phone A; Phone B's notification setting for the same group is unchanged.
6. **Revoke a device** — sign the same account in on both phones. On Phone A open *Account →
   Devices* and revoke Phone B. Phone B is signed out by itself the next time it syncs and shows no
   new messages. Sign in again on Phone B with the password: it links again, and messages sent
   afterwards arrive.
7. **Group removal** — remove a member from a v2 group; that member receives nothing sent
   afterwards, and the remaining members still read each other's messages.
8. **Typing, reactions, read receipts** — type, react and read on both sides; the indicators behave
   as before the migration.

### Suite F — Hybrid Offline <-> Online Transitions (All 10 Scenarios)

1. **Mesh -> Online**: Pair & chat offline first, then sign in online and link — verify one unified conversation thread (`Mesh + online`).
2. **Online -> Mesh**: Chat online first, then enable Airplane Mode + BT/Wi-Fi in proximity — verify messages continue in the same thread via `MESH`.
3. **Zero-Connectivity Queue**: Disable both internet and Bluetooth, send 3 messages (`Queued`), then restore internet or Nearby — verify automatic delivery in exact order.
4. **Mid-Chat Wi-Fi Drop**: While `Mesh + online`, turn off Wi-Fi router — verify uninterrupted delivery via Nearby mesh.
5. **Process Kill Recovery**: Force-stop the app mid-send/transfer and relaunch — verify `recoverInterruptedState()` resumes queued messages/transfers without duplicates.
6. **Cross-Transport Dedup**: Send while both `MESH` and `ONLINE` are active (`Transport.BOTH`) — verify recipient sees **exactly one** message row (`transport = BOTH`).
7. **Persian (`فارسی`) RTL**: Switch the language chip **فارسی** on the **Settings** screen on Phone A — verify full right-to-left mirroring, mirrored directional icons (`ArrowBack`, `ArrowForward`, `Reply`, `Send`), and zero raw untranslated keys.

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
| **EN + FA (`فارسی`) String Parity, RTL Icons & R8 Minification** | **Yes** (`tools/check_string_parity.sh`: 250/250 keys; `lintDebug`; R8 `assembleDemo`) | Not run in cloud sandbox | Ready via Suite F & G | Zero literal directional arrows; full `Icons.AutoMirrored` usage. |
| **Archive, delete-for-me, forward, saved messages** | **Yes for the data paths** (`ChatRepository`/DAO logic compiles; local-only state) | Not run in cloud sandbox | **Ready via Suite G** | Forwarding and archive are pure local operations, so the radio tests only need to confirm delivery of the forwarded copy. |
| **Social-graph authorisation hardening (S-2)** | **Yes** (`supabase/tests/rls_social_graph.sql`, plus a negative run on a pre-fix database) | N/A | Not applicable | The fix is server-side; it is live only after the owner runs `supabase db push`. |
| **Write-path hardening (W-1 … W-19) and the client fixes that go with it** | **Yes for the database** (`supabase/tests/rls_write_path_hardening.sql`: client contract + attack matrix, on a stub of the platform); `EditPolicyTest` for edit authorship | N/A | **Not yet — Suite H** | Not live until the owner applies both pending migrations. Real PostgREST, Storage and access tokens are first exercised by Suite H. |
| **Edge Function rate limiting & gateway JWT (S-3/S-4)** | **Yes** (Deno type-check in CI; `mm_consume_rate_limit_as` covered by the suite) | N/A | Not applicable | Requires `supabase functions deploy` for the `verify_jwt`/limiter changes to take effect live. |
