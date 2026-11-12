<!-- GENERATED FILE - DO NOT EDIT. Source: THIRD_PARTY_NOTICES.md (tools/build_licence_asset.py). -->
<!-- sha256:02f639b4f160c453c4eb43f4a84f7e6901c68f1d18ddf7e40694dcd77e324b18 -->

# Third-party notices and licence obligations

Messenger is not distributed under a single licence yet. This file does two things:

1. it is the **complete inventory of third-party components** compiled into the APK (and therefore
   the attribution notice that must ship with any binary), and
2. it states the **licence decision the owner still has to take**, with the technical facts that
   decision needs. No licence has been chosen on the owner's behalf, and no `LICENSE` file has been
   added that would claim one.

`tools/license_inventory.py` (run in CI) fails when a dependency declared in
`app/build.gradle.kts` has no entry here, so this list cannot silently drift.

## 1. Inventory

Versions are the ones pinned in `app/build.gradle.kts`.

### 1.1 Copyleft components — these define the distribution obligation

| Component | Version | Licence | Notes |
| --- | --- | --- | --- |
| `org.signal:libsignal-android` | 0.86.5 | **AGPL-3.0** | The Signal Protocol implementation (PQXDH, Double Ratchet, Sender Keys). Ships a Rust JNI library per ABI. This is the component that makes the combined work subject to the AGPL network-use clause. |
| `org.signal:libsignal-client` | 0.86.5 | **AGPL-3.0** | Test-scope only (`testRuntimeOnly`, desktop JNI), never packaged in an APK. |

### 1.2 Weak-copyleft / classpath-exception components

| Component | Version | Licence | Notes |
| --- | --- | --- | --- |
| `com.android.tools:desugar_jdk_libs` | 2.0.4 | **GPL-2.0 with Classpath Exception** | A subset of OpenJDK libraries desugared into the APK. The Classpath Exception removes the requirement to license the application under the GPL. Attribution is required. |

### 1.3 Permissive components (attribution required)

| Component | Version | Licence |
| --- | --- | --- |
| `androidx.compose.ui:ui` | BOM 2024.09.03 | Apache-2.0 |
| `androidx.compose.ui:ui-graphics` | BOM 2024.09.03 | Apache-2.0 |
| `androidx.compose.ui:ui-tooling` (debug only) | BOM 2024.09.03 | Apache-2.0 |
| `androidx.compose.ui:ui-tooling-preview` | BOM 2024.09.03 | Apache-2.0 |
| `androidx.compose.ui:ui-test-manifest` (debug/test only) | BOM 2024.09.03 | Apache-2.0 |
| `androidx.compose.material3:material3` | BOM 2024.09.03 | Apache-2.0 |
| `androidx.compose.material:material-icons-extended` | BOM 2024.09.03 | Apache-2.0 |
| `androidx.core:core-ktx` | 1.13.1 | Apache-2.0 |
| `androidx.appcompat:appcompat` | 1.7.0 | Apache-2.0 |
| `androidx.activity:activity-compose` | 1.9.2 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.8.4 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.8.4 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.8.4 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-process` | 2.8.4 | Apache-2.0 |
| `androidx.navigation:navigation-compose` | 2.7.7 | Apache-2.0 |
| `androidx.room:room-runtime` | 2.6.1 | Apache-2.0 |
| `androidx.room:room-ktx` | 2.6.1 | Apache-2.0 |
| `androidx.room:room-compiler` (KSP) | 2.6.1 | Apache-2.0 |
| `androidx.room:room-testing` (test only) | 2.6.1 | Apache-2.0 |
| `androidx.sqlite:sqlite` | 2.4.0 | Apache-2.0 |
| `androidx.work:work-runtime-ktx` | 2.9.0 | Apache-2.0 |
| `androidx.datastore:datastore-preferences` | 1.1.1 | Apache-2.0 |
| `androidx.exifinterface:exifinterface` | 1.3.7 | Apache-2.0 |
| `androidx.test.ext:junit` (test only) | 1.2.1 | Apache-2.0 |
| `androidx.test:runner` (test only) | 1.6.2 | Apache-2.0 |
| `net.zetetic:sqlcipher-android` | 4.5.7 | BSD-style (Zetetic LLC) for SQLCipher + Apache-2.0 for the Android support code + SQLite in the public domain. **The BSD notice and the "Copyright (c) 2008-2026, ZETETIC, LLC" line must be reproduced in a user-accessible place.** |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | Apache-2.0 |
| `com.squareup.okhttp3:mockwebserver` (test only) | 4.12.0 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.9.0 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` (test only) | 1.9.0 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.7.3 | Apache-2.0 |
| `com.google.zxing:core` | 3.5.3 | Apache-2.0 |
| `com.journeyapps:zxing-android-embedded` | 4.3.0 | Apache-2.0 |
| `io.getstream:stream-webrtc-android` | 1.3.10 | Apache-2.0 (wrapper); the bundled libwebrtc binaries are BSD-3-Clause |
| `junit:junit` (test only) | 4.13.2 | EPL-1.0 (test only, not packaged) |

