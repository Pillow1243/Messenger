# OfflineMeshMessenger

> **Prototype only — not ready for installation, production use, or sensitive messages.** The project has not been successfully compiled or tested on devices. Its cryptographic implementation and mesh routing have not been validated. Do not rely on any security or delivery guarantees.

An in-progress Android application exploring offline nearby messaging with Kotlin, Jetpack Compose, Room, and Google Nearby Connections. The current implementation attempts to provide direct and group chats, QR contacts, attachment chunking, a queued outbox, and English/Persian UI. These features are not yet verified end to end.

## Architecture

- `presentation`: Compose screens, navigation, and ViewModels.
- `data`: repositories for chats, contacts, settings and files.
- `core/mesh`: Nearby Connections discovery and packet handling.
- `core/db`: Room entities and DAOs with a SQLCipher database factory.
- `core/crypto`: experimental identity, session and encryption code; requires substantial correction and an independent security review.
- `work` and `service`: background synchronization and foreground service scaffolding.

The project intends to use libsignal, Android Keystore and SQLCipher, but their integration is **not verified**. In particular, the current crypto code uses API names that do not match the declared Signal library and includes a non-Signal fallback; it must not be treated as a working or audited Signal Protocol implementation. Peer identity verification, key lifecycle, group security, message delivery, file handling and radio settings need review and device testing. The manifest currently permits internet access for an optional update checker; offline-only operation and the absence of network leaks have not been verified.

## Build

Requirements: JDK 17, Android SDK 34, and Android build tools, with network access for Gradle and dependencies. Run `./gradlew testDebugUnitTest assembleDebug assembleRelease`. The release build is unsigned unless signing is configured separately; it is not directly installable as a release APK. The debug build uses the standard debug key. The GitHub Actions workflow attempts these tasks and uploads APKs only if they succeed. No successful build or APK is currently available.

## Status and security

This repository is a work in progress, not a commercially deployable messenger. It has no completed security audit, automated integration tests, or verified two-device offline mesh tests. Android permissions, background restrictions, Nearby/Google Play services availability, key storage, attachment bounds, authentication and error handling all require further work. Never use this prototype to protect confidential information.
