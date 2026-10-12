# UNISON-P2P Security and Privacy Notes

UNISON-P2P is an independent project. It is not Tox-compatible and must not claim to be more secure than Tox without a defined threat model, independent review, and comparative testing.

## Current protections

- A peer ID is derived from the public part of a device-generated P-256 ECDSA identity key. The private identity key is kept in that browser's IndexedDB.
- Signaling registration uses a fresh server challenge signed by the private identity key. The server verifies the signature and checks that the supplied public key derives the claimed peer ID before registering the WebSocket. This makes simple peer-ID squatting and unauthorized retrieval of queued signals harder; it does not prevent denial of service.
- WebRTC offer/answer descriptions are signed and checked against the sender's peer ID.
- The data channel uses a separate application-layer encryption prototype: fresh P-256 ECDH keys per connection, identity-signed key exchange, HKDF-SHA-256 directional keys, and AES-256-GCM payload encryption.
- WebRTC transport encryption is also enabled by the browser.
- An optional relay-only network mode can be configured with a TURN server. When enabled, the WebRTC peer connection uses iceTransportPolicy set to relay and requires valid TURN credentials.

## Anonymity limits

- Without relay-only mode, WebRTC may expose IP/network candidates to the other peer and network metadata to the STUN provider.
- Relay-only mode hides direct IP candidates from the peer, but the TURN operator can observe connection metadata and IP addresses. The signaling host and internet provider can still observe their respective network metadata.
- The current deployment does not route signaling through Tor/I2P, does not use onion routing, and does not provide distributed DHT discovery or cover traffic. Do not advertise the current build as anonymous or untraceable.
- The signaling service receives peer IDs and signed SDP, and temporarily queues signaling envelopes in memory for up to 10 minutes.
- TURN credentials are stored in the browser's local storage when configured. Use short-lived credentials from a trusted TURN service; do not embed permanent TURN secrets in a public client.
- Browser persistence is not secure hardware storage. A device compromise or malicious same-origin script can compromise identity keys and local chat data.

## Cryptographic limitations

- The application-layer protocol is custom prototype cryptography and has not been independently audited. It has no reviewed Double Ratchet, no audited Noise session protocol, and no formal interoperability/security test suite.
- Fresh ECDH keys per WebRTC connection are not the same as a mature per-message ratchet. Do not claim full forward secrecy across every message or post-compromise security.
- The browser stores the local history-encryption key in the same IndexedDB database as the ciphertext; this helps separate records but does not protect against an attacker who can read the whole browser profile.
- Message expiration is best-effort local cleanup, not guaranteed secure erasure from backups, screenshots, browser caches, or the other participant's device.
- ECDSA P-256 and AES-GCM are standard browser primitives, but using standard primitives does not make a custom protocol audited or automatically secure.

## Next security milestones

1. Add a reviewed session protocol (for example, a well-specified Noise pattern) and independent review before production claims.
2. Add a per-message ratchet with replay/order protection and documented key lifecycle.
3. Provide a trustworthy TURN deployment and short-lived credentials; test relay-only behavior on Android networks.
4. Consider an optional Tor-compatible transport/proxy for signaling and relay access, with clear limitations for browser WebRTC.
5. Add security tests, dependency update checks, content-security policy, and a threat-model-driven privacy review.

## References

- Tox FAQ: https://tox.chat/faq.html
- Tox privacy FAQ: https://wiki.tox.chat/users/faq
- Noise Protocol Framework: https://noiseprotocol.org/
