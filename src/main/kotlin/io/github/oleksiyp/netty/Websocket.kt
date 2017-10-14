package io.github.oleksiyp.netty

import io.netty.buffer.ByteBuf
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import java.util.regex.Pattern

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

suspend fun WebSocketHandlerScope.route(regexp: String,
                                        block: suspend RouteContext.() -> Unit) {
    val matcher = Pattern.compile(regexp).matcher(params.path())
    if (matcher.matches()) {
        RouteContext(matcher).block()
    }
}
