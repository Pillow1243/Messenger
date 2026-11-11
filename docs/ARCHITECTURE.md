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

Room v2. New columns are nullable or defaulted; `MIGRATION_1_2` is additive only and the schema is
exported to `app/schemas` and checked in CI. Tables added: `online_outbox`, `sync_state`,
`peer_devices`, `peer_profiles`. Existing tables gained the online columns they need
(`conversations.online_id/peer_user_id/draft/...`, `messages.remote_id/client_msg_id/transport/...`).
No table was dropped, renamed or rewritten, so an upgraded install keeps every message.

## UI

* One `AppViewModel` exposes `StateFlow`s; screens are stateless composables that collect them.
  The data volume is small (one device's chats) and the state is shared across tabs, so a single
  holder is simpler than one view model per screen.
* The chat list shows an honest transport chip (`Mesh`, `Online`, `Mesh + online`, `Connecting`)
  that opens the account screen.
* RTL: the app never hardcodes `start`/`end` insets by hand; Compose's layout direction handles
  Persian, and all strings exist in `values-fa`.
* Every empty state explains what will appear there and why, including the offline case.

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
