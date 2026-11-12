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

---

## 4. Deploying the security hardening to the live project

The repository is ahead of the deployed backend. On 2026-11-12 the live project
`lnhmbjqjfrnqkpsjpszb` had six migrations applied (through `20261112035022`) and the three `mm-*`
Edge Functions at version 1 with `verify_jwt = false`. Not yet applied:

| Migration | Closes |
| --- | --- |
| `20261112070000_mm_social_graph_authz.sql` | S-2 (social-graph enumeration), service-role rate limiter |
| `20261112120000_mm_write_path_hardening.sql` | W-1 … W-19 (direct writes around the RPCs), and the constraint that rejected photo/video/audio messages |

Both are idempotent, touch only `mm_*` objects, the `mm_private` schema and the policies of the two
`mm-*` buckets, and delete no row. Apply them **in this order**:

```bash
supabase link --project-ref lnhmbjqjfrnqkpsjpszb
supabase db push          # shows the pending files first; confirm that only these two are listed

# Redeploy the three functions; supabase/config.toml sets verify_jwt = true for each,
# so the gateway flag changes with the deployment, not by hand.
supabase functions deploy mm-account-delete
supabase functions deploy mm-turn-credentials
supabase functions deploy mm-push-dispatch
```

If `supabase db push` lists more than those two files (the version numbers recorded in the live
history may not match the file names), do not force it: paste the two
files into the SQL editor instead, one after the other. Re-running any Messenger migration is safe.

Verify afterwards (read-only, from the SQL editor or `psql`):

```sql
-- no API role may reach the internal helpers
select has_schema_privilege('authenticated', 'mm_private', 'USAGE');           -- must be false
select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'mm_private' and has_function_privilege('anon', p.oid, 'EXECUTE'); -- must be 0
-- the limiters are not callable by clients
select has_function_privilege('authenticated',
        'public.mm_consume_rate_limit_as(text,text,integer,integer)', 'EXECUTE');   -- must be false
select has_function_privilege('authenticated',
        'public.mm_consume_rate_limit(text,integer,integer)', 'EXECUTE');           -- must be false
-- RPC-only tables are not directly writable, anon holds nothing
select has_table_privilege('authenticated', 'public.mm_conversation_members', 'INSERT'),  -- false
       has_table_privilege('authenticated', 'public.mm_devices', 'UPDATE'),               -- false
       has_table_privilege('authenticated', 'public.mm_calls', 'UPDATE'),                 -- false
       has_table_privilege('anon', 'public.mm_messages', 'SELECT');                       -- false
-- exactly one call-state function is left
select count(*) from pg_proc where proname = 'mm_set_call_state';                         -- 1
```

Then do the two-device pass in `docs/TWO_DEVICE_VALIDATION.md`: it is the first time these rules
meet the real platform (PostgREST, Storage, real access tokens) rather than the test stub.

**Rollback.** Neither migration modifies or deletes data, so there is nothing to restore. If the
write-path migration blocks something legitimate, the narrow remedy is to grant the missing
statement on that one table (for example `grant update (some_column) on public.mm_x to
authenticated;`) and report it, not to revert the file. Reverting `20261112070000` would mean
restoring the previous helper bodies from `20261111120000` / `20261111120200`; do not
`drop schema mm_private cascade` once `20261112120000` is applied, because the policies and RPCs
now depend on it.

> Shared project warning: this database also hosts another application. Only `mm_*` objects (and the
> `mm-media` / `mm-avatars` buckets) belong to Messenger. Never modify `luma_*` tables, functions or
> buckets.
