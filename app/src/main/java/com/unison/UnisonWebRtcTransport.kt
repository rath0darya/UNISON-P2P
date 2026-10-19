package com.unison

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebRTC data-channel chat transport. The signaling service only exchanges SDP/ICE;
 * chat text travels through the peer-to-peer data channel whenever ICE can connect.
 * A deployed WebSocket signaling endpoint must implement the documented protocol.
 */
internal class UnisonWebRtcTransport(
    context: Context,
    private val ownCode: String,
    private val displayName: String,
    signalingUrl: String,
    private val callback: Callback
) {
    internal interface Callback {
        fun onStatus(status: String)
        fun onMessage(text: String, senderName: String, timestamp: Long)
        fun onPeerName(name: String)
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val endpoint = signalingUrl.trim()
    private val started = AtomicBoolean(false)
    private val http = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var peer: PeerConnection? = null
    @Volatile private var dataChannel: DataChannel? = null
    @Volatile private var targetCode: String? = null
    @Volatile private var targetName: String = "Unison contact"
    private val pendingIce = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false
    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions()
        )
        PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        if (!endpoint.startsWith("wss://") && !endpoint.startsWith("ws://")) {
            postStatus("Set your WebSocket signaling server URL in Settings to connect")
            return
        }
        val request = Request.Builder().url(endpoint).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject()
                    .put("type", "register")
                    .put("code", normalize(ownCode))
                    .put("name", displayName.ifBlank { "Unison user" })
                    .toString())
                postStatus("Connected to signaling server; ready to pair")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleSignal(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                postStatus("Signaling connection failed: ${t.message ?: "network error"}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                postStatus("Signaling connection closed: ${reason.ifBlank { "disconnected" }}")
            }
        })
    }

    fun connectTo(code: String) {
        val target = normalize(code)
        if (!validCode(target)) {
            postStatus("Invalid pairing code")
            return
        }
        if (target == normalize(ownCode)) {
            postStatus("That is your own pairing code")
            return
        }
        if (socket == null) {
            postStatus("Signaling server is not connected. Configure its URL in Settings.")
            return
        }
        targetCode = target
        targetName = target
        val sent = socket?.send(JSONObject()
            .put("type", "pair")
            .put("targetCode", target)
            .put("name", displayName)
            .toString()) ?: false
        postStatus(if (sent) "Requesting peer through signaling server…" else "Could not send pairing request")
    }

    fun sendMessage(text: String): Boolean {
        if (text.isBlank()) return false
        val channel = dataChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        val payload = JSONObject()
            .put("type", "message")
            .put("text", text.take(MAX_MESSAGE_CHARS))
            .put("timestamp", System.currentTimeMillis())
            .toString()
            .toByteArray(Charsets.UTF_8)
        return channel.send(DataChannel.Buffer(ByteBuffer.wrap(payload), false))
    }

    fun isConnected(): Boolean = dataChannel?.state() == DataChannel.State.OPEN

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { dataChannel?.close() }
        runCatching { peer?.close() }
        runCatching { peer?.dispose() }
        runCatching { dataChannel?.dispose() }
        dataChannel = null
        peer = null
        socket?.close(1000, "app stopped")
        socket = null
        http.dispatcher.cancelAll()
    }

    private fun handleSignal(raw: String) {
        try {
            val packet = JSONObject(raw)
            when (packet.optString("type")) {
                "registered" -> postStatus("Connected to signaling server; ready to pair")
                "error" -> postStatus(packet.optString("message", "Signaling server error"))
                "peer-online" -> {
                    val from = normalize(packet.optString("fromCode"))
                    if (validCode(from)) {
                        targetCode = from
                        targetName = packet.optString("fromName").ifBlank { from }
                        callback.onPeerName(targetName)
                        postStatus("Peer is online; negotiating WebRTC connection…")
                        createOffer()
                    }
                }
                "incoming_pair" -> {
                    val from = normalize(packet.optString("fromCode"))
                    if (!validCode(from) || from == normalize(ownCode)) return
                    targetCode = from
                    targetName = packet.optString("fromName").ifBlank { from }
                    main.post { callback.onPeerName(targetName) }
                    postStatus("Incoming pairing request from $targetName")
                    ensurePeerConnection(createDataChannel = false)
                }
                "offer" -> {
                    val from = normalize(packet.optString("fromCode"))
                    if (!acceptFrom(from)) return
                    ensurePeerConnection(createDataChannel = false)
                    val sdp = SessionDescription(SessionDescription.Type.OFFER, packet.getString("sdp"))
                    peer?.setRemoteDescription(object : SdpObserverAdapter() {
                        override fun onSetSuccess() {
                            remoteDescriptionSet = true
                            flushPendingIce()
                            peer?.createAnswer(object : SdpObserverAdapter() {
                                override fun onCreateSuccess(desc: SessionDescription) {
                                    peer?.setLocalDescription(object : SdpObserverAdapter() {
                                        override fun onSetSuccess() {
                                            sendSignal("answer", from, desc.description)
                                        }
                                        override fun onSetFailure(error: String) { postStatus("WebRTC answer setup failed: $error") }
                                    }, desc)
                                }
                                override fun onCreateFailure(error: String) { postStatus("WebRTC answer failed: $error") }
                            }, MediaConstraints())
                        }
                        override fun onSetFailure(error: String) { postStatus("Could not accept WebRTC offer: $error") }
                    }, sdp)
                }
                "answer" -> {
                    val from = normalize(packet.optString("fromCode"))
                    if (!acceptFrom(from)) return
                    val sdp = SessionDescription(SessionDescription.Type.ANSWER, packet.getString("sdp"))
                    peer?.setRemoteDescription(object : SdpObserverAdapter() {
                        override fun onSetSuccess() {
                            remoteDescriptionSet = true
                            flushPendingIce()
                            postStatus("WebRTC answer accepted; connecting directly…")
                        }
                        override fun onSetFailure(error: String) { postStatus("Could not accept WebRTC answer: $error") }
                    }, sdp)
                }
                "ice" -> {
                    val from = normalize(packet.optString("fromCode"))
                    if (!acceptFrom(from)) return
                    val candidate = IceCandidate(
                        packet.optString("sdpMid").ifBlank { "0" },
                        packet.optInt("sdpMLineIndex", 0),
                        packet.getString("candidate")
                    )
                    synchronized(pendingIce) {
                        if (remoteDescriptionSet) peer?.addIceCandidate(candidate) else pendingIce.add(candidate)
                    }
                }
            }
        } catch (e: Exception) {
            postStatus("Invalid signaling message: ${e.message ?: "unknown error"}")
        }
    }

    private fun acceptFrom(code: String): Boolean {
        if (!validCode(code)) return false
        val current = targetCode
        return current == null || current == code
    }

    private fun ensurePeerConnection(createDataChannel: Boolean) {
        if (peer != null) {
            if (createDataChannel && dataChannel == null) createChannel()
            return
        }
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> postStatus("Connected directly to $targetName")
                    PeerConnection.IceConnectionState.FAILED -> postStatus("WebRTC connection failed. This network may require a TURN relay.")
                    PeerConnection.IceConnectionState.DISCONNECTED -> postStatus("WebRTC connection interrupted")
                    PeerConnection.IceConnectionState.CHECKING -> postStatus("Finding a direct WebRTC route…")
                    else -> Unit
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                val target = targetCode ?: return
                sendSignal("ice", target, candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: org.webrtc.MediaStream) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {
                dataChannel = channel
                observeChannel(channel)
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver, mediaStreams: Array<out org.webrtc.MediaStream>) {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                if (newState == PeerConnection.PeerConnectionState.FAILED) postStatus("WebRTC peer connection failed")
            }
            override fun onSelectedCandidatePairChanged(event: org.webrtc.CandidatePairChangeEvent) {}
        }
        peer = factory.createPeerConnection(iceServers, observer)
        if (peer == null) {
            postStatus("Could not create WebRTC peer connection")
            return
        }
        if (createDataChannel) createChannel()
    }

    private fun createChannel() {
        val init = DataChannel.Init()
        val channel = peer?.createDataChannel("unison-chat", init) ?: return
        dataChannel = channel
        observeChannel(channel)
    }

    private fun observeChannel(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                if (channel.state() == DataChannel.State.OPEN) postStatus("Connected directly to $targetName")
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                try {
                    val packet = JSONObject(String(bytes, Charsets.UTF_8))
                    if (packet.optString("type") != "message") return
                    val body = packet.optString("text")
                    if (body.isNotBlank() && body.length <= MAX_MESSAGE_CHARS) {
                        main.post {
                            callback.onMessage(body, targetName, packet.optLong("timestamp", System.currentTimeMillis()))
                        }
                    }
                } catch (_: Exception) {
                    postStatus("Ignored malformed chat data")
                }
            }
        })
    }

    private fun createOffer() {
        ensurePeerConnection(createDataChannel = true)
        val target = targetCode ?: return
        peer?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                peer?.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() { sendSignal("offer", target, desc.description) }
                    override fun onSetFailure(error: String) { postStatus("WebRTC offer setup failed: $error") }
                }, desc)
            }
            override fun onCreateFailure(error: String) { postStatus("WebRTC offer failed: $error") }
        }, MediaConstraints())
    }

    private fun flushPendingIce() {
        synchronized(pendingIce) {
            pendingIce.forEach { peer?.addIceCandidate(it) }
            pendingIce.clear()
        }
    }

    private fun sendSignal(type: String, to: String, sdp: String, mid: String? = null, line: Int? = null) {
        val packet = JSONObject().put("type", type).put("toCode", to).put("sdp", sdp)
        if (type == "ice") {
            packet.put("candidate", sdp).put("sdpMid", mid).put("sdpMLineIndex", line ?: 0).remove("sdp")
        }
        socket?.send(packet.toString())
    }

    private fun postStatus(value: String) = main.post { callback.onStatus(value) }
    private fun normalize(code: String): String = code.uppercase().filter { it.isLetterOrDigit() && it in ALPHABET }
    private fun validCode(code: String): Boolean = code.length == 28 && code.all { it in ALPHABET }

    private abstract class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) {}
        override fun onSetFailure(error: String) {}
    }

    companion object {
        private const val MAX_MESSAGE_CHARS = 8000
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
