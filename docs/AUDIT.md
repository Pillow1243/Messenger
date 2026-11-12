# Repository audit

This file has two parts. **Part A** is the current state (2026-11-12) and is the one to act on.
**Part B** is the historical audit of 2026-11-11 (base `5880f98`). Its findings were written against
that older code and are kept only as a record; some have since been fixed, and the status of each
is stated in Part A where it still matters.

---

## Part A — Current findings (2026-11-12, second, third and fourth pass)

Method: the source tree on this branch, every migration, the three Edge Function sources, the CI
workflows, and — because the audit environment has no route to `supabase.co` — a **real PostgreSQL 16
instance** loaded with the Supabase platform stub (`supabase/tests/local/`), all migrations, and both
authorisation suites. Android compilation and unit tests were executed by GitHub Actions on this
branch, not locally (see A.5).

### A.1 Findings by severity

| ID | Severity | Status | Finding |
| --- | --- | --- | --- |
| S-1 | **High** | **Fixed** (`20261112035022`) | Every `mm_*` function was EXECUTE-able by `anon`, and `mm_claim_prekey_bundle` returned a one-time pre-key to an anonymous caller because its ownership check evaluated to NULL when `auth.uid()` was null. |
| S-2 | **High** (raised from Medium) | **Fixed** (`20261112070000`) | `mm_is_member`, `mm_role`, `mm_is_admin`, `mm_can_post`, `mm_shares_conversation`, `mm_is_blocked` and `mm_is_call_participant` accepted arbitrary identifiers and were executable by every signed-in user, so any account could enumerate who shares a conversation with whom, group roles, posting rights, block relationships and call participation through PostgREST — without touching a message. Severity raised to High: this is a social-graph disclosure primitive that affects every user, not a theoretical leak. Fix: implementations in the non-exposed `mm_private` schema + caller-scoped public wrappers, with a dedicated negative suite. The suite fails on a pre-fix database at the first enumeration assertion and passes afterwards. |
| S-3 | Low | **Fixed** (`supabase/config.toml`) | The three Edge Functions were deployed with `verify_jwt = false`; the gateway accepted anonymous requests and only the in-body `auth.getUser()` stood in the way. Now `verify_jwt = true` in the repository's function configuration, with the in-body authorisation kept as the second layer. |
| S-4 | **Medium** | **Fixed** (`_shared/rate_limit.ts`, `20261112070000`) | The rate-limit blocks in `mm-push-dispatch` and `mm-turn-credentials` wrote into `mm_rate_limits` with columns that do not exist (`user_id`, `bucket`, `count` vs `subject`, `action`, `window_start`, `hits`) and discarded the error, so **neither function was throttled** despite documenting a limit. Replaced with an atomic `mm_consume_rate_limit_as()` (service-role only, allow-listed actions) that fails closed; `mm-account-delete` gains a limit too. |
| D-1 | Medium | **Fixed** (earlier pass) | Documentation drift (README/ARCHITECTURE/SUPABASE/OWNER_SETUP vs the code). |
| D-2 | Low | **Fixed** (earlier pass) | Release signing secret names did not match `release.yml`. |
| T-1 | Medium | **Partly fixed** | 15 JVM test classes run in CI, and the first **Android instrumentation tests** now run in CI on a KVM-accelerated emulator: the SQLCipher database file is asserted to have no SQLite header and no plaintext canary, the Keystore-wrapped passphrase survives close/reopen, and the schema contains the expected tables. The fourth pass added the mesh protocol suite (several complete device stacks with real Signal sessions and a simulated radio), the search queries against a real SQLite, and attachment forwarding (A.7). Still missing: anything that drives the UI, notifications, the online sync path against a server, and Room migrations on a device. |
| T-2 | Medium | **Open** | No physical two-device validation of the current build. Requires the owner's phones; the validation pack is in [`TWO_DEVICE_VALIDATION.md`](TWO_DEVICE_VALIDATION.md). |
| T-3 | Low | **Fixed** | No automated check that the English and Persian resource files cover the same keys, and no check that every declared dependency has a licence entry. Both are now CI steps (`tools/check_string_parity.sh`, `tools/license_inventory.py`). |
| B-1 | Medium | **Resolved for this milestone** | Android compilation, unit tests, lint, debug/demo packaging and the Room schema check cannot run in the audit sandbox (no JDK, no Android SDK, no Maven/Gradle network). They run on GitHub Actions; this branch's `CI` run is the build evidence, and the backend work was verified locally against real PostgreSQL. |
| L-1 | Medium | **Analysed, owner decision open** | `libsignal-android` is AGPL-3.0 and is linked into the APK. `THIRD_PARTY_NOTICES.md` now contains the full inventory, the obligations, and the decisions the owner must take. No licence was chosen on the owner's behalf. |
| L-2 | Medium | **Fixed** (earlier pass) | The in-app licences screen exists (`presentation/screens/LicencesScreen.kt`, asset built by `tools/build_licence_asset.py`). An earlier revision of this table still listed it as missing. |
| W-1 … W-19 | **High** (several) | **Fixed in the repository, not yet applied to the live project** | Third pass: the write path. A signed-in client could skip the RPCs and write rows directly. See [A.6](#a6-third-pass-2026-11-12-the-write-path). |
| E-1 | **High, functional** | **Fixed in the repository, not yet redeployed** | `mm-push-dispatch` refused every message wake-up because it selected a column that does not exist. See A.6. |
| M-1 … M-9 | **High** (M-1 … M-3) | **Fixed**, M-6 partly | Fourth pass: the offline mesh trusted its clear packet header. Forged receipts, messages moved between chats by a relay, a path traversal in file receive, and several delivery defects. See [A.7](#a7-fourth-pass-2026-11-12-the-android-client-and-the-rest-of-the-backend). |
| R-1 … R-3, FP-1, I-1, BG-1 … BG-4 | **High** (R-2, R-3), Medium | **Fixed** | Fourth pass: local search failed for every user with a contact; forwarding an attachment sent an unreadable file and destroyed the original; edits and deletes that the server refused looked successful; any app could answer a ringing call; background delivery defects. See A.7. |
| P-1 … P-3, Q-1, E-2, E-3 | **High** (P-1, P-2) | **Fixed in the repository, not yet applied to the live project** | Fourth pass: anybody could read any profile (including presence) by adding its id to their own contact list; joining a public channel exposed every subscriber's profile and device keys; push dispatch ignored failed lookups; account deletion left media behind. See A.7. |
| F-1 | — | Not implemented | Group calls (need an SFU), stories, polls, scheduled/silent messages, bots and Mini Apps, search filters, pinned messages, disappearing messages, chat folders. Listed honestly in `docs/ROADMAP.md`. |

### A.2 Verified as correct (no change needed)

* Release signing refuses to publish a debug-signed artefact: `release.yml` requires all four
  secrets and rejects a certificate whose subject is `CN=Android Debug`.
* The release build is R8-minified; the demo build is a release-type build, so R8 runs on every push.
* The Room schema is exported (`app/schemas`) and CI fails on schema drift.
* All 25 `mm_*` tables have RLS enabled; only `mm_rate_limits` and `mm_push_log` have no policy, and
  both are internal bookkeeping (deny-all for API roles). This is now asserted, not assumed.
* The `mm-account-delete`, `mm-turn-credentials` and `mm-push-dispatch` sources validate the caller
  before acting; the service-role key is read only from the function environment and never shipped.
* `mm-claim_prekey_bundle` is single-use and refuses anonymous callers.
* Group member removal rotates `sender_key_epoch` and `distribution_id`; device revocation rotates
  the group keys and revokes push tokens (asserted in `rls_policies.sql`).
* Call state is terminal **through the RPC**: an ended call cannot be resurrected by a late
  `active` update. (The third pass found that the row could still be rewritten directly; see W-17.)
* Client-side media encryption is streaming AES-256-GCM with a per-file key; the bucket is private.

### A.3 What was changed in this milestone

* `supabase/migrations/20261112070000_mm_social_graph_authz.sql` — S-2 fix plus the service-role
  rate limiter and counter pruning.
* `supabase/tests/rls_social_graph.sql` — negative/positive regression suite for S-2.
* `supabase/tests/local/*` — disposable-database harness + Supabase platform stub.
* `.github/workflows/backend.yml` — migrations, both suites, idempotency, Deno type-check, licence
  inventory, secret scan.
* `supabase/config.toml` — exposed schema list (`public` only) and `verify_jwt = true`.
* `supabase/functions/_shared/rate_limit.ts` + edits to the three functions — S-3/S-4.
* `tools/check_no_secrets.sh`, `tools/check_string_parity.sh`, `tools/license_inventory.py`.
* Chat UX: archive, delete-for-me, forward, saved messages (+16 strings, both locales).
* Documentation: `README.md`, `docs/AUDIT.md`, `docs/SECURITY.md`, `docs/SUPABASE.md`,
  `docs/RELEASE.md`, `docs/ROADMAP.md`, `docs/CHANGELOG.md`, `THIRD_PARTY_NOTICES.md`.

### A.4 Remaining work, in priority order

0. **Apply migrations `20261112070000`, `20261112120000` and `20261113090000` to the live project
   and redeploy the three Edge Functions** (owner action, `docs/OWNER_SETUP.md` §4). Until then the
   live database still has every weakness listed in S-2, A.6 and the backend part of A.7.
1. T-2: two-device validation of online media, sync, calls and mesh↔online switching, **and of mesh
   protocol 2** (Suite I), which has only ever run on an emulator with the radio replaced.
2. L-1: the owner's licence decision (the in-app licences screen, L-2, is done).
3. T-1: the remaining instrumentation coverage (navigation, notifications, Room migrations);
   the storage and Keystore cases are already in CI.
4. F-1: product features, in the order given in `docs/ROADMAP.md` (search filters, pinned messages,
   scheduled/silent messages, disappearing messages, chat folders, then polls/stories).
5. Group calls once an SFU is provisioned; the 1:1 path is not a substitute.

### A.5 Limits of this audit

* The sandbox has **no JDK, no Android SDK, no Docker and no route to Maven/Google/`supabase.co`**.
  Anything Android is therefore verified by GitHub Actions on this branch, and anything about the
  live project is verified against a local PostgreSQL reproduction. The second pass ran no query
  against the live project; the third pass (A.6) read its migration history, two catalog queries,
  the Edge Function list and one function body through the Supabase connector, and wrote nothing.
* The new migration has **not been pushed to the live project**. It is additive and data-preserving,
  and it must be applied with `supabase db push` (or the SQL editor) by the owner — see
  `docs/SUPABASE.md`.
* The Edge Functions have not been redeployed; until they are, the live deployment keeps the old
  rate-limit behaviour and `verify_jwt = false`. `docs/OWNER_SETUP.md` lists the deploy command.
* Security review is internal. No third-party cryptographic or application-security audit exists.

### A.6 Third pass (2026-11-12): the write path

**Question asked:** the earlier passes checked what a user can *read* and which functions `anon`
can call. This pass asked what a signed-in user with a modified client can *write* when it ignores
the app and talks to PostgREST directly.

**Method.** Every finding below was reproduced before it was fixed: a disposable PostgreSQL 16 with
the Supabase stub and all earlier migrations, statements executed as the `authenticated` role with
forged-but-valid JWT claims for four test users. The same statements are now assertions in
`supabase/tests/rls_write_path_hardening.sql`, which also replays the statements the Android client
issues (including PostgREST's `ON CONFLICT` forms) to prove the fix does not break the app.

**Root cause.** The tables carried the platform default `GRANT ALL … TO authenticated`, and several
RLS policies checked *who* was writing but not *what* was written. The security-definer RPCs
enforced the real rules — block lists, the device cap, sender-key rotation, terminal call states,
rate limits — but nothing forced a client to use them.

| ID | Severity | What a signed-in user could do (reproduced) | Fix (migration `20261112120000`) |
| --- | --- | --- | --- |
| W-1 | **High, functional** | Nothing malicious: the `kind` check on `mm_messages` rejected `image`, `video`, `audio` and `text-edit`, which are exactly what the app sends for photos, videos, audio files and edits. Those uploads failed with a constraint violation; only `text`, `file` and `voice` were accepted. | Constraint lists the kinds the client really uses. |
| W-2 | Medium, functional | `mm_set_call_state(uuid,text)` and `mm_set_call_state(uuid,text,text default null)` coexisted. A two-argument call is ambiguous in SQL (reproduced: "function … is not unique"); PostgREST refuses to choose between such overloads, so answering or declining without an end reason is expected to fail. Not reproduced through a real PostgREST (none in the sandbox). | Two-argument overload dropped. A call can also no longer be moved back to `ringing`, and the end reason is bounded. |
| W-3 | **High** | Revoking a lost phone did nothing lasting: the revoked device called `mm_register_device` again, which cleared `revoked_at`, and carried on reading and sending. | The auth session that registers a device is recorded; revocation tombstones it (`mm_revoked_sessions`). A tombstoned session cannot register any device, read conversations, messages or envelopes, post, or revoke the owner's other devices (`device_revoked`, P0019). A fresh sign-in may link the same installation again, which counts against the device cap and re-keys the owner's groups. |
| W-4 | **High** | `mm_revoke_device(<any device uuid>)` only scoped its `UPDATE` to the caller; the `DELETE`s that followed removed **another user's** one-time pre-keys and undelivered envelopes. Any user could silently destroy anyone's pending messages. | Ownership is checked first; nothing happens for a device the caller does not own. |
| W-5 | Medium | `mm_rotate_user_groups(<any user>)` was callable by everyone: forced re-keying of every group a victim belongs to, on demand. | Internal only (no API-role EXECUTE). |
| W-6 | Medium | `mm_consume_rate_limit(action, max, window)` let the client choose the action name and its limits: arbitrary rows in `mm_rate_limits` (a 100 kB action name was accepted) and pre-spending of real limits. | Internal only. |
| W-7 | Medium | No cap on one-time pre-keys: 2 001 rows of 100 kB each were inserted directly in one request. | Pre-keys are written through the RPCs only; at most 200 unclaimed per device, with a size check on the key. |
| W-8 | Low | `mm_start_direct_conversation` accepted a peer id that does not exist and had no limiter. | Peer must have a profile; limited. |
| W-9 | Medium | `mm_set_read(my conversation, <message of a conversation I am not in>)` wrote a read receipt on the foreign message. | The message must belong to the stated conversation. |
| W-10 | **High** | Direct writes to `mm_conversations` / `mm_conversation_members`: (a) insert a `direct` conversation with a chosen `direct_key` and thereby squat the pair of two *other* users; (b) as owner of a direct chat, add a third member — a silent eavesdropper on new messages; (c) as admin, add anyone, skipping the block list, the member cap and the limiter; (d) delete a membership **without** the sender-key rotation, leaving the removed member with a valid group key; (e) rewrite `owner_id`, `type`, `encryption_version`, `sender_key_epoch`. | No direct INSERT/DELETE on either table and no UPDATE on `mm_conversations`; membership changes go through the RPCs, which rotate keys. |
| W-11 | Low | `mm_search_users('__')` and `'%%'` matched every profile (LIKE wildcards were passed through), an enumeration primitive. | Wildcards are escaped; a literal prefix search still works. |
| W-12 | Low | Reports could be inserted directly, unthrottled, about targets the reporter cannot see. | RPC only; the target must be visible to the reporter; an open report about the same target is updated instead of duplicated; limited. |
| W-13 | **High** | A member could insert rows into `mm_messages` with `kind = 'system'` (forged "X left the group"-style notices), a back-dated `sent_at`, another user's device id, a `reply_to` or `attachment_id` from a different conversation, and — the serious one — a `text-edit` targeting **somebody else's** message, which recipients' devices applied without checking the author. | A guard trigger for client inserts: allow-listed kinds, server-assigned `sent_at`, clamped client timestamp, own live device, same-conversation reply and attachment, author-only edits, bounded `meta`. `sent_at`, `edited_at`, `deleted_at` are not insertable. The Android client independently refuses an edit that does not come from the author (`EditPolicy`). |
| W-14 | Medium | Unlimited uploads into the private media bucket (cost and storage exhaustion); object names of any shape. | Per-user quota (5 GiB / 20 000 objects) and a strict `<conversation>/<object>` name in the storage INSERT policy; attachment records must point into their own conversation prefix and the Messenger bucket. |
| W-15 | Low | A group admin could change another member's mute / notification settings. | Only the member's own row, and only the three personal columns. |
| W-16 | Medium | A direct INSERT into `mm_devices` skipped the five-device cap and the limiter; a direct UPDATE could un-revoke a device or swap its identity key. | Devices are written through `mm_register_device` / `mm_revoke_device` only. |
| W-17 | Medium | Any member of the conversation could UPDATE `mm_calls` / `mm_call_participants` directly: resurrect an ended call, change the initiator, mark the other side as declined. | Read-only for clients; the state machine lives in `mm_set_call_state`. |
| W-18 | Medium | The *sender* of a contact request could set it to `accepted`; a blocked user could still send requests. | Only the recipient may answer; block list enforced on insert. |
| W-19 | Medium | Envelopes: `recipient_device` was not checked against `recipient_user_id`, and `sender_device` could name anybody's device. | Recipient device must be a live device of the stated recipient, who must be a member; `sender_device` must be the caller's. Envelopes, sender-key distributions and attachments are immutable. |

Also closed in the same change: typing indicators could be written with an arbitrary expiry (a
one-year "typing…" was accepted, including from a blocked user); call-signal payloads were
unbounded; a profile owner could update any column of their profile row.

**Defects in the Android client found by replaying its requests**

| ID | Finding | Fix |
| --- | --- | --- |
| C-1 | Retried uploads of envelopes, sender-key distributions and attachment records used `merge-duplicates`, i.e. `ON CONFLICT DO UPDATE`, which the database refused (there never was an UPDATE policy). A retry after a dropped connection therefore failed instead of being a no-op. | Idempotent inserts (`ignore-duplicates`). |
| C-2 | Editing a message PATCHed `kind = 'text-edited'`, a column that was never updatable and a value the constraint rejects, so the "edited" marker never reached the server. | Only `edited_at` is patched; the text travels as the encrypted `text-edit` message. |
| C-3 | Hang-up was signalled with kind `hangup`, which `mm_call_signals_kind_check` rejects; the peer only learned about the end of the call from the call-state row. | Sent as `bye` (the kind the backend also accepts for already-ended calls); `hangup` is still understood on receipt. |
| C-4 | An incoming `text-edit` was applied to whichever local message its `target` named, without checking that the editor wrote it. | `EditPolicy`: same device, or another device known to belong to the same account; never an outgoing, deleted or non-text message; same conversation. Unit-tested. |

**Defect in an Edge Function**

| ID | Severity | Finding | Fix |
| --- | --- | --- | --- |
| E-1 | **High, functional** | `mm-push-dispatch` looked the message up with `select("id, sender_id, created_at, deleted_at")`. `mm_messages` has no `created_at` column (it is `sent_at`), so the query failed, the code read the failure as "no such message" and answered **403 `message_not_owned_by_caller` to every message wake-up**. Push for calls was unaffected (different table). This is the same class of bug as S-4: a string-typed column name, an ignored `error`. Confirmed against the live project: the deployed function (version 1) contains the same select and the live table has no such column. | Column corrected; every lookup now distinguishes a failed query (500 `lookup_failed`) from a missing row; a repeated dispatch for one message no longer inserts duplicate notification rows. New CI gate `tools/check_edge_function_schema.py` compares every table, column and RPC argument named in the functions with the migrated database — it fails on the old source at exactly this line. |

**Verification**

* Backend: `supabase/tests/local/run.sh` — stub, eight migrations, three suites, the Edge Function
  schema contract, migrations re-applied (idempotency). Run locally on PostgreSQL 16.2 and by the `Backend` workflow on
  `postgres:16`. The new suite has 106 assertions, 51 of them refusals; for the 37 that accept
  "any error or zero rows" the *reason* for each refusal was inspected once (permission, policy or
  the intended error), so that none passes because of a typo in the test.
* Android: compiled, unit-tested and linted by the `CI` workflow on this branch; nothing Android
  can be built in the audit sandbox.
* **Not verified:** behaviour against the real Supabase platform (PostgREST overload resolution,
  the `session_id` claim in real access tokens, Storage's own use of `storage.objects.owner` /
  `owner_id`). The stub models these; the two-device pack in `TWO_DEVICE_VALIDATION.md` is where
  they get exercised for real.

**Deployment (owner action — nothing was applied to the live project)**

The live project `lnhmbjqjfrnqkpsjpszb` was only *read* in this pass (`list_migrations`, two catalog
queries, `list_edge_functions`, `get_edge_function` for `mm-push-dispatch`). It has six migrations applied, through `20261112035022`. Still to
apply, in order: `20261112070000_mm_social_graph_authz.sql`, then
`20261112120000_mm_write_path_hardening.sql`; then redeploy the three `mm-*` Edge Functions (they
are at version 1 with `verify_jwt = false`). Both migrations are idempotent, touch only `mm_*`
objects and the two `mm-*` bucket policies, and delete no data. The core tables were empty when
checked. The same read confirmed, on the live database, the state this pass describes: the `kind`
constraint without `image`/`video`/`audio`/`text-edit`, two `mm_set_call_state` overloads,
`authenticated` able to INSERT into `mm_conversation_members`, and **zero rows in `mm_profiles`
and `mm_messages`** — nobody has an account yet, so the rollout cannot disturb existing users.
Either order of client and database rollout is safe: an old client against the new
database fails in the same places it already failed (C-1, C-2), and the new client against the old
database is no worse than the old one.

**Left open on purpose**

* **Profile visibility through contacts.** Adding any user id as a contact makes that profile
  readable (display name, username, avatar, bio). The id has to be known first and the same fields
  are returned by username search, so this is Low; changing it means deciding whether contacts
  require consent, which is a product decision.
  *Reassessed in the fourth pass:* the readable row also carried `last_seen_at`, which username
  search does not return, so this was a presence tracker and not Low. Fixed as P-1 (A.7).
* **Quota cost.** `mm_private.media_quota_ok` sums the caller's objects in `storage.objects` on
  every upload. That table has no index on the owner columns and belongs to the platform, so the
  check is linear in the size of the bucket. Fine at the current scale; needs a counter table
  before the bucket holds millions of objects. *Done in the fourth pass as Q-1 (A.7).*
* **Error codes.** The schema's custom SQLSTATEs start at `P0001`, which collide with PL/pgSQL's
  own (`P0002` no_data_found, `P0004` assert_failure). PostgREST passes them through unchanged, so
  clients are unaffected, but inside PL/pgSQL `WHEN OTHERS` does not catch `P0004`
  ("device_not_found"). Renumbering would change the client contract; not done.
* **Session tombstones** are keyed on the `session_id` claim. A token without that claim (custom
  JWTs) cannot be tombstoned; such a caller can still not re-activate a revoked device.

### A.7 Fourth pass (2026-11-12): the Android client and the rest of the backend

**Question asked.** The earlier passes were about the server. This one read the Android client as an
attacker in radio range, a malicious contact, another app on the same phone, and an ordinary user
whose network fails at the wrong moment would meet it - and went back to the backend for what a
signed-in stranger can *read*.

**What was read, and what was not.** Read completely: the mesh layer (`core/mesh/*`,
`ChatRepository` send/receive/queue paths), `FileRepository`, `ContactRepository`, the services, the
workers, the push path, launch-intent handling, the manifest, the DAOs, the release workflow, all
migrations and the three Edge Functions. Read in part: `OnlineSync`/`OnlineApi` (the write paths and
the push wake-up, not every sync branch), `CryptoManager` and `SignalStore`. **Not read:** most of
`presentation/screens/*` and the navigation graph, `PreKeyBundleCodec`, `SecretStore`,
`MediaTransferWorker`, `WebRtcCallManager`. Findings in unread code are therefore still possible,
and the absence of findings in the cryptographic code is not an audit of it.

**How each finding is known.** "Emulator" means a test in `app/src/androidTest` that carries out
the attack or the failure against the fixed code and runs in CI (the tests were written after the
fixes; they were not run against the old code); "PostgreSQL" means the backend suite; "SQLite" means
reproduced with the same statement on a stock SQLite; "read" means established from the source
only, fixed, compiled by CI, and **not** exercised.

#### Offline mesh

| ID | Severity | Finding | Fixed by | Known from |
| --- | --- | --- | --- | --- |
| M-1 | **High** | A delivery receipt was a clear header naming a message id, accepted from any device in range. One forged frame showed "delivered" and made the sender delete the ciphertext queued for the real recipient: silent message loss on demand. | Receipts are encrypted packets, accepted only from the device the queued packet was for. | Emulator (`aReceiptCannotBeForged`) |
| M-2 | **High** | Group id, group name, message id, timestamp and attachment metadata were read from the clear header. A relay could move a message into another chat, rename a group, rewrite the time or the message id. A contact could write into the direct chat of two other people, because a direct chat's id is the peer's device id and a "group" packet with that id was accepted. | All of it travels inside the Signal ciphertext (`SealedPayload`) and the header is only compared against it. Group packets need a stored mesh group and a member as sender. | Emulator (four cases) |
| M-3 | **High** | The transfer id of an incoming file became a directory name unchecked: `../../files/x` wrote outside the staging directory, inside the app's private storage. Chunk index, count and size were unchecked; a transfer could be completed by chunks from a different contact. | Ids validated at the call site and again where the path is built; metadata must describe one consistent transfer; a transfer is bound to its conversation and message. | Emulator (two cases) |
| M-4 | Medium, functional | A packet was removed from the queue once written to a link. If the link dropped a moment later it was gone and the message stayed "sent" for ever. Receipts were not relayed, so a relayed message could never become "delivered". Store-and-forward expired after seven minutes. | Packets stay queued until confirmed and are repeated; repeats are re-acknowledged; receipts are relayed; expiry is 24 hours. | Emulator (lost receipt, absent device, relay) |
| M-5 | Medium, functional | The queue held 600 packets; a file above about 19 MB for an absent recipient was cut off without a word. The retry pass always took the same 200 oldest packets, so one absent device starved everybody else. | 2,000 packets, least-tried first, a visible failure when a file cannot fit. | JVM tests; read |
| M-6 | Medium | A HELLO with a known device id and a different identity key replaced that device's route. | Refused. **Partly fixed:** a HELLO is still not a proof of possession, see below. | Read |
| M-7 | Medium, functional | Only the creator of a mesh group could write in it: the others never learned the member list. | The sealed header carries it; membership rules in `docs/SECURITY.md` 5.1. | Emulator |
| M-8 | Low | Every retry re-encrypted a text that was still queued, advancing the ratchet each time. | The queued ciphertext is re-sent. | Read |
| M-9 | Low | Retrying a failed mesh-only attachment scheduled an online upload that could not happen. | Re-sent over the mesh. | Read |

The protocol change is incompatible: version 1 and version 2 devices ignore each other on the mesh.

#### Messages, search, files

| ID | Severity | Finding | Fixed by | Known from |
| --- | --- | --- | --- | --- |
| R-1 | Medium | Edit and delete-for-everyone changed the local copy and ignored the server. A delete after 24 hours is filtered out by row-level security *without an error*, so the sender saw the message disappear while everybody else kept it. Reaction toggling compared against the emoji's total count. Deleting an online group ignored a failed leave. | The server is asked first; zero changed rows is a failure; the user is told. | Read; the server-side rule is covered by the PostgreSQL suite |
| R-2 | **High** | Forwarding an attachment passed the stored file - sealed with a key that never leaves this phone - to functions that expect plaintext and delete their input. The recipient got a file nobody can open, and the forwarded message lost its attachment. | A decrypted temporary copy is sent. | Emulator (`forwardingAnAttachmentSendsTheFileItselfAndKeepsTheOriginal`) |
| R-3 | **High**, functional | The contact search query declared a two-character `ESCAPE`. Room accepts that at build time; SQLite rejects it when it compares the first row. Every local search threw as soon as one contact existed, inside a coroutine without a handler. The message and chat queries had no `ESCAPE` at all, so `%` and `_` were never found. | One-character escape everywhere, one pattern builder, a handler in the view model. | SQLite; emulator (`LocalSearchInstrumentedTest`) |
| FP-1 | Medium | `FileProvider` covered the whole cache directory, which also holds decrypted temporaries. | Only `cache/share/`. | Read |

R-3 deserves a sentence of its own: by the code, **search has not worked for any user with a contact
since it was written**, and nothing in CI could have noticed. It is the clearest argument in this
repository for the two-device pass - a person would have found it in a minute.

#### Other apps, background work

| ID | Severity | Finding | Fixed by | Known from |
| --- | --- | --- | --- | --- |
| I-1 | Medium | The launcher activity is exported, and the extras that answer a ringing call were honoured from any sender: another app on the phone could answer a ringing call. Conversation and call ids from intents and `offlinemesh://` links were used unvalidated. | Answering needs a per-installation token that only this app's own notification carries; ids are validated (`LaunchIntentPolicy`). | JVM test of the policy; the Android wiring is read only |
| BG-1 | Medium | A push wake-up was recorded as handled before it was handled. If the phone had no session or no network at that moment, the server's repeat was discarded as a duplicate for ten minutes. | Forgotten again when handling fails. | JVM test |
| BG-2 | Low | `OutboxWorker` reported success whatever happened. | Retries while the outbox is not empty. | Read |
| BG-3 | Low | `SyncWorker` swallowed the errors of every step but one. | Each step runs and is reported. | Read |
| BG-4 | Medium | No workflow passed the Firebase client options to Gradle. Every APK built by CI - the demo build and the signed release - was built without push, whatever the owner configured. | Workflows read four repository secrets (`docs/OWNER_SETUP.md` 1B). **They are not set yet.** | Read |

#### Backend (migration `20261113090000`, Edge Functions)

| ID | Severity | Finding | Fixed by | Known from |
| --- | --- | --- | --- | --- |
| P-1 | **High** | A profile row was readable by anyone who put its id into *their own* contact list or sent it a contact request. The row included `last_seen_at`: a presence tracker for arbitrary accounts that the target could not see or stop, block list ignored. | A contact counts only when mutual; a block ends it; `last_seen_at` is not selectable. | PostgreSQL |
| P-2 | **High** | "Shares a conversation" included public channels. Subscribing to one exposed every other subscriber's profile, device list (identity keys, device names, platform) and membership. | In a channel subscribers see the staff and the staff see subscribers; subscribers do not see each other. | PostgreSQL |
| P-3 | Low | Every member could read every other member's mute settings. | Column privileges; `mm_my_memberships()` for one's own. | PostgreSQL |
| Q-1 | Low | The media quota summed the bucket on every upload. | Per-account counters maintained by triggers scoped to `mm-media`. | PostgreSQL |
| E-2 | Medium | `mm-push-dispatch` treated a failed lookup as an empty result: when the block or preference query failed, blocked and muted users were notified anyway. Large recipient lists exceeded the URL length and were silently dropped. | Fails closed; lists are sliced. | Read; Deno type-check; column check against the schema |
| E-3 | Medium | `mm-account-delete` took the list of media from the attachment rows and ignored every error on the way. An upload whose row was never written, anything beyond the first page of rows, or a failed storage call left ciphertext in the bucket while the account's rows were deleted - after which nothing pointed at it. | Media is listed by storage ownership (`mm_media_objects_of`) and removed in batches first; any failure stops before a row is deleted. | Read; Deno type-check |

The same migration adds what the write-path hardening had made impossible - **administration of
groups and channels** (G-1: rename, roles, ownership transfer, invite rotation, bans) - and two
server capabilities, **message timers** (DM-1) and **pinned messages**. They are specified by the
suite and have no client.

#### Not done

* **HELLO is not authenticated (rest of M-6).** A device that replays another device's genuine
  HELLO is given its route until the real one connects. It cannot read or forge anything and
  nothing is lost (packets stay queued until the real device confirms them), but it can delay
  delivery. The fix is a signed challenge in the handshake - another protocol change.
* **No client for timers, pins, administration.** In particular the app does not delete its local
  copy when a message expires. The server side alone is not "disappearing messages" and must not be
  presented as such.
* **Mute does not sync between a user's devices**; `mm_my_memberships()` exists for it and is unused.
* **Mesh groups have no administrator.** Any member can add members. Documented, not fixed.
* **Mesh metadata is in the clear** (who, to whom, when, how much). By design of a relayed mesh;
  documented in `docs/SECURITY.md` 5.1.
* **Unmeasured:** the battery cost of repeating unconfirmed packets every 15-20 seconds while a
  peer is connected.
* Smaller things seen and left: a malformed text payload is shown as an empty message; the pre-key
  in a HELLO is reused across handshakes; a corrupt stored session is replaced by a fresh one
  without telling the user.

#### What the tests do and do not show

`MeshProtocolInstrumentedTest` runs two to four complete device stacks - separate databases, real
libsignal identities and sessions, the real `ChatRepository` and file store - on an emulator, and
replaces only the radio with an in-process hub that can drop, rewrite and inject frames. The hub
routes with the same `FrameRouting` function as the real transport. That is strong evidence for the
protocol logic and none for Nearby Connections: discovery, real link loss, throughput, and behaviour
under Android's background limits are untested, as before.

The backend suites run against a stub of the Supabase platform on PostgreSQL 16. The new suite was
mutation-tested (eight weakenings of the migration, each detected). Nothing in this pass has run
against the live project, PostgREST, Storage or Realtime.

## Part B — Historical audit (2026-11-11, base `5880f98`)


Date: 2026-11-11 · Base commit: `5880f98` (`main`) · Branch: `arena/939f33dc-messenger`

This is the state the mission started from, established by reading the source, the Gradle
configuration, the CI workflow, the GitHub state (branches, PRs, runs, releases) and the
Supabase project, not by trusting the README.

## 1. Repository facts

| Item | Finding |
| --- | --- |
| `main` HEAD | `5880f98` "Merge pull request #2 from Pillow1243/arena/9699413f-messenger" |
| Branches | `main`, two merged `arena/*` branches |
| Tags / releases | none; a single `demo-latest` **prerelease** is force-updated by CI |
| PRs | #1 and #2, both merged |
| CI | one workflow, `Android CI`, last run green in 5m52s |
| Tests | 6 JVM test classes, all pure-logic (packet codec, routing, chunking, pairing, formatting) |
| Working tree | clean at start; all existing work preserved |
| Licensed code | `org.signal:libsignal-android` (AGPL-3.0), SQLCipher (BSD-style), Nearby (Google Play Services) |

Product state: a complete, honest **offline mesh** prototype — 1:1 and group chat over Nearby
Connections, Signal Protocol per peer device, SQLCipher at rest, store-and-forward relay,
QR pairing, EN + FA UI. No online mode, no accounts, no server.

## 2. Findings, by severity

### P0 — correctness / data integrity

1. **Destructive migrations.** `DatabaseFactory` builds Room with
   `fallbackToDestructiveMigration()` while `exportSchema = false`. Any schema change silently
   wipes every message, contact and key on upgrade. There is no migration and no test.
2. **Database loss is a normal code path.** If the KeyStore-wrapped passphrase cannot be
   unwrapped, the code *deletes the encrypted database* and starts over. A transient Keystore
   error (key invalidation, backup restore) destroys the user's history without consent.
3. **No delivery guarantee across process death for outgoing files.** `dispatchFile` streams
   chunks from a plaintext cache file and deletes it in `finally`; if the process dies mid-loop
   the sealed attachment remains but the chunks that were never queued are gone, and the message
   is marked `SENT`/`QUEUED` without any resume logic. A partial transfer can never complete.
4. **Receiver-side transfers are not idempotent per chunk index.** `onChunk` counts a chunk as
   new when the file is absent, but duplicate chunks after a partial assemble, and chunk index
   gaps, are not validated — a truncated file can be "assembled" and sealed as complete.
   `FileChunker.assemble` only checks existence of `chunk_N.bin`, not its length or the total
   size, and no hash is verified.
5. **`retryPending()` can rewrite message state.** It re-dispatches every `QUEUED` outgoing text
   message with `replaceQueued = true`, which deletes relay rows for the new packet ids only —
   previously handed-off ciphertexts stay queued and are re-sent under *new* packet ids,
   duplicating delivery work; on the receiving side the `msgId` guard saves correctness, but the
   relay table grows and the mesh floods.

### P1 — security

6. **Release artefacts are signed with the public Android debug key**, and CI republishes
   `demo-latest` from *any* branch push. Anyone can forge an update for the published package.
7. **CI installs `android-34` with `|| true`**, so a broken SDK step is silently ignored, and the
   workflow has `contents: write` for all events (including `pull_request`).
8. **Lint is disabled** (`abortOnError = false`, `checkReleaseBuilds = false`), so static analysis
   cannot fail the build.
9. **Rate limiting / abuse controls do not exist** — irrelevant for a serverless app, but a hard
   blocker for the online mode that was requested.
10. **No database backup exclusion for key material review:** `allowBackup=false` and
    `dataExtractionRules` are correct; noted as verified, not a finding.
11. **Identity change handling is correct but invisible in delivery:** a peer whose identity key
    changed is flagged in the contact row, yet queued ciphertext keeps retrying forever instead of
    surfacing an actionable error.
12. **Relay queue is unbounded in age** (bounded at 600 rows) and `onRelay` accepts packets from
    any peer without verifying that the packet's `from` shares a conversation with the target —
    a malicious neighbour can use the device as free storage for arbitrary ciphertext.

### P2 — architecture / maintainability

13. **Single `:app` module, 34 files**, with `ChatRepository` (616 lines) mixing encryption,
    transport framing, persistence, file transfer and relay policy.
14. **Global mutable singletons** (`DatabaseFactory`, `KeystoreHelper`) and a `lateinit` service
    locator on `Application`; no interfaces between layers, so nothing is unit-testable.
15. **`AppViewModel` is a 242-line god object** doing settings, chats, contacts, pairing, files.
16. **Raw strings for message status/type** (`"QUEUED"`, `"TEXT"`) instead of sealed types.
17. **`SeenPacketCache` in memory only** for the process; the Room `seen_packets` table exists but
    is never used, so a restart forgets duplicates and can re-deliver.
18. **No `LocalesConfig` coverage for new strings** (must add every string to both locales).

### P3 — polish

19. Persian translations cover the existing UI, but the app hard-codes some English in
    `Format.kt`-adjacent strings (verified: locale-aware formatting is fine).
20. Storage and Keystore now have instrumented tests; screenshot tests, macrobenchmark and
    navigation/notification instrumentation tests do not exist yet.
21. `README` documents limitations honestly; several are now addressed.

## 3. What was missing entirely (requested by the mission)

* Accounts, authentication, sessions, device registration, account deletion.
* Any server, any online transport, any sync engine, presence, typing, receipts.
* Channels/communities, discovery, moderation, reporting.
* Database migrations and migration tests.
* Meaningful lint/static analysis, production signing, release channels.
* Tests beyond pure functions (no crypto workflow, no repository, no policy tests).

## 4. Decisions taken because of these findings

1. **Keep one Gradle module, enforce layers by package** (`core/*`, `network/*`, `domain/*`,
   `data/*`, `presentation/*`) with explicit construction in a single composition root, and
   document the split into modules as future work. Rationale: a 15-module build cannot be
   compiled in this environment (no local JDK/Android SDK/Maven access), so introducing it would
   trade real risk for cosmetic structure. Detail: `docs/ARCHITECTURE.md`.
2. **Reuse the existing Supabase project** instead of creating a new one: creating a third free
   project is blocked by the organisation's plan limit. The project already hosts another
   application's tables, so every Messenger object is namespaced with `mm_` and fully isolated
   by its own RLS. Detail: `docs/SUPABASE.md`.
3. **Server stores ciphertext only.** Messages are split into a metadata row
   (`mm_messages`) and one Signal ciphertext per recipient device (`mm_message_envelopes`).
   There is no server-side plaintext search, so search stays local.
4. **Preserve the mesh as a first-class transport.** Every online feature is additive; the app
   still works with the radio off and no account, and the mesh path keeps its own store-and-forward.
5. **Never ship a fake feature.** Call *signalling* (rings, state, SDP/ICE relay) is implemented,
   but the WebRTC media stack is not bundled — documented as a blocker, not faked.