### 1.4 Proprietary SDKs under Google terms

| Component | Version | Terms | Notes |
| --- | --- | --- | --- |
| `com.google.android.gms:play-services-nearby` | 19.3.0 | Google APIs Terms of Service + Android Software Development Kit License (Apache-2.0 for the AAR contents) | Closed-source binary redistribution is permitted only as part of an application built with the SDK; no separate licence grant. |
| `com.google.firebase:firebase-messaging` | 24.0.3 | Google APIs Terms of Service (Apache-2.0 for open components) | Same redistribution rule as above. |

### 1.5 Transitive dependencies

The table above lists the *declared* dependencies. Each pulls its own transitive tree (Kotlin
stdlib, AndroidX annotations, protobuf-lite, Okio, jspecify, …), all of which are Apache-2.0,
BSD or MIT. `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` in a build
environment prints the exact resolved set for a given release; the AGPL/Classpath-Exception
components listed in 1.1 and 1.2 are the only reciprocal licences in that tree, which was verified
by inspecting the POM `licenses` blocks of every declared dependency.

## 2. What the AGPL means for this project

`libsignal-android` is licensed **AGPL-3.0**. It is compiled into the APK
(`implementation("org.signal:libsignal-android:0.86.5")`), so the distributed binary is a work
based on an AGPL library. Facts that follow, stated without legal advice:

* **Source availability.** Anyone who distributes the APK (the owner, or a store) must make the
  Corresponding Source available to recipients — including any modifications to libsignal itself,
  of which there are none here (the library is used unmodified from Maven Central).
* **Network use (§13).** If the combined software is offered to users over a network, those users
  must be offered the Corresponding Source. A messaging backend is a network service; whether the
  *app* is "interacting with users remotely through a network" in the §13 sense is the crux of the
  analysis. The conservative reading is that it does, which means the source must be offered.
* **No additional restrictions.** The AGPL cannot be combined with terms that forbid what the AGPL
  permits, so a proprietary EULA for the app itself is not compatible while libsignal is linked in
  this way.
* **Attribution.** The AGPL and all permissive notices above must be reproduced, together with the
  SQLCipher BSD notice, in a user-accessible location (an in-app "Licences" screen or a page linked
  from the store listing / README).
* **Transport-layer alternative.** Replacing libsignal with a permissively licensed Signal Protocol
  implementation (or with an independently written protocol) would remove the AGPL obligation and is
  a *product* decision with cryptographic consequences. Design note only — nothing in this
  repository does that.

## 3. Owner decisions that remain (blocking, cannot be made in code)

1. **Choose the licence of the application code** (`LICENSE` file). The realistic options are:
   * release the app under **AGPL-3.0** — consistent with the linked libsignal, simplest and legal
     today;
   * dual-license / commercial, which requires a licence from Signal for the libsignal library
     (they offer no such licence for the open-source release) or a different protocol stack;
   * keep the repository unpublished as a service (AGPL §13 still applies to network users).
2. **Publish the Corresponding Source location** in the app and in the release notes (a link back
   to this repository is sufficient if it stays public).
3. ~~Add an in-app licences screen~~ — **done**. This file is shipped inside the APK as the asset
   `assets/licenses/THIRD_PARTY_NOTICES.md` and rendered by Settings → About →
   *Open-source licences* (`presentation/screens/LicencesScreen.kt`). The asset is generated by
   `tools/build_licence_asset.py`; the `In-app licence asset is current` CI gate fails when it
   drifts from this file, so the attribution bundled in a build is always the inventory of that
   build. What remains is the owner's choice in item 1, which cannot be made in code.

Until (1) and (2) are decided, no APK from this repository should be described as a production
release; the demo builds published by CI are development artefacts signed with a public debug key.
