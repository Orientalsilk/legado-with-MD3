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
    internal data class Token(
        val path: String,
        val globalGeneration: Long,
        val fileGeneration: Long,
    )

    private val locks = KeyedMutex()
    private val maintenanceLock = Mutex()
    private val generationMonitor = Any()
    private val fileGenerations = mutableMapOf<String, Long>()
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
            Token(path, globalGeneration, fileGenerations[path] ?: 0L)
        }
        return locks.withLock(path) { block(token) }
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
        token.globalGeneration == globalGeneration &&
            token.fileGeneration == (fileGenerations[token.path] ?: 0L)
    }

    fun invalidate(file: File) {
        val path = file.absoluteFile.path
        synchronized(generationMonitor) {
            fileGenerations[path] = (fileGenerations[path] ?: 0L) + 1L
        }
    }

    fun invalidateAll() {
        synchronized(generationMonitor) {
            globalGeneration++
            fileGenerations.clear()
        }
    }
}
