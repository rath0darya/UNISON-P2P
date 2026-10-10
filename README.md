# UNISON P2P

A mobile-first, native-Android-style web prototype for decentralized private peer-to-peer communication.

**Developer credit:** Developed with ♥️ Aravind

## Implemented in the current web prototype

- Responsive app-style Home, Join, Identity, and Privacy screens.
- Cryptographic identity generation using Web Crypto ECDSA P-256; a 20-character peer ID is derived from the public key.
- Identity record stored in this browser's IndexedDB.
- Peer ID entry with validation, duplicate/self checks, peer list, and optional camera QR scanning on supported browsers.
- Direct WebRTC data-channel connection flow using manually exchanged offer/answer text; no signaling server is required.
- Signed connection descriptions. The app verifies that the signaling public key hashes to the claimed peer ID and checks its ECDSA signature.
- Text chat over the WebRTC data channel. WebRTC provides encrypted transport between connected peers.
- Incoming messages are checked for expiry and messages in the running chat are removed after 48 hours.
- PWA manifest and same-origin service-worker app shell.

## How to connect two devices

1. Serve the app from HTTPS or localhost on both devices and create an identity on each.
2. Exchange the 20-character peer IDs and add each other in **Join**.
3. On device A, open the saved peer and choose **Create connection offer**. Copy the full generated text and send it to device B through a channel you trust.
4. On device B, open the matching saved peer, paste A's text, and choose **Accept offer and create answer**. Send the generated answer text back to A.
5. On device A, paste the answer and choose **Finish connection with answer**. When the direct data channel opens, the status becomes connected and the chat appears.

Connection descriptions are signed and validated against the expected saved peer ID. Never accept connection text from an unknown peer. The initial implementation uses a public STUN server to help establish direct routes; some networks may not permit a direct connection. A TURN relay fallback is not configured.

## Current limitations — not production ready

- No automatic peer discovery, public relay network, TURN fallback, voice/video calls, group chat, file transfer, or native Android package.
- Manual signaling text is a bootstrap mechanism; a user must exchange offer/answer data out of band.
- Chat history is held in memory only and is lost when the page reloads. Messages have a 48-hour expiry while the app is running, but background deletion, persistent encrypted storage, and cross-device deletion are not implemented.
- The identity is stored in browser IndexedDB. Secure encrypted identity export/import and sharing the same identity between web and Android are not implemented.
- WebRTC encrypts the connection in transit, and signaling data is identity-signed, but the overall protocol has not received independent cryptographic review. Do not treat this prototype as audited or production-grade security.
- STUN can reveal network metadata such as public IP information to the STUN provider and peers may learn network information during connection setup. This prototype does not promise anonymity.
- Browser storage may be cleared by the browser, device, or user.

## Run locally

Serve the repository over HTTPS or localhost (required for secure browser cryptography, camera permissions, and service workers):

```sh
python3 -m http.server 8080
```

Then open http://localhost:8080.

## Design principles

- No mandatory account, email, phone number, or real name.
- Cryptographic peer identity rather than a central account.
- Direct peer-to-peer communication where the network permits it.
- Message content is intended to expire after 48 or 72 hours; the current chat prototype uses a 48-hour in-memory expiry.
- Web and Android clients should eventually share a versioned open protocol.

The project remains a prototype. Decentralization, end-to-end security, privacy, and deletion claims must be evaluated against the actual deployment and threat model.
