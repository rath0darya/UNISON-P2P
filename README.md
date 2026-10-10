# UNISON P2P

A mobile-first, native-Android-style web prototype for a privacy-first decentralized communication project.

**Developer credit:** Developed with ♥️ Aravind

## Current web prototype

- Responsive, app-like interface for mobile and desktop.
- Welcome, Join, Identity, and Privacy screens.
- Local cryptographic identity generation using the browser Web Crypto API (ECDSA P-256); a short peer ID is derived from the public key.
- Identity record stored in this browser's IndexedDB.
- 16–20-character join-code format validation.
- PWA manifest and a minimal same-origin app-shell service worker.
- Message-retention policy shown as a design target: 48 or 72 hours.

## Important: this is not yet a working messaging network

The current build does **not** implement QR encoding/scanning, peer discovery, the custom P2P protocol, direct connections, message sending/receiving, calls, encrypted identity transfer, or the actual 48/72-hour deletion engine. The join code screen validates format only; it does not connect users. Do not use this prototype to store sensitive identity material or claim production-grade security.

The current identity feature is an initial implementation milestone, not a completed security design. The generated Web Crypto private key is stored as a CryptoKey in IndexedDB, but this prototype has not undergone security review. Browser data can be cleared by the user, the browser, or the operating system. An identity can only persist across devices after a secure export/import mechanism is implemented.

## Run locally

Serve the repository over HTTPS or localhost (required for secure browser cryptography and service workers). For example, with Python installed:

```sh
python3 -m http.server 8080
```

Then open http://localhost:8080.

## Suggested next milestones

1. Review identity/key format and threat model.
2. Implement QR invitation encoding and camera scanning without exposing invitation secrets.
3. Specify the shared protocol and peer authentication.
4. Add direct peer messaging and explicit connection-state UI.
5. Implement expiry enforcement, cleanup, and tests across local storage/caches.
6. Add encrypted identity export/import for web and Android.
7. Add peer discovery, NAT traversal, optional relays, then voice/video calling.

## Design principles

- No mandatory account, email, phone number, or real name.
- Identity is derived from cryptographic public-key material, not from a central account.
- User-controlled device storage; no central account is required.
- Message content is intended to expire after 48 or 72 hours.
- No central permanent message archive.
- Web and Android clients should share a versioned open protocol.

The project is a prototype. Claims about decentralization, anonymity, end-to-end encryption, or irreversible deletion should only be made once their corresponding protocol and implementation have been built and independently tested.
