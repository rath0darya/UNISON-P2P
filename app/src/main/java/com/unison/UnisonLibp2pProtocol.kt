package com.unison

import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.multistream.ProtocolId
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.etc.types.toByteBuf
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.protocol.ProtocolMessageHandler
import io.netty.buffer.ByteBuf
import java.nio.charset.Charset
import java.util.concurrent.CompletableFuture

internal interface UnisonStreamController {
    fun send(payload: String)
}

internal typealias UnisonStreamCallback = (PeerId, String) -> Unit

internal class UnisonChatBinding(callback: UnisonStreamCallback) :
    StrictProtocolBinding<UnisonStreamController>(UNISON_PROTOCOL_ID, UnisonChatProtocol(callback))

internal const val UNISON_PROTOCOL_ID: ProtocolId = "/unison/chat/1.0.0"

internal class UnisonChatProtocol(
    private val callback: UnisonStreamCallback
) : ProtocolHandler<UnisonStreamController>(1024 * 1024, 1024 * 1024) {
    override fun onStartInitiator(stream: Stream) = startStream(stream)
    override fun onStartResponder(stream: Stream) = startStream(stream)

    private fun startStream(stream: Stream): CompletableFuture<UnisonStreamController> {
        val ready = CompletableFuture<Void>()
        val handler = StreamHandler(callback, ready)
        stream.pushHandler(handler)
        return ready.thenApply { handler }
    }

    private inner class StreamHandler(
        private val callback: UnisonStreamCallback,
        private val ready: CompletableFuture<Void>
    ) : ProtocolMessageHandler<ByteBuf>, UnisonStreamController {
        private lateinit var stream: Stream
        override fun onActivated(stream: Stream) {
            this.stream = stream
            ready.complete(null)
        }
        override fun onMessage(stream: Stream, msg: ByteBuf) {
            callback(stream.remotePeerId(), msg.toString(Charset.forName("UTF-8")))
        }
        override fun send(payload: String) {
            stream.writeAndFlush(payload.toByteArray(Charsets.UTF_8).toByteBuf())
        }
    }
}
