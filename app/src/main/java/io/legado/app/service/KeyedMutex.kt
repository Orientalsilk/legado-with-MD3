package io.legado.app.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A per-key coroutine mutex whose entry remains registered while callers are
 * either holding the lock or waiting for it.
 *
 * Removing a Mutex immediately after releasing it is racy: a queued waiter may
 * acquire the old Mutex just before the previous owner removes the map entry.
 * A new caller can then create a second Mutex for the same key and enter the
 * critical section concurrently. Reference-counting all users (owners + waiters)
 * prevents that split-lock window and still releases idle keys from memory.
 */
internal class KeyedMutex {
    private class Entry(
        val mutex: Mutex = Mutex(),
        var users: Int = 0,
    )

    private val monitor = Any()
    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> withLock(key: String, block: suspend () -> T): T {
        val entry = synchronized(monitor) {
            val current = entries[key]
            if (current != null) {
                current.users++
                current
            } else {
                Entry(users = 1).also { entries[key] = it }
            }
        }

        try {
            return entry.mutex.withLock { block() }
        } finally {
            synchronized(monitor) {
                entry.users--
                if (entry.users == 0 && entries[key] === entry) {
                    entries.remove(key)
                }
            }
        }
    }

    internal fun registeredKeyCount(): Int = synchronized(monitor) {
        entries.size
    }
}
