package io.github.oleksiyp.proxy

import java.util.Collections.synchronizedList

class Log {
    val msgs = synchronizedList(mutableListOf<String>())
    val subscribers = synchronizedList(mutableListOf<(String) -> Unit>())

    fun subscribe(subscriber: (String) -> Unit): () -> Unit {
        synchronized(msgs) {
            subscribers.add(subscriber)

            for (msg in msgs) {
                subscriber(msg)
            }
        }

        return {
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