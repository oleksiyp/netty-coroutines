package io.github.oleksiyp.json

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

open class SeparableContext(private val out: StringBuilder) {
    var first = true

    protected fun separate() {
        if (!first) {
            out.append(',')
        }
        first = false

    }
}

class JsonContext(internal val out: StringBuilder) : SeparableContext(out) {
    fun hash(block: HashJsonContext.() -> Unit) {
        separate()
        out.append('{')
        HashJsonContext(out).block()
        out.append('}')
    }

    fun seq(block: JsonContext.() -> Unit) {
        separate()
        out.append('[')
        JsonContext(out).block()
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

class HashJsonContext(private val out: StringBuilder) : SeparableContext(out) {
    operator fun String.rangeTo(block: JsonContext.() -> Unit) {
        separate()
        out.jsonEscape(this)
        out.append(":")
        JsonContext(out).block()
    }
}
