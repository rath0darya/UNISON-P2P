"use strict";

const http = require("node:http");
const crypto = require("node:crypto");
const { WebSocketServer, WebSocket } = require("ws");

const PORT = Number(process.env.PORT || 10000);
const MAX_MESSAGE_BYTES = 64 * 1024;
const CODE_PATTERN = /^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{28}$/;
const peers = new Map(); // pairing code -> authenticated-by-possession socket
const sockets = new WeakMap(); // socket -> { code, name }
const requestTimes = new WeakMap();

const server = http.createServer((req, res) => {
  if (req.url === "/health" || req.url === "/") {
    res.writeHead(200, { "content-type": "application/json; charset=utf-8" });
    res.end(JSON.stringify({ service: "unison-signaling", status: "ok", transport: "WebRTC signaling only" }));
    return;
  }
  res.writeHead(404);
  res.end("Not found");
});

const wss = new WebSocketServer({
  server,
  maxPayload: MAX_MESSAGE_BYTES,
  perMessageDeflate: false
});

function send(socket, packet) {
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(packet));
  }
}

function validCode(value) {
  return typeof value === "string" && CODE_PATTERN.test(value);
}

function safeName(value) {
  return String(value || "Unison user").replace(/[\u0000-\u001f\u007f]/g, "").trim().slice(0, 48) || "Unison user";
}

function unregister(socket) {
  const entry = sockets.get(socket);
  if (entry && peers.get(entry.code) === socket) peers.delete(entry.code);
  sockets.delete(socket);
}

function rateAllowed(socket) {
  const now = Date.now();
  const history = (requestTimes.get(socket) || []).filter((time) => now - time < 10_000);
  if (history.length >= 50) {
    requestTimes.set(socket, history);
    return false;
  }
  history.push(now);
  requestTimes.set(socket, history);
  return true;
}

wss.on("connection", (socket) => {
  socket.on("message", (raw) => {
    if (!rateAllowed(socket)) {
      send(socket, { type: "error", message: "Too many signaling messages; slow down." });
      return;
    }

    let packet;
    try {
      packet = JSON.parse(raw.toString());
    } catch {
      send(socket, { type: "error", message: "Invalid JSON." });
      return;
    }

    const type = packet && packet.type;
    if (type === "register") {
      const code = packet.code;
      if (!validCode(code)) {
        send(socket, { type: "error", message: "Invalid pairing code format." });
        socket.close(1008, "invalid pairing code");
        return;
      }
      const existing = peers.get(code);
      if (existing && existing !== socket && existing.readyState === WebSocket.OPEN) {
        send(socket, { type: "error", message: "This pairing code is already online. Cloned app instances must generate separate pairing codes." });
        socket.close(1008, "duplicate pairing code");
        return;
      }
      const entry = { code, name: safeName(packet.name) };
      peers.set(code, socket);
      sockets.set(socket, entry);
      send(socket, { type: "registered", code });
      return;
    }

    const self = sockets.get(socket);
    if (!self) {
      send(socket, { type: "error", message: "Register your pairing code before signaling." });
      return;
    }

    if (type === "pair") {
      const targetCode = packet.targetCode;
      if (!validCode(targetCode) || targetCode === self.code) {
        send(socket, { type: "error", message: "Invalid target pairing code." });
        return;
      }
      const target = peers.get(targetCode);
      if (!target || target.readyState !== WebSocket.OPEN) {
        send(socket, { type: "error", message: "That peer is not connected to the signaling server. Ask them to open Unison and check their server URL." });
        return;
      }
      const targetInfo = sockets.get(target);
      send(socket, { type: "peer-online", fromCode: targetCode, fromName: targetInfo?.name || "Unison user" });
      send(target, { type: "incoming_pair", fromCode: self.code, fromName: self.name });
      return;
    }

    if (type === "offer" || type === "answer" || type === "ice") {
      const targetCode = packet.toCode;
      if (!validCode(targetCode) || targetCode === self.code) {
        send(socket, { type: "error", message: "Invalid signaling recipient." });
        return;
      }
      const target = peers.get(targetCode);
      if (!target || target.readyState !== WebSocket.OPEN) {
        send(socket, { type: "error", message: "Peer disconnected from signaling before negotiation finished." });
        return;
      }
      const outgoing = { type, fromCode: self.code, fromName: self.name };
      if (type === "ice") {
        if (typeof packet.candidate !== "string" || packet.candidate.length > 16_000) return;
        outgoing.candidate = packet.candidate;
        outgoing.sdpMid = String(packet.sdpMid || "0").slice(0, 32);
        outgoing.sdpMLineIndex = Number.isInteger(packet.sdpMLineIndex) ? packet.sdpMLineIndex : 0;
      } else {
        if (typeof packet.sdp !== "string" || packet.sdp.length > 48_000) return;
        outgoing.sdp = packet.sdp;
      }
      send(target, outgoing);
      return;
    }

    send(socket, { type: "error", message: "Unsupported signaling message type." });
  });

  socket.on("close", () => unregister(socket));
  socket.on("error", () => unregister(socket));
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`Unison signaling service listening on port ${PORT}`);
});
