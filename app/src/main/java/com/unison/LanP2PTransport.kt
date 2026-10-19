package com.unison

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct, backend-free LAN transport. Devices must be on the same reachable local network.
 * Pairing code is used for discovery and a mutual application-level identity check.
 * This is not an Internet/NAT-traversal solution and does not provide end-to-end encryption
 * for socket payloads; use only on trusted networks until an authenticated encrypted channel
 * is implemented.
 */
class LanP2PTransport(
    context: Context,
    private val myCode: String,
    private val displayName: String,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(status: String)
        fun onMessage(text: String, fromName: String, timestamp: Long)
    }

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    @Volatile private var expectedPeerCode: String? = null
    @Volatile private var activeSocket: Socket? = null
    @Volatile private var activeWriter: BufferedWriter? = null
    @Volatile private var peerName: String = "Peer"

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(0))
                }
                val port = server!!.localPort
                postStatus("Searching on this Wi-Fi network")
                advertise(port)
                acceptLoop()
            } catch (e: Exception) {
                if (running.get()) postStatus("Local connection unavailable: ${e.message ?: "network error"}")
            }
        }
        discover()
    }

    fun connectTo(code: String) {
        val normalized = normalize(code)
        if (normalized.length != 28 || normalized.any { it !in ALPHABET }) {
            postStatus("Invalid pairing code")
            return
        }
        if (normalized == normalize(myCode)) {
            postStatus("That is your own pairing code")
            return
        }
        expectedPeerCode = normalized
        postStatus("Looking for that peer on the same Wi-Fi network…")
        // Discovery callback resolves matching services. Restart discovery to force a fresh scan.
        stopDiscovery()
        discover()
    }

    fun sendMessage(text: String): Boolean {
        if (text.isBlank()) return false
        val writer = activeWriter ?: return false
        return try {
            synchronized(writer) {
                writer.write(JSONObject().put("type", "message")
                    .put("text", text)
                    .put("timestamp", System.currentTimeMillis()).toString())
                writer.newLine()
                writer.flush()
            }
            true
        } catch (e: Exception) {
            postStatus("Peer connection lost")
            closeActive()
            false
        }
    }

    fun isConnected(): Boolean = activeSocket?.isConnected == true && activeSocket?.isClosed == false

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        stopDiscovery()
        try { registrationListener?.let { nsd.unregisterService(it) } } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        closeActive()
        executor.shutdownNow()
    }

    private fun advertise(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = normalize(myCode)
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val callback = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                registrationListener = this
                postStatus("Available for direct pairing on this Wi-Fi")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                postStatus("Could not advertise on local network (code $errorCode)")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, callback) }
        catch (e: Exception) { postStatus("Local discovery could not start") }
    }

    private fun discover() {
        if (!running.get()) return
        val callback = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                val wanted = expectedPeerCode ?: return
                if (service.serviceType.trimEnd('.') != SERVICE_TYPE.trimEnd('.') ||
                    normalize(service.serviceName) != wanted) return
                try {
                    nsd.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            postStatus("Peer found but could not resolve it (code $errorCode)")
                        }
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val address = serviceInfo.host ?: return
                            val port = serviceInfo.port
                            executor.execute { connectSocket(address, port, wanted) }
                        }
                    })
                } catch (e: Exception) {
                    postStatus("Could not resolve peer on local network")
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                postStatus("Local peer discovery failed (code $errorCode)")
                try { nsd.stopServiceDiscovery(this) } catch (_: Exception) {}
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                try { nsd.stopServiceDiscovery(this) } catch (_: Exception) {}
            }
        }
        discoveryListener = callback
        try { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, callback) }
        catch (e: Exception) { postStatus("Local discovery unavailable") }
    }

    private fun stopDiscovery() {
        val old = discoveryListener ?: return
        discoveryListener = null
        try { nsd.stopServiceDiscovery(old) } catch (_: Exception) {}
    }

    private fun acceptLoop() {
        while (running.get()) {
            try {
                val socket = server?.accept() ?: break
                socket.tcpNoDelay = true
                executor.execute { establish(socket, expectedPeerCode) }
            } catch (e: Exception) {
                if (running.get()) postStatus("Listening stopped: ${e.message ?: "network error"}")
                break
            }
        }
    }

    private fun connectSocket(address: InetAddress, port: Int, wanted: String) {
        if (!running.get() || expectedPeerCode != wanted || !connecting.compareAndSet(false, true)) return
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            socket.tcpNoDelay = true
            establish(socket, wanted)
        } catch (e: Exception) {
            postStatus("Could not connect. Keep both phones on the same Wi-Fi and try again.")
        } finally {
            connecting.set(false)
        }
    }

    private fun establish(socket: Socket, expected: String?) {
        try {
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            synchronized(writer) {
                writer.write(JSONObject().put("type", "hello").put("code", normalize(myCode))
                    .put("name", displayName.ifBlank { "Unison user" }).toString())
                writer.newLine()
                writer.flush()
            }
            val hello = reader.readLine()?.let { JSONObject(it) } ?: throw IllegalStateException("No peer handshake")
            val remoteCode = normalize(hello.optString("code"))
            if (hello.optString("type") != "hello" || remoteCode.length != 28 ||
                remoteCode.any { it !in ALPHABET } || (expected != null && remoteCode != expected)) {
                throw SecurityException("Peer code did not match")
            }
            if (remoteCode == normalize(myCode)) throw SecurityException("Self connection rejected")
            if (expected == null) expectedPeerCode = remoteCode
            val old = activeSocket
            activeSocket = socket
            activeWriter = writer
            peerName = hello.optString("name").ifBlank { "Peer" }
            socket.soTimeout = 0
            try { old?.close() } catch (_: Exception) {}
            postStatus("Connected directly to $peerName")
            while (running.get() && !socket.isClosed) {
                val line = reader.readLine() ?: break
                val packet = JSONObject(line)
                if (packet.optString("type") == "message") {
                    val body = packet.optString("text")
                    if (body.isNotBlank() && body.length <= MAX_MESSAGE_CHARS) {
                        val stamp = packet.optLong("timestamp", System.currentTimeMillis())
                        main.post { listener.onMessage(body, peerName, stamp) }
                    }
                }
            }
            if (activeSocket === socket) {
                activeSocket = null
                activeWriter = null
                postStatus("Peer disconnected")
            }
            socket.close()
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            if (e !is java.net.SocketException || running.get()) {
                postStatus(if (e is SecurityException) "Pairing rejected: code mismatch" else "Connection ended; retry pairing if needed")
            }
        }
    }

    private fun closeActive() {
        try { activeSocket?.close() } catch (_: Exception) {}
        activeSocket = null
        activeWriter = null
    }

    private fun postStatus(status: String) {
        main.post { listener.onStatus(status) }
    }

    companion object {
        private const val SERVICE_TYPE = "_unison._tcp."
        private const val CONNECT_TIMEOUT_MS = 6000
        private const val HANDSHAKE_TIMEOUT_MS = 10000
        private const val MAX_MESSAGE_CHARS = 8000
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private fun normalize(code: String): String = code.uppercase().filter { it.isLetterOrDigit() && it in ALPHABET }
    }
}
