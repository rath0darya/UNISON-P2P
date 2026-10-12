# UNISON P2P

A mobile-first, native-Android-style web prototype for decentralized private peer-to-peer communication.

**Developer credit:** Developed with ♥️ Aravind

## Implemented in the current web prototype

- Responsive app-style Home, Join, Identity, and Privacy screens.
- Cryptographic identity generation using Web Crypto ECDSA P-256; a 20-character peer ID is derived from the public key.
- Identity record stored in this browser's IndexedDB.
- Shareable invite links as the primary contact-sharing flow; opening a link takes the recipient to the Join screen with the peer ID filled in.
- Optional on-screen QR invitation from Identity, generated in the browser using QRCode.js; QR scanning remains an alternative for nearby users.
- Peer ID entry with validation, duplicate/self checks, peer list, and optional camera QR scanning on supported browsers. QR scanning accepts either a peer ID or an invite link encoded in a QR code.
- Direct WebRTC data-channel chat with automatic signed offer/answer exchange through the signaling service. Add a contact using only their peer ID; manual offer/answer pairing has been removed from the chat UI.
- Signed connection descriptions. The app verifies that the signaling public key hashes to the claimed peer ID and checks its ECDSA signature.
- Text chat payloads, receipts, and call-control messages are additionally encrypted at the application layer with AES-256-GCM. Each encrypted frame uses authenticated sender/recipient/counter metadata and a strictly increasing per-session receive counter to reject replayed or reordered frames. Peers exchange fresh ephemeral P-256 ECDH public keys over the WebRTC data channel; each handshake is signed by the peer's persistent ECDSA P-256 identity key and checked against the peer ID. HKDF-SHA-256 derives separate directional AES-GCM keys. WebRTC transport encryption remains enabled underneath.
- In-chat audio and video calls, incoming-call accept/decline controls, mute, hang up, and switching between audio-only and video during an active call (browser microphone/camera permission required).
- WhatsApp-style message status: one tick for sent, two gray ticks for delivered, and two green ticks after the recipient opens the chat and sends a read receipt.
- Telegram-inspired full-screen conversation layout on mobile, with a scrollable message area and composer anchored at the bottom.
- Messages are encrypted with a device-held AES-GCM key before being saved in IndexedDB, survive refresh on the same browser profile, and are removed after their selected 48- or 72-hour lifetime when cleanup runs.
- PWA manifest and same-origin service-worker app shell.

## Friend invitations and automatic connection setup

1. Serve the app from HTTPS and create an identity.
2. On Home, tap **Share invite link** and send it to your friend in any country. The link contains your peer ID, not private keys or chat messages.
3. Your friend opens the link and taps **Add friend**. Both people must add each other's peer IDs to their saved peer lists.
4. Deploy the included Python signaling service using the repository's `render.yaml` blueprint. It installs the packages in `requirements.txt` and starts with `python server.py`; its health endpoint is `/health`. The signaling backend does not require Node.js.
5. **Termux-hosted setup:** run the Python app and signaling server together, expose port `10000` with ngrok, and open ngrok's HTTPS URL on both devices. The app automatically uses that same host for WSS signaling, so users only add each other's peer IDs; they do not enter the tunnel or signaling URL. If you deploy the web app and signaling server on separate hosts instead, configure the signaling URL in Privacy on both devices.
6. Open the saved peer chat. The client exchanges signed WebRTC offers/answers through the signaling service and verifies the peer ID and signature before applying them. When both peers are online, the lower peer ID initiates to avoid both sides starting offers at once. Signals can be queued for up to 10 minutes while a peer is offline; both people should open UNISON within that window for a fresh connection.

Signaling registration now includes a fresh server challenge that the client must sign with the private key for its peer ID before the server registers that socket or delivers queued signals. This reduces peer-ID impersonation and unauthorized mailbox access, but it does not prevent denial of service or make the service anonymous. The Privacy screen also has optional TURN relay-only mode: configure a trusted TURN URL plus short-lived credentials, then enable relay-only mode to avoid exposing direct WebRTC candidates to the other peer. TURN operators and the signaling host still see network metadata. See SECURITY.md for the threat model and known limitations.\n\nThe Python signaling service relays only signed connection setup descriptions. It does not receive the WebRTC chat data channel or store chat message content. The service sees peer IDs, connection metadata, and SDP/IP-routing details, so it is not an anonymity service. Use a service you trust. If a direct WebRTC path is blocked by a network, this version has no TURN media/data relay fallback and may fail to connect.

