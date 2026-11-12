# Owner Setup Guide (Optional External Credentials)

Offline Mesh Messenger works out of the box for:

* **Offline Nearby Mesh** (BLE + Wi-Fi Direct discovery, QR PQXDH pairing, E2EE text/files/voice, store-and-forward relay) — zero accounts or servers required.
* **Online E2EE Messaging & Media** (`lnhmbjqjfrnqkpsjpszb` Supabase Auth, Realtime, PostgREST, private `mm-media` bucket with streaming AES-256-GCM attachments).
* **Audited Signal Sender Keys (v2) Groups**, **Multi-Device Management (up to 5 active devices)**, **Remote Revocation**, **Pre-Key Rotation**, and **Full Account Deletion** (`mm-account-delete`).
* **1:1 WebRTC Voice & Video Calling** over direct / STUN-traversable networks (`stun:stun.l.google.com:19302`, `stun:stun1.l.google.com:19302`).

Three production capabilities depend on owner-controlled external accounts/keys that are **never hardcoded or faked** in the repository. Follow the sections below to enable them.

---

## 1. Firebase Cloud Messaging (Background Push Wakeups)

Both the Android client (`MessengerFirebaseMessagingService`, `FirebasePushInitializer`) and the Supabase Edge Function (`mm-push-dispatch`) are deployed and active. Until Firebase credentials are supplied:

* `FirebasePushInitializer.status` reports `UNCONFIGURED` (shown transparently in **Account**).
* `mm-push-dispatch` returns `{ "ok": true, "configured": false, "sent": 0 }` without failing message or call creation.
* Push payloads are **strictly zero-plaintext** (`type = "mm_wakeup"`, `event_kind`, `conversation_id`, `target_id`, `expires_at_ms`); message bodies, sender names, filenames, and media keys never traverse FCM.

### Step 1A — Create Firebase Android apps

1. In the [Firebase Console](https://console.firebase.google.com/), create a project (or select your existing project).
2. Register Android package IDs you intend to distribute:
   * Production: `com.offlinemesh.messenger`
   * Debug / Demo (optional): `com.offlinemesh.messenger.debug`, `com.offlinemesh.messenger.demo`
3. Note the four client values from `google-services.json` (`mobilesdk_app_id`, `current_key`, `project_id`, `project_number`).

### Step 1B — Pass Firebase client options at build time

Supply via Gradle properties or CI environment variables (no `google-services` Gradle plugin required):

```bash
export FIREBASE_APP_ID="1:1234567890:android:abcdef123456"
export FIREBASE_API_KEY="AIzaSy..."
export FIREBASE_PROJECT_ID="your-firebase-project-id"
export FIREBASE_SENDER_ID="1234567890"

./gradlew assembleDemo -PdemoSign=true
```

### Step 1C — Configure `FCM_SERVICE_ACCOUNT_JSON` on Supabase

1. In Firebase Console -> **Project settings -> Service accounts**, click **Generate new private key** to download the FCM HTTP v1 JSON key.
2. Set the secret on Supabase project `lnhmbjqjfrnqkpsjpszb`:

```bash
supabase secrets set --project-ref lnhmbjqjfrnqkpsjpszb \
  FCM_SERVICE_ACCOUNT_JSON="$(cat service-account.json)"
```

`mm-push-dispatch` immediately begins minting OAuth2 `firebase.messaging` tokens and dispatching high-priority data-only wakeups, automatically revoking tokens when FCM returns `UNREGISTERED`.

---

## 2. Production TURN Relay (Symmetric NAT / Carrier Grade NAT Calls)

`mm-turn-credentials` is deployed and queried automatically before every outgoing or answered 1:1 WebRTC call. Without TURN secrets, it returns STUN servers (`turnConfigured: false`), and `CallOverlay` displays a transparent notice (`Direct P2P / STUN mode`).

To enable coturn / Cloudflare Calls / Metered TURN time-limited HMAC credentials (`TURN REST API`):

```bash
supabase secrets set --project-ref lnhmbjqjfrnqkpsjpszb \
  TURN_URLS="turn:turn.example.com:3478?transport=udp,turn:turn.example.com:3478?transport=tcp,turns:turn.example.com:5349?transport=tcp" \
  TURN_SHARED_SECRET="<your-coturn-static-auth-secret>" \
  TURN_TTL_SECONDS="3600"
```

Alternatively, for static-credential TURN providers, set `TURN_URLS`, `TURN_USERNAME`, and `TURN_CREDENTIAL`. The function also reads `CF_TURN_TOKEN_ID` / `CF_TURN_API_TOKEN` (Cloudflare Calls) and `METERED_DOMAIN` / `METERED_API_KEY` (Metered). Never place TURN credentials in Android source code or `BuildConfig`.

---

## 3. Production Release Signing Keystore

`release` builds enable full R8 shrinking and obfuscation (`isMinifyEnabled = true`, `isShrinkResources = true`). Production signing is activated only when all four signing environment variables are provided in GitHub Actions Secrets:

* `MESSENGER_KEYSTORE_BASE64` (base64-encoded `.jks` / `.keystore`)
* `MESSENGER_KEYSTORE_PASSWORD`
* `MESSENGER_KEY_ALIAS`
* `MESSENGER_KEY_PASSWORD`

The release workflow (`.github/workflows/release.yml`) runs only for `v*` tags or a manual dispatch,
and stops with an error if any of the four is missing. It never falls back to the debug key.

For local signed release builds:

```bash
export MESSENGER_KEYSTORE_PATH="/secure/path/release.keystore"
export MESSENGER_KEYSTORE_PASSWORD="..."
export MESSENGER_KEY_ALIAS="offlinemesh"
export MESSENGER_KEY_PASSWORD="..."
./gradlew assembleRelease
```
