# Unison WebRTC signaling server

This is a separate, small Node.js WebSocket service for Unison. The Android app uses it only to find an online pairing code and exchange WebRTC offer/answer/ICE metadata. **Chat messages are not sent through this server**; they use a WebRTC data channel whenever a direct ICE path can be established.

## Deploy on Render (free tier)

1. Push this repository to GitHub.
2. In Render, create a **Blueprint** from this repository, or create a Web Service manually.
3. For a manual service set **Root Directory** to `signaling-server`, **Build Command** to `npm install`, and **Start Command** to `npm start`.
4. Wait for the service to deploy. Check `https://YOUR-SERVICE.onrender.com/health`.
5. In Unison → Settings → Network & Signaling, enter `wss://YOUR-SERVICE.onrender.com` and save it on each phone.

Render free services may sleep when idle and cold starts can delay the first connection. Keep the app open while pairing during testing.

## Android signaling message protocol

- Register: `{ "type":"register", "code":"28_CHAR_CODE", "name":"Display name" }`
- Pair: `{ "type":"pair", "targetCode":"28_CHAR_PEER_CODE" }`
- Routed negotiation: `offer`, `answer`, and `ice` packets include `toCode`; the server adds `fromCode` and `fromName` when forwarding.
- Server responses: `registered`, `peer-online`, `incoming_pair`, or `error`.

## Important limits and security

- The server is signaling only; it is not a TURN relay and does not guarantee connectivity through every carrier NAT/firewall. A TURN service is needed when direct ICE connectivity fails.
- Pairing codes are bearer secrets. Anyone who obtains a code may attempt to contact its owner. Do not publish your code.
- A code can have only one active connection. If an app clone shares the original app's saved code, generate a new code for the clone. This prevents silent identity collisions.
- This minimal server is suitable for development/testing, not production without authentication, abuse monitoring, origin/rate controls, and a reviewed pairing/identity design.
- Vercel serverless functions are not a good fit for this persistent WebSocket service; use a Render Web Service or another host that supports long-lived WebSockets.
