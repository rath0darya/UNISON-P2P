#!/usr/bin/env python3
"""Python-only UNISON-P2P signaling server.

Relays signed WebRTC offer/answer descriptions only. Chat messages stay on the
peer-to-peer data channel. This server is a prototype, not an anonymity service.
"""
import asyncio
import base64
import hashlib
import json
import os
import re
import time
from collections import defaultdict, deque

from aiohttp import WSMsgType, web
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec, utils

PORT = int(os.environ.get("PORT", "10000"))
MAX_PAYLOAD = 96 * 1024
MAILBOX_TTL = 10 * 60
MAX_MAILBOX_PER_PEER = 20
ID_RE = re.compile(r"^[A-Z2-7]{20}$")
clients = defaultdict(set)
mailbox = defaultdict(deque)


def compact_json(value):
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False)


def valid_id(value):
    return isinstance(value, str) and ID_RE.fullmatch(value) is not None


def canonical_public(jwk):
    return compact_json({
        "crv": jwk.get("crv"),
        "kty": jwk.get("kty"),
        "x": jwk.get("x"),
        "y": jwk.get("y"),
    })


def b64url_int(value):
    if not isinstance(value, str):
        raise ValueError("Invalid JWK coordinate")
    raw = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    if not raw:
        raise ValueError("Empty JWK coordinate")
    return int.from_bytes(raw, "big")


def verify_signal(signal):
    if not isinstance(signal, dict):
        return False
    if signal.get("version") != 1 or signal.get("type") not in ("offer", "answer"):
        return False
    if not valid_id(signal.get("id")) or not valid_id(signal.get("to")):
        return False
    if signal["id"] == signal["to"]:
        return False
    sdp = signal.get("sdp")
    jwk = signal.get("publicJwk")
    signature = signal.get("signature")
    if not isinstance(sdp, str) or len(sdp) > 80000:
        return False
    if not isinstance(jwk, dict) or jwk.get("kty") != "EC" or jwk.get("crv") != "P-256":
        return False
    if not isinstance(signature, str) or len(signature) > 256:
        return False
    try:
        digest = hashlib.sha256(canonical_public(jwk).encode("utf-8")).digest()
        peer_id = base64.b32encode(digest).decode("ascii").rstrip("=")[:20]
        if peer_id != signal["id"]:
            return False
        public_key = ec.EllipticCurvePublicNumbers(
            b64url_int(jwk.get("x")),
            b64url_int(jwk.get("y")),
            ec.SECP256R1(),
        ).public_key()
        # Web Crypto ECDSA signatures are IEEE-P1363 r||s; cryptography
        # verifies DER-encoded ECDSA signatures.
        raw_sig = base64.b64decode(signature, validate=True)
        if len(raw_sig) != 64:
            return False
        r = int.from_bytes(raw_sig[:32], "big")
        s = int.from_bytes(raw_sig[32:], "big")
        der_sig = utils.encode_dss_signature(r, s)
        payload = {
            "version": signal["version"],
            "type": signal["type"],
            "id": signal["id"],
            "to": signal["to"],
            "publicJwk": jwk,
            "sdp": sdp,
        }
        public_key.verify(der_sig, compact_json(payload).encode("utf-8"), ec.ECDSA(hashes.SHA256()))
        return True
    except (ValueError, TypeError, KeyError, InvalidSignature):
        return False


def prune_mailbox(peer_id):
    now = time.time()
    queue = mailbox[peer_id]
    while queue and now - queue[0][0] >= MAILBOX_TTL:
        queue.popleft()
    return queue


async def send_json(ws, data):
    if not ws.closed:
        await ws.send_str(compact_json(data))


async def health(_request):
    return web.json_response({
        "service": "UNISON-P2P signaling",
        "status": "ok",
        "runtime": "python",
        "storesChatMessages": False,
    }, headers={"Cache-Control": "no-store"})


