# UNISON native Android app

This repository includes a native Android application module under `app/`. It uses the permanent application ID `com.unison`, a Telegram-inspired chat layout, and WebRTC data-channel transport for peer-to-peer text chat.

## Current state

The native module is an initial buildable UI/security foundation, not yet a complete messenger. It currently includes:
- Compose chat-list and conversation UI, message timestamps, custom one/double ticks, and message-info timestamps.
- WebRTC data-channel chat transport using Google WebRTC and a configurable WebSocket signaling URL.
- A separate Node.js signaling service in `signaling-server/`, with a Render deployment blueprint.
- A 32-character uppercase pairing-code generator with separators and a persistent Android Keystore EC identity fingerprint.
- An AES-256-GCM encrypted local file vault using an Android Keystore key.
- A foreground-service notification foundation.
- A custom original UNISON vector app mark.

Still incomplete: TURN relay configuration for difficult NATs, robust signaling reconnection and online presence, message delivery acknowledgements, persistent Room database/repository wiring, encrypted export/import to a user-selected Documents folder, a production-grade reviewed E2EE ratchet, file transfer, real voice/video media tracks, group membership/sync, and comprehensive notification/call integration. WebRTC connectivity has not been validated on physical devices in this environment. Demo chat rows are UI previews only; do not treat them as delivered messages. The existing web prototype and Python signaling code remain in the repository.

## Package and upgrades

- Keep `applicationId = "com.unison"` for all compatible updates.
- Keep the same signing certificate for every update. Changing package name or signing certificate causes Android's “App not installed as package conflicts with an existing package” error.
- The CI release workflow expects a persistent signing key in GitHub Actions repository secrets. Never commit a release keystore or its password to the repository.
- Android Keystore identity/storage keys persist through normal app updates. Android does not guarantee they survive uninstall. A recovery/export feature is needed to restore identity and decrypt backups after uninstall/reinstall.

## Build locally

Requires JDK 17, Android SDK 35, and Gradle 8.9:
```sh
gradle :app:assembleDebug
```

## Stable signed APK in the repository

Configure these repository Actions secrets before running **Build Android APK**:
- `UNISON_KEYSTORE_BASE64`: base64-encoded PKCS#12/JKS release keystore bytes
- `UNISON_KEYSTORE_PASSWORD`
- `UNISON_KEY_ALIAS`
- `UNISON_KEY_PASSWORD`

The workflow builds the release APK and writes it to `Download/UNISON.apk` in this repository, then commits that APK. A stable signing key is essential for upgrades. If you lose the key, Android will not accept an in-place update signed with a replacement key; preserve an offline backup.

## Server-free networking limitation

The native module does not yet provide worldwide direct connectivity. A pairing code establishes a trusted pairing input; it does not solve carrier-grade NAT, changing addresses, or network discovery. Under a strict no-server/no-relay rule, some internet connections and background incoming calls will fail. Do not claim delivery until the recipient device acknowledges receipt.
