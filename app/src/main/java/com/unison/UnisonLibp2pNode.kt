package com.unison

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import io.libp2p.core.Discoverer
import io.libp2p.core.PeerId
import io.libp2p.core.PeerInfo
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.discovery.MDnsDiscovery
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Uses the open libp2p host, Noise-secured transport and a versioned Unison stream protocol.
 * This first adapter uses mDNS for discovery; mDNS is local-network discovery only. A
 * worldwide pairing-code directory, public rendezvous and relay/hole-punch configuration
 * are not supplied by libp2p automatically and are not claimed by this class.
 */
internal class UnisonLibp2pNode(
    context: Context,
    private val ownCode: String,
    private val displayName: String,
    private val callback: Callback
) {
    internal interface Callback {
        fun onStatus(status: String)
        fun onMessage(text: String, senderName: String, timestamp: Long)
    }

    private data class PeerSession(
        var name: String,
        var code: String = "",
        val controller: UnisonStreamController
    )

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)
    private val sessions = ConcurrentHashMap<PeerId, PeerSession>()
    @Volatile private var expectedCode: String? = null
    private val privateAddress = privateNetworkAddress()
    private val wifiLock: WifiManager.MulticastLock? = runCatching {
        (app.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("unison-libp2p-discovery").apply { setReferenceCounted(false) }
    }.getOrNull()
    private val node = host {
        protocols { +UnisonChatBinding(::onWireMessage) }
        network { listen("/ip4/0.0.0.0/tcp/0") }
    }
    private val peerFinder: Discoverer = MDnsDiscovery(node, address = privateAddress)

    val peerId: PeerId get() = node.peerId

    fun start() {
        if (!started.compareAndSet(false, true)) return
        try {
            wifiLock?.acquire()
            node.start().get()
            peerFinder.newPeerFoundListeners += { info -> onPeerFound(info) }
            peerFinder.start()
            postStatus("libp2p started; looking for peers on this local network")
        } catch (e: Exception) {
            postStatus("libp2p could not start: ${e.cause?.message ?: e.message ?: "unknown error"}")
        }
    }

    fun connectTo(code: String) {
        val normalized = normalize(code)
        if (!validCode(normalized)) {
            postStatus("Invalid pairing code")
            return
        }
        if (normalized == normalize(ownCode)) {
            postStatus("That is your own pairing code")
            return
        }
        expectedCode = normalized
        val matching = sessions.values.firstOrNull { normalize(it.code) == normalized }
        if (matching != null) postStatus("Connected to ${matching.name} using libp2p")
        else postStatus("Searching for that code through libp2p local discovery…")
    }

    fun sendMessage(text: String): Boolean {
        if (text.isBlank()) return false
        val targetCode = expectedCode ?: return false
        val target = sessions.values.firstOrNull { normalize(it.code) == targetCode } ?: return false
        return runCatching {
            target.controller.send(JSONObject().put("type", "message")
                .put("text", text.take(MAX_MESSAGE_CHARS))
                .put("timestamp", System.currentTimeMillis()).toString())
            true
        }.getOrDefault(false)
    }

    fun isConnected(): Boolean {
        val target = expectedCode ?: return false
        return sessions.values.any { normalize(it.code) == target }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { peerFinder.stop() }
        runCatching { node.stop().get() }
        sessions.clear()
        runCatching { if (wifiLock?.isHeld == true) wifiLock.release() }
    }

    private fun onPeerFound(info: PeerInfo) {
        if (info.peerId == node.peerId || sessions.containsKey(info.peerId)) return
        val address = info.addresses.firstOrNull() ?: return
        try {
            val binding = UnisonChatBinding(::onWireMessage)
            val connection = binding.dial(node, info.peerId, address)
            val stream = connection.stream.get()
            val controller = connection.controller.get()
            sessions[info.peerId] = PeerSession(info.peerId.toBase58(), controller = controller)
            controller.send(JSONObject().put("type", "hello")
                .put("code", normalize(ownCode))
                .put("name", displayName.ifBlank { "Unison user" }).toString())
            stream.closeFuture().thenAccept {
                sessions.remove(info.peerId)
                postStatus("libp2p peer disconnected")
            }
        } catch (_: Exception) {
            // mDNS can report a peer just as it disappears; a later discovery event can retry.
        }
    }

    private fun onWireMessage(peerId: PeerId, raw: String) {
        try {
            val packet = JSONObject(raw)
            when (packet.optString("type")) {
                "hello" -> {
                    val code = normalize(packet.optString("code"))
                    if (!validCode(code) || code == normalize(ownCode)) return
                    val session = sessions[peerId] ?: return
                    session.code = code
                    session.name = packet.optString("name").ifBlank { peerId.toBase58() }
                    val expected = expectedCode
                    if (expected != null && expected != code) {
                        sessions.remove(peerId)
                        postStatus("A peer was found, but its pairing code did not match")
                    } else {
                        // Allow a receiver to join when the initiator is the one who entered a code.
                        if (expected == null) expectedCode = code
                        postStatus("Connected to ${session.name} using libp2p")
                    }
                }
                "message" -> {
                    val session = sessions[peerId] ?: return
                    val expected = expectedCode ?: return
                    if (normalize(session.code) != expected) return
                    val text = packet.optString("text")
                    if (text.isNotBlank() && text.length <= MAX_MESSAGE_CHARS) {
                        main.post { callback.onMessage(text, session.name, packet.optLong("timestamp", System.currentTimeMillis())) }
                    }
                }
            }
        } catch (_: Exception) {
            postStatus("Ignored an invalid libp2p message")
        }
    }

    private fun postStatus(value: String) = main.post { callback.onStatus(value) }

    private fun normalize(code: String): String =
        code.uppercase().filter { it.isLetterOrDigit() && it in ALPHABET }

    private fun validCode(code: String): Boolean =
        code.length == 28 && code.all { it in ALPHABET }

    private fun privateNetworkAddress(): InetAddress {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
        }.getOrNull() ?: InetAddress.getLoopbackAddress()
    }

    companion object {
        private const val MAX_MESSAGE_CHARS = 8000
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
