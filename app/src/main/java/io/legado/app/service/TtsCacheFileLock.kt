package io.legado.app.service

import java.io.File

/**
 * Shared lock for the HTTP TTS cache.
 *
 * Playback synthesis and the cache-management screen must use the same key and lock;
 * otherwise a cache clear can race an in-flight .part -> .mp3 commit and leave the
 * just-deleted cache entry behind.
 */
internal object TtsCacheFileLock {
    private val locks = KeyedMutex()

    suspend fun <T> withLock(file: File, block: suspend () -> T): T =
        locks.withLock(file.absoluteFile.path, block)
}
