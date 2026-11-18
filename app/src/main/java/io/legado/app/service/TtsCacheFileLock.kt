package io.legado.app.service

import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared lock and invalidation generation for the HTTP TTS cache.
 *
 * Playback synthesis and cache management must use the same key and lock. The
 * generation token also prevents a synthesis job that was already queued when
 * the user cleared the cache from recreating the deleted file afterward.
 */
internal object TtsCacheFileLock {
    internal class FileGenerationState(
        var generation: Long = 0L,
        var users: Int = 0,
    )

    internal class Token internal constructor(
        val path: String,
        val globalGeneration: Long,
        val fileGeneration: Long,
        internal val state: FileGenerationState,
    )

    private val locks = KeyedMutex()
    private val maintenanceLock = Mutex()
    private val generationMonitor = Any()
    // States exist only while a versioned lock is active or queued. This avoids
    // retaining one map entry forever for every cache file ever deleted.
    private val fileGenerations = mutableMapOf<String, FileGenerationState>()
    private var globalGeneration = 0L
    private var serviceSessionGeneration = 0L

    suspend fun <T> withLock(file: File, block: suspend () -> T): T =
        locks.withLock(file.absoluteFile.path, block)

    /**
     * Serializes cache-wide maintenance (startup sweep / service shutdown cleanup).
     * Per-file synthesis continues to use keyed locks, so pre-synthesis remains parallel.
     */
    suspend fun <T> withMaintenanceLock(block: suspend () -> T): T =
        maintenanceLock.withLock { block() }

    suspend fun <T> withVersionedLock(
        file: File,
        block: suspend (Token) -> T,
    ): T {
        val path = file.absoluteFile.path
        val token = synchronized(generationMonitor) {
            val state = fileGenerations.getOrPut(path) { FileGenerationState() }
            state.users++
            Token(path, globalGeneration, state.generation, state)
        }

        try {
            return locks.withLock(path) { block(token) }
        } finally {
            synchronized(generationMonitor) {
                val state = token.state
                state.users--
                if (state.users == 0 && fileGenerations[path] === state) {
                    fileGenerations.remove(path)
                }
            }
        }
    }

    /** Registers the newest live service instance so stale onDestroy cleanup cannot touch it. */
    fun beginServiceSession(): Long = synchronized(generationMonitor) {
        serviceSessionGeneration++
        serviceSessionGeneration
    }

    fun isCurrentServiceSession(generation: Long): Boolean = synchronized(generationMonitor) {
        generation == serviceSessionGeneration
    }

    fun isCurrent(token: Token): Boolean = synchronized(generationMonitor) {
        val state = token.state
        token.globalGeneration == globalGeneration &&
            fileGenerations[token.path] === state &&
            token.fileGeneration == state.generation
    }

    fun invalidate(file: File) {
        val path = file.absoluteFile.path
        synchronized(generationMonitor) {
            // If there are no owners or queued waiters, there is no old token to invalidate.
            fileGenerations[path]?.let { it.generation++ }
        }
    }

    fun invalidateAll() {
        synchronized(generationMonitor) {
            globalGeneration++
            fileGenerations.clear()
        }
    }
}
