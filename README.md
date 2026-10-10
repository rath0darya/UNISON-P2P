# UNISON P2P

A mobile-first, native-Android-style web prototype for decentralized private peer-to-peer communication.

**Developer credit:** Developed with ♥️ Aravind

## Implemented in the current web prototype

- Responsive app-style Home, Join, Identity, and Privacy screens.
- Cryptographic identity generation using Web Crypto ECDSA P-256; a 20-character peer ID is derived from the public key.
- Identity record stored in this browser's IndexedDB.
- Shareable invite links as the primary contact-sharing flow; opening a link takes the recipient to the Join screen with the peer ID filled in.
- Peer ID entry with validation, duplicate/self checks, peer list, and optional camera QR scanning on supported browsers. QR scanning accepts either a peer ID or an invite link encoded in a QR code.
- Direct WebRTC data-channel connection flow using manually exchanged offer/answer text; no signaling server is required.
- Signed connection descriptions. The app verifies that the signaling public key hashes to the claimed peer ID and checks its ECDSA signature.
- Text chat over the WebRTC data channel. WebRTC provides encrypted transport between connected peers.
- Incoming messages are checked for expiry; users can choose a 48- or 72-hour lifetime and expired in-memory messages are cleaned while the app is running.
- PWA manifest and same-origin service-worker app shell.

## Friend invitations and current connection setup

1. Serve the app from HTTPS or localhost and create an identity.
2. On Home, tap **Share invite link**. Send that link through any messaging app, email, or other channel to a friend in any country. If the system share sheet is unavailable, the link is copied instead. **Copy invite link** is also available.
3. Your friend opens the link; UNISON opens Join with your peer ID filled in. They tap **Add friend**. This saves the contact, but does not by itself establish a live connection.
4. QR is an optional alternative: open **Join → Scan QR** and scan a QR containing the invite link or peer ID. Camera scanning requires browser support and permission.
5. To start a live chat in this prototype, both users must still exchange the signed WebRTC offer/answer text out of band: the offer creator sends an offer; the other person accepts it and sends back the answer; the offer creator finishes with that answer.

Invite links contain only the peer ID. They do not contain private keys or chat messages. Links make contact sharing easier across countries, but this prototype still needs a signaling/discovery mechanism to remove the manual offer/answer step.

Connection descriptions are signed and validated against the expected saved peer ID. Never accept connection text from an unknown peer. The initial implementation uses a public STUN server to help establish direct routes; some networks may not permit a direct connection. A TURN relay fallback is not configured.

## Current limitations — not production ready

- No automatic peer discovery, public relay network, TURN fallback, voice/video calls, group chat, file transfer, or native Android package.
- Invite links simplify adding a contact but do not connect peers by themselves. Manual signaling text is still required for a live connection; automatic cross-country discovery/signaling and a relay fallback are not implemented.
- Chat history is held in memory only and is lost when the page reloads. Messages have a selectable 48- or 72-hour expiry while the app is running, but background deletion, persistent encrypted storage, and cross-device deletion are not implemented.
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
- Message content is intended to expire after 48 or 72 hours; the current chat prototype supports a 48- or 72-hour in-memory expiry.
- Web and Android clients should eventually share a versioned open protocol.

The project remains a prototype. Decentralization, end-to-end security, privacy, and deletion claims must be evaluated against the actual deployment and threat model.