async def websocket_handler(request):
    ws = web.WebSocketResponse(max_msg_size=MAX_PAYLOAD, heartbeat=30)
    await ws.prepare(request)
    peer_id = None
    rate_start = time.monotonic()
    rate_count = 0
    try:
        async for frame in ws:
            if frame.type == WSMsgType.TEXT:
                now = time.monotonic()
                if now - rate_start > 10:
                    rate_start, rate_count = now, 0
                rate_count += 1
                if rate_count > 80:
                    await ws.close(code=1008, message=b"rate limit")
                    break
                try:
                    msg = json.loads(frame.data)
                except (json.JSONDecodeError, TypeError):
                    await send_json(ws, {"type": "error", "message": "Invalid JSON."})
                    continue
                if not isinstance(msg, dict):
                    await send_json(ws, {"type": "error", "message": "Invalid message."})
                    continue
                if msg.get("type") == "register":
                    requested_id = msg.get("id")
                    if not valid_id(requested_id):
                        await ws.close(code=1008, message=b"invalid peer ID")
                        break
                    if peer_id is not None:
                        clients[peer_id].discard(ws)
                        if not clients[peer_id]:
                            clients.pop(peer_id, None)
                    peer_id = requested_id
                    clients[peer_id].add(ws)
                    await send_json(ws, {"type": "registered", "id": peer_id})
                    queue = prune_mailbox(peer_id)
                    while queue:
                        _, envelope = queue.popleft()
                        for target in list(clients.get(peer_id, ())):
                            await send_json(target, {"type": "signal", "signal": envelope})
                    mailbox.pop(peer_id, None)
                    continue
                envelope = msg.get("signal")
                if (msg.get("type") != "signal" or peer_id is None or
                        not isinstance(envelope, dict) or envelope.get("id") != peer_id or
                        not verify_signal(envelope)):
                    await send_json(ws, {"type": "error", "message": "Invalid or unsigned signal rejected."})
                    continue
                target_id = envelope["to"]
                targets = list(clients.get(target_id, ()))
                if targets:
                    for target in targets:
                        await send_json(target, {"type": "signal", "signal": envelope})
                    await send_json(ws, {"type": "delivered", "to": target_id})
                else:
                    queue = prune_mailbox(target_id)
                    if len(queue) >= MAX_MAILBOX_PER_PEER:
                        queue.popleft()
                    queue.append((time.time(), envelope))
                    await send_json(ws, {
                        "type": "queued",
                        "to": target_id,
                        "expiresInMs": MAILBOX_TTL * 1000,
                    })
            elif frame.type in (WSMsgType.ERROR, WSMsgType.CLOSE, WSMsgType.CLOSED):
                break
    finally:
        if peer_id is not None:
            clients[peer_id].discard(ws)
            if not clients[peer_id]:
                clients.pop(peer_id, None)
    return ws


async def root(request):
    if request.headers.get("Upgrade", "").lower() == "websocket":
        return await websocket_handler(request)
    # Serve the web app from the same public origin as signaling. This lets
    # users connect by peer ID only; the browser derives WSS from its own URL.
    return web.FileResponse(os.path.join(os.path.dirname(os.path.abspath(__file__)), "index.html"))


async def frontend_file(request):
    filename = request.path.lstrip("/")
    if filename not in {"manifest.json", "sw.js"}:
        raise web.HTTPNotFound()
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), filename)
    if not os.path.isfile(path):
        raise web.HTTPNotFound()
    return web.FileResponse(path)


def create_app():
    app = web.Application(client_max_size=MAX_PAYLOAD)
    app.router.add_get("/", root)
    app.router.add_get("/health", health)
    app.router.add_get("/manifest.json", frontend_file)
    app.router.add_get("/sw.js", frontend_file)
    return app


if __name__ == "__main__":
    web.run_app(create_app(), host="0.0.0.0", port=PORT, access_log=None)
