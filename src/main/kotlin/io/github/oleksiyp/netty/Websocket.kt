package io.github.oleksiyp.netty

import io.netty.handler.codec.http.websocketx.WebSocketFrame

class WebSocketHandlerScope(internal: Internal<WebSocketFrame>)
    : NettyScope<WebSocketFrame>(internal) {
    
}