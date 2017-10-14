package io.github.oleksiyp.netty

import io.netty.buffer.ByteBuf
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketFrame

class WebSocketHandlerScope(uri: String, internal: Internal<WebSocketFrame>)
    : NettyScope<WebSocketFrame>(internal) {

    val params by lazy { QueryStringDecoder(uri) }
    init {
        internal.onCloseHandlers += {
            send(CloseWebSocketFrame())
        }
    }

    suspend fun send(text: String) {
        send(TextWebSocketFrame(text))
    }

    suspend fun send(buf: ByteBuf) {
        send(BinaryWebSocketFrame(buf))
    }
}

