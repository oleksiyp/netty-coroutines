package io.github.oleksiyp.proxy.service

import java.util.Collections.synchronizedList

class Log {
    val msgs = synchronizedList(mutableListOf<String>())
    val subscribers = synchronizedList(mutableListOf<(String) -> Unit>())

    fun subscribe(subscriber: (String) -> Unit): AutoCloseable {
        synchronized(msgs) {
            subscribers.add(subscriber)

            for (msg in msgs) {
                subscriber(msg)
            }
        }

        return AutoCloseable {
            subscribers.remove(subscriber)
        }
    }

    fun append(msg: String) {
        synchronized(msgs) {
            msgs.add(msg)

            synchronized(subscribers) {
                for (subscriber in subscribers) {
                    subscriber(msg)
                }
            }
        }
    }
}