'use strict';

const http = require('node:http');
const crypto = require('node:crypto');
const { WebSocketServer, WebSocket } = require('ws');

const PORT = Number(process.env.PORT || 10000);
const MAX_PAYLOAD = 96 * 1024;
const MAILBOX_TTL_MS = 10 * 60 * 1000;
const MAX_MAILBOX_PER_PEER = 20;
const ID_RE = /^[A-Z2-7]{20}$/;
const clients = new Map();
const mailbox = new Map();
const rate = new WeakMap();

function validId(id) { return typeof id === 'string' && ID_RE.test(id); }
function canonicalPublic(jwk) {
  return JSON.stringify({ crv: jwk?.crv, kty: jwk?.kty, x: jwk?.x, y: jwk?.y });
}
function verifySignal(s) {
  if (!s || s.version !== 1 || !['offer', 'answer'].includes(s.type) ||
      !validId(s.id) || !validId(s.to) || s.id === s.to ||
      typeof s.sdp !== 'string' || s.sdp.length > 80000 ||
      !s.publicJwk || s.publicJwk.kty !== 'EC' || s.publicJwk.crv !== 'P-256' ||
      typeof s.signature !== 'string' || s.signature.length > 256) return false;
  try {
    const digest = crypto.createHash('sha256').update(canonicalPublic(s.publicJwk)).digest();
    const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
    let bits = 0, value = 0, out = '';
    for (const b of digest) {
      value = (value << 8) | b; bits += 8;
      while (bits >= 5) { out += alphabet[(value >>> (bits - 5)) & 31]; bits -= 5; }
    }
    if (bits) out += alphabet[(value << (5 - bits)) & 31];
    if (out.slice(0, 20) !== s.id) return false;
    const key = crypto.createPublicKey({ key: s.publicJwk, format: 'jwk' });
    const payload = JSON.stringify({ version: s.version, type: s.type, id: s.id, to: s.to, publicJwk: s.publicJwk, sdp: s.sdp });
    return crypto.verify('sha256', Buffer.from(payload), key, Buffer.from(s.signature, 'base64'));
  } catch { return false; }
}
function send(ws, data) {
  if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(data));
}
function queueFor(id, envelope) {
  const now = Date.now();
  const list = (mailbox.get(id) || []).filter(x => now - x.at < MAILBOX_TTL_MS);
  if (list.length >= MAX_MAILBOX_PER_PEER) list.shift();
  list.push({ at: now, envelope });
  mailbox.set(id, list);
}
function deliver(id) {
  const list = mailbox.get(id) || [];
  mailbox.delete(id);
  for (const item of list) if (Date.now() - item.at < MAILBOX_TTL_MS) {
    for (const ws of clients.get(id) || []) send(ws, { type: 'signal', signal: item.envelope });
  }
}
const server = http.createServer((req, res) => {
  if (req.url === '/health' || req.url === '/') {
    res.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
    res.end(JSON.stringify({ service: 'UNISON-P2P signaling', status: 'ok', storesChatMessages: false }));
    return;
  }
  res.writeHead(404); res.end('Not found');
});
const wss = new WebSocketServer({ server, maxPayload: MAX_PAYLOAD });
wss.on('connection', ws => {
  let peerId = null;
  rate.set(ws, { start: Date.now(), count: 0 });
  ws.on('message', raw => {
    const bucket = rate.get(ws);
    if (!bucket) return ws.close(1008, 'rate limit');
    if (Date.now() - bucket.start > 10000) { bucket.start = Date.now(); bucket.count = 0; }
    if (++bucket.count > 80) return ws.close(1008, 'rate limit');
    let msg;
    try { msg = JSON.parse(raw.toString()); } catch { return send(ws, { type: 'error', message: 'Invalid JSON.' }); }
    if (msg.type === 'register') {
      if (!validId(msg.id)) return ws.close(1008, 'invalid peer ID');
      if (peerId && clients.has(peerId)) {
        const old = clients.get(peerId); old.delete(ws); if (!old.size) clients.delete(peerId);
      }
      peerId = msg.id;
      if (!clients.has(peerId)) clients.set(peerId, new Set());
      clients.get(peerId).add(ws);
      send(ws, { type: 'registered', id: peerId });
      deliver(peerId);
      return;
    }
    if (msg.type !== 'signal' || !peerId || !msg.signal || msg.signal.id !== peerId ||
        !verifySignal(msg.signal)) return send(ws, { type: 'error', message: 'Invalid or unsigned signal rejected.' });
    const signal = msg.signal;
    const targets = clients.get(signal.to);
    if (targets && targets.size) {
      for (const target of targets) send(target, { type: 'signal', signal });
      send(ws, { type: 'delivered', to: signal.to });
    } else {
      queueFor(signal.to, signal);
      send(ws, { type: 'queued', to: signal.to, expiresInMs: MAILBOX_TTL_MS });
    }
  });
  ws.on('close', () => {
    if (!peerId) return;
    const set = clients.get(peerId);
    if (set) { set.delete(ws); if (!set.size) clients.delete(peerId); }
  });
  ws.on('error', () => {});
});
server.listen(PORT, '0.0.0.0', () => console.log('UNISON signaling listening on ' + PORT));
