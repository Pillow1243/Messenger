# Architecture

## Shape

One Gradle module (`:app`), layered by package. Splitting into a dozen Gradle modules would only
add build complexity: the code base is small, there is exactly one consumer of each layer, and the
package boundaries below are enforced by review, not by the compiler. Each package has one reason
to change.

```
com.offlinemesh.messenger
├── core/
│   ├── crypto/      libsignal wrapper (CryptoManager, SignalStore, PreKeyBundleJson)
│   ├── db/          Room entities, DAOs, database factory (SQLCipher), migrations
│   ├── mesh/        transport-agnostic mesh engine (BLE / Wi-Fi Direct / Nearby), routing
│   └── security/    SecretStore (Keystore AES-GCM)
├── network/         Supabase: config, HTTP, auth, PostgREST, realtime, connectivity
├── domain/          pure policy: TransportSelector, Backoff, OnlineState
├── data/            repositories and the sync engine (ChatRepository, OnlineSync, OnlineApi)
├── presentation/    Compose screens, components, theme, one shared view model
├── service/         foreground service for the mesh
└── work/            WorkManager jobs (mesh housekeeping, online outbox)
```

Dependency direction is strictly downward: `presentation → data → {domain, network, core}`.
`core/mesh` knows nothing about Supabase; `network` knows nothing about Room.

## Two transports behind one repository

`ChatRepository` is the only write path for messages. For every outgoing message it asks
`TransportSelector` what is usable right now:

```
sendText() ──► persist message (QUEUED) ──► deliverMessage()
                                              │
                     ┌────────────────────────┴─────────────────────────┐
                     ▼                                                  ▼
        OnlineSync.deliver()                                 dispatchMeshText()
   per-device Signal envelope → mm_messages             per-peer envelope → relay queue
   + mm_message_envelopes, outbox on failure            + direct hand-off if in range
```

* The **message row is written first**, so a crash between "user pressed send" and "ciphertext
  produced" leaves a queued message, never a lost one.
* Both paths mark the same row `SENT`, so the UI does not need to know which transport won.
* `BOTH` is attempted for low-latency delivery; the receiving side de-duplicates by message id.
* Online failure never blocks the mesh path in the same call: `deliverMessage` queues the online
  copy in `online_outbox` and still tries the mesh.

## Online sync engine (`data/OnlineSync`)

* **Bootstrap**: register this installation's public bundle and pre-keys (`mm_register_device`),
  refreshing only when the pre-key pool drops below 5 or the registration is a day old (the
  endpoint is rate limited).
* **Realtime**: five channels (`mm_message_envelopes` filtered to the current user, `mm_messages`,
  `mm_message_receipts`, `mm_typing`, `mm_reactions`). Realtime is a *hint*: after every
  (re)connection `backfillAll()` re-reads from the stored cursor in `sync_state`, so a dropped
  socket cannot lose messages.
* **Ingest**: envelope → libsignal decrypt → insert local row → mark delivered → delete the
  envelope. An envelope that fails to decrypt is left on the server for a later attempt; an
  envelope already ingested is deleted without processing.
* **Egress**: `deliver()` claims a bundle per device that has no session yet, encrypts once per
  device, inserts the message row, then the envelopes. `enqueue()` + `drainOutbox()` retry with
  `Backoff` (5 s → 10 min, 8 attempts) both in-process and from WorkManager.
* **Edits** are a normal encrypted message with `kind = "text-edit"` and `meta.target`, so peers
  learn about the change through the same INSERT path they already trust.
* **Conversation mapping**: the server conversation id is stored on the *existing* local row
  (`linkOnline`), so a peer you met in the mesh does not gain a duplicate chat when you sign in.

## Storage

Room **v3** (`AppDatabase.CURRENT = 3`, schemas `app/schemas/.../2.json` and `3.json`). Migrations:

* `MIGRATION_1_2` is additive: it adds online columns to `conversations`, `messages` and `contacts`,
  the `plaintextSha256` column on `file_transfers`, and the tables `online_outbox`, `sync_state`,
  `peer_devices` and `peer_profiles`.
* `MIGRATION_2_3` is additive: group encryption metadata on `conversations` (`encryptionVersion`,
  `senderKeyEpoch`, `distributionId`, `mutedUntil`, `myRole`) and file metadata on `messages`
  (`fileMime`, `fileSha256`), plus the tables `signal_sender_keys`, `group_skdm_states` and
  `call_logs`.

Every new column is nullable or has a default, so SQLite accepts the `ALTER TABLE`. No table was
dropped, renamed or rewritten, so an upgraded install keeps every message. `MigrationTest` checks the
statements, and CI fails if an exported schema changes without being committed (`app/schemas`).

## UI

* One `AppViewModel` exposes `StateFlow`s; screens are stateless composables that collect them.
  The data volume is small (one device's chats) and the state is shared across tabs, so a single
  holder is simpler than one view model per screen.
* The chat list shows an honest transport chip (`Mesh`, `Online`, `Mesh + online`, `Connecting`)
  that opens the account screen.
* RTL: the app never hardcodes `start`/`end` insets by hand; Compose's layout direction handles
  Persian, and all strings exist in `values-fa`.
* Every empty state explains what will appear there and why, including the offline case.
* Chat list → chat → *Archived chats* (`ArchiveAndSavedScreens.kt`) and *Saved messages* are separate
  destinations in `NavGraph`, so both have a real back stack rather than a dialog that swallows the
  system back gesture. Forwarding reuses the same destination model: a `ForwardPickerDialog` lists
  the chats already on the device and calls `ChatRepository.forwardMessage`, which re-encrypts for
  the target chat like a freshly typed message.
* Device-local state is deliberately separated from server state: archive flags, starred messages
  and drafts live only in SQLCipher. They are private, they work offline, and they cannot fail
  because a policy rejected a write.

## Authorisation model (server side)

RLS policies need membership answers inside their own expression, which requires `SECURITY DEFINER`
helpers. Those helpers are the only place where the client-facing API and the policy layer touch, so
they are layered explicitly:

```
API client ──RPC──▶ public.mm_is_member(conv, user)      (caller-scoped wrapper, EXECUTE: authenticated)
                            │
                            └──▶ mm_private.member_exists(conv, user)   (true implementation)
                                 schema not in PostgREST `schemas`, EXECUTE: owner only
```

`supabase/config.toml` pins `schemas = ["public"]`, so `mm_private` has no REST route at all. The
wrappers enforce that a caller may only learn about itself or about co-members of a conversation it
belongs to; anything else returns `false`/`null`. Writes never rely on the wrappers for privilege:
every mutating RPC re-checks `auth.uid()` and re-reads the graph inside its own transaction.

## Failure behaviour

| Failure | Behaviour |
| --- | --- |
| No network | Chat still works over the mesh; sends are queued, `ModeBanner` says so |
| Token expired | `SupabaseAuth.validAccessToken()` refreshes under a mutex; realtime rejoins with the new token |
| Realtime socket closed | Exponential reconnect (1 s → 30 s); backfill on rejoin |
| Decrypt failure | Envelope stays on the server, no row is created, error is logged without content |
| Identity key changed | Send aborts, message marked FAILED, user must reset trust explicitly |
| Keystore key lost | Database quarantined, fresh DB created, nothing silently deleted |
| App killed with queued online messages | `OutboxWorker` flushes on the next network |
