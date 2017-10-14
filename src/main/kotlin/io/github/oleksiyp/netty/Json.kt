package io.github.oleksiyp.netty

private fun StringBuilder.jsonEscape(value : Any) {
    append('\"')
    value.toString().forEach {
        when(it) {
            '\"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\\' -> append("\\\\")
            else -> append(it)
        }
    }
    append('\"')
}

open class SeparableScope(private val out: StringBuilder) {
    var first = true

    protected fun separate() {
        if (!first) {
            out.append(',')
        }
        first = false

    }
}

class JsonScope(internal val out: StringBuilder) : SeparableScope(out) {
    fun hash(block: HashJsonScope.() -> Unit) {
        separate()
        out.append('{')
        HashJsonScope(out).block()
        out.append('}')
    }

    fun seq(block: JsonScope.() -> Unit) {
        separate()
        out.append('[')
        JsonScope(out).block()
        out.append(']')
    }

    fun num(value: Number) {
        separate()
        out.append(value)
    }

    fun str(value: Any) {
        separate()
        out.jsonEscape(value)
    }
}

class HashJsonScope(private val out: StringBuilder) : SeparableScope(out) {
    operator fun String.rangeTo(block: JsonScope.() -> Unit) {
        separate()
        out.jsonEscape(this)
        out.append(":")
        JsonScope(out).block()
    }

    operator fun String.rangeTo(value: Any) {
        rangeTo({ str(value) })
    }

    operator fun String.rangeTo(value: Number) {
        rangeTo({ num(value) })
    }
}
