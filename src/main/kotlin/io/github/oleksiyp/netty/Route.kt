package io.github.oleksiyp.netty

import io.netty.handler.codec.http.HttpMethod
import java.util.regex.Matcher
import java.util.regex.Pattern

class RouteContext(private val matcher: Matcher) {
    val regexGroups = object : AbstractList<String>() {
        override fun get(index: Int): String = matcher.group(index)

        override val size: Int
            get() = matcher.groupCount() + 1
    }
}

suspend fun WebSocketHandlerScope.route(regexp: String,
                                        block: suspend RouteContext.() -> Unit) {
    val matcher = Pattern.compile(regexp).matcher(params.path())
    if (matcher.matches()) {
        RouteContext(matcher).block()
    }
}

suspend fun RequestHttpHandlerScope.route(regexp: String,
                                          method: HttpMethod? = null,
                                          methods: MutableList<HttpMethod> = mutableListOf(HttpMethod.GET),
                                          block: suspend RouteContext.() -> Unit) {
    val matcher = Pattern.compile(regexp).matcher(params.path())
    if (method != null) {
        methods += method
    }
    if (matcher.matches() && methods.contains(request.method())) {
        RouteContext(matcher).block()
    }
}