QR remains optional: **Identity → Show QR code** and **Join → Scan QR**. Invite links are easier for friends in different countries.

## Current limitations — not production ready

- No public relay network for chat traffic, TURN fallback, voice/video calls, group chat, file transfer, or native Android package. The Python signaling service and static frontend are defined in `render.yaml` but still need to be deployed by the repository owner.
- Invite links add contacts but do not themselves connect them. Automatic signaling works only after a public signaling service is deployed and configured by both users. The service is not hosted by this repository.
- Chat history is encrypted at rest in the same browser profile and can survive refresh on that device. It is not synced between devices; expiry cleanup runs while the app is open and on startup, not guaranteed at the exact expiry moment while the app is closed.
- The identity is stored in browser IndexedDB. Secure encrypted identity export/import and sharing the same identity between web and Android are not implemented.
- The application-layer E2EE handshake and message encryption are prototype cryptography and have not received independent review, interoperability testing, or adversarial testing. Do not treat UNISON as audited or production-grade security. Both peers must run a compatible updated client; older clients cannot exchange the new encrypted data-channel messages.
- STUN can reveal network metadata such as public IP information to the STUN provider and peers may learn network information during connection setup. This prototype does not promise anonymity.
- Browser storage may be cleared by the browser, device, or user.

## Run the combined app and signaling service in Termux

Clone this repository on your Android phone, then run:

```sh
cd ~/UNISON-P2P
bash termux-start.sh
```

The startup script checks the required project files and Python package versions before starting. If Python is missing, it attempts to install it through Termux's package manager; if `aiohttp` or `cryptography` is missing/out of range, it installs the declared requirements automatically, with a Termux-package fallback. It then compiles the Python files, runs the security tests, and checks that the chosen port is free. The server starts only if every preflight check passes. The script does not store chat messages on the signaling server. In a second Termux session, run `ngrok http 10000`. Open the HTTPS URL ngrok prints on both devices. Because the page and WebSocket signaling share that same public host, the app derives its WSS address automatically and people connect using peer IDs only. Keep both Termux processes and the phone online. If ngrok gives you a different URL after restarting, share the new URL with users again. A stable ngrok domain avoids that URL changing, if available on your ngrok plan.

## Run the Python signaling server locally

For a full dependency, syntax, and security-test preflight before startup, use `bash termux-start.sh`. For manual development, install the Python dependencies and start the signaling service:

```sh
python -m pip install -r requirements.txt
python server.py
```

Check `http://127.0.0.1:10000/health`; the JSON response should include `"runtime": "python"`. The root path also accepts WebSocket upgrades for signaling.

## Run the web app locally

Serve the repository over HTTPS or localhost (required for secure browser cryptography, camera permissions, and service workers):

```sh
python3 -m http.server 8080
```

Then open http://localhost:8080. Localhost is for testing on that device only; invite links for friends abroad require a publicly reachable HTTPS deployment.

## Design principles

- No mandatory account, email, phone number, or real name.
- Cryptographic peer identity rather than a central account.
- Direct peer-to-peer communication where the network permits it.
- Message content is intended to expire after 48 or 72 hours; this version encrypts stored history in IndexedDB and cleans expired messages on startup and periodically while open. Browser/device storage removal is not guaranteed while the app is closed.
- Web and Android clients should eventually share a versioned open protocol.

The project remains a prototype. Decentralization, end-to-end security, privacy, and deletion claims must be evaluated against the actual deployment and threat model.

## Chat interface and peer IDs

- Chat screen uses a full-screen mobile layout with outgoing/incoming message bubbles, date separators, a bottom composer, and local device time formatting. Outgoing messages show sent/delivered/read ticks when receipt messages are exchanged. Times are formatted using the browser/device locale and time zone; each participant sees their own local time.
- Peer IDs are displayed and accepted as uppercase 20-character Base32 strings. Existing browser-stored lowercase IDs are normalized when the app starts.
- A peer ID/hash identifies an identity; it cannot by itself establish a live WebRTC connection. After the signaling service is deployed and configured, signed offer/answer exchange happens automatically; manual pairing remains available as a fallback.
- Adding salt or punctuation to the displayed peer ID would not by itself improve connection security. The identity is derived from a public-key hash, and connection descriptions remain signature-verified; WebRTC encrypts data in transit.
