# Release engineering

How a Messenger build is produced, what is inside it, how it is verified, and what must be true
before it can be called a production release.

## 1. Channels

| Channel | Trigger | Signing | Where |
| --- | --- | --- | --- |
| **CI debug** | every push / PR (`ci.yml`) | public Android debug key, `.debug` application id | workflow artifact `messenger-apks` |
| **Demo** | push to `main` (`ci.yml` → `demo-release`) | public Android debug key, `.demo` application id | prerelease `demo-latest` |
| **Production** | `v*` tag (`release.yml`) | `MESSENGER_KEYSTORE_*` secrets only | release `<tag>` |

The debug key is public by definition (its password is `android`), so a debug-signed APK can be
re-signed by anybody and must never be presented as a trusted build. `release.yml` refuses to
publish if any signing secret is missing, and its final step rejects a certificate whose subject
is `CN=Android Debug`.

## 2. Producing a production release

```bash
# 1. version bump (single source of truth)
#    app/build.gradle.kts -> versionCode / versionName
# 2. changelog
#    docs/CHANGELOG.md
# 3. tag (annotated, signed by the owner)
git tag -a v3.2.0 -m "Messenger 3.2.0"
git push origin v3.2.0
```

`release.yml` then builds `assembleRelease`, writes `SHA256SUMS.txt` and `build-metadata.txt`,
verifies the signing certificate, and publishes the tag. If the signing secrets are absent the
workflow **skips** the release instead of publishing something unsigned or debug-signed.

Required repository secrets (names are the contract with `release.yml`):

| Secret | Meaning |
| --- | --- |
| `MESSENGER_KEYSTORE_BASE64` | base64 of the `.keystore` file |
| `MESSENGER_KEYSTORE_PASSWORD` | store password |
| `MESSENGER_KEY_ALIAS` | key alias inside the store |
| `MESSENGER_KEY_PASSWORD` | key password |

Optional build-time configuration (Supabase project, Firebase client ids, TURN) is documented in
[`OWNER_SETUP.md`](OWNER_SETUP.md). The Supabase **anon** key in `app/build.gradle.kts` is public by
design; the **service-role** key must never appear in a build.

## 3. Artefact verification

```bash
sha256sum -c SHA256SUMS.txt
apksigner verify --print-certs messenger-3.2.0-production.apk   # build-tools 34
unzip -l messenger-3.2.0-production.apk | grep -E 'libsignal|libwebrtc|libsqlcipher'
```

The signature block of a production APK must show the owner's key, never
`CN=Android Debug, O=Android, C=US`. The fingerprint of that certificate belongs in the release
notes (`apksigner verify --print-certs` prints the SHA-256 digest).

A release artefact also carries:

* `SHA256SUMS.txt` — checksums of every published file,
* `build-metadata.txt` — commit SHA, tag, version, build time, ABI list,
* `THIRD_PARTY_NOTICES.md` — the attribution that must accompany the binary (see
  [`../THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md)),
* `dependency-graph.txt` — the resolved runtime dependency tree of the release variant.

## 4. Build provenance

Every artefact is reproducible from a commit: the CI artifact names the commit SHA, the workflow
logs show the exact Gradle invocation, and `app/build.gradle.kts` pins plugin and library
versions. `build-metadata.txt` records the SHA, so an APK's origin can be traced without trusting
the download page.

## 5. Release-blocking checklist

- [ ] `Test, lint and package`, `Backend`, and `Export Room schema` are green on the commit.
- [ ] `docs/CHANGELOG.md` has an entry for the version.
- [ ] `THIRD_PARTY_NOTICES.md` covers every declared dependency (`python3 tools/license_inventory.py`).
- [ ] English/Persian string parity holds (`./tools/check_string_parity.sh`).
- [ ] No credential material in the tree (`./tools/check_no_secrets.sh`).
- [ ] The licence decision in `THIRD_PARTY_NOTICES.md` §3 is resolved; without it this is a demo
      build, not a production release.
- [ ] Two-device validation results for the build are recorded in
      [`TWO_DEVICE_VALIDATION.md`](TWO_DEVICE_VALIDATION.md).
