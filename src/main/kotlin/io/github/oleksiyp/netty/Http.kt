package io.github.oleksiyp.netty

import io.github.oleksiyp.json.JsonScope
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.http.*
import java.nio.charset.Charset


class ErrorHttpHandlerScope(val cause: Throwable,
                            internal: Internal<HttpRequest>) : HttpHandlerScope(internal)

class RequestHttpHandlerScope(val request: HttpRequest,
                              internal: Internal<HttpRequest>) : HttpHandlerScope(internal) {

    val params by lazy { QueryStringDecoder(request.uri()) }

    override val keepAlive
        get() = HttpUtil.isKeepAlive(request)

}

abstract class HttpHandlerScope(internal: Internal<HttpRequest>) : NettyScope<HttpRequest>(internal) {
    private var done = false
    private var responded = false
    private var sendLastContent = false

    protected open val keepAlive = false

    suspend fun response(response: HttpResponse) {
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
        }

        internal.send(response)
        sendLastContent = response !is FullHttpResponse
        responded = true
    }

    suspend fun response(html: String = "",
                         contentType: String = "text/html",
                         charset: Charset = Charset.forName("UTF-8"),
                         status: HttpResponseStatus = HttpResponseStatus.OK) {


        val bytes = html.toByteArray(charset)

        val data = alloc().buffer(bytes.size).writeBytes(bytes)

        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, data)

        with(response.headers()) {
            set(HttpHeaderNames.CONTENT_TYPE, contentType)
            set(HttpHeaderNames.CONTENT_LENGTH, data.writerIndex())
        }

        response(response)
    }

    suspend fun jsonResponse(block: JsonScope.() -> Unit) {
        val str = StringBuilder()
        JsonScope(str).block()
        response(str.toString(), "application/json")
    }

    suspend fun content(response: HttpContent) {
        if (!responded) {
            throw RuntimeException("no response send before data");
        }
        internal.send(response)
    }

    suspend fun content(response: ByteBuf) {
        content(DefaultHttpContent(response))
    }

    suspend fun end() {
        if (done) {
            return
        }
        done = true
        if (!responded) {
            val notFoundResponse = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND)
            notFoundResponse.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0)
            response(notFoundResponse)
            sendLastContent = false
        }
        if (sendLastContent) {
            internal.send(LastHttpContent.EMPTY_LAST_CONTENT)
        }
    }

    suspend fun alloc() = internal.channel.alloc()

    fun QueryStringDecoder.firstParam(key: String) = parameters()?.get(key)?.getOrNull(0)
    fun QueryStringDecoder.firstIntParam(key: String) = firstParam(key)?.toIntOrNull()
}

suspend fun HttpHandlerScope.staticResourcesHandler(path: String, resourcesBase: String) {
    val resource = this.javaClass.classLoader.getResource(resourcesBase + "/" + path)
    if (resource == null) {
        response(DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND))
        return
    }

    val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
    with(response.headers()) {
        set(HttpHeaderNames.CONTENT_TYPE, "text/html")
    }
    response(response)

    resource.openStream().use {
        val bytes = ByteArray(512 * 1024)
        while (isActive) {
            val r = it.read(bytes)
            if (r <= 0) {
                break
            }
            content(alloc().buffer(r).writeBytes(bytes))
        }
    }
}
