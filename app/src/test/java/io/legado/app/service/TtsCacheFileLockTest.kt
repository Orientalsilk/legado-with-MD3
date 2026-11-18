package io.legado.app.service

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsCacheFileLockTest {
    @Test
    fun `invalidation during synthesis marks its token stale`() = runBlocking {
        val file = File.createTempFile("tts-cache-lock", ".mp3")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        try {
            val synthesis = async {
                TtsCacheFileLock.withVersionedLock(file) { token ->
                    entered.complete(Unit)
                    release.await()
                    TtsCacheFileLock.isCurrent(token)
                }
            }

            entered.await()
            TtsCacheFileLock.invalidate(file)
            release.complete(Unit)

            assertFalse(synthesis.await())
        } finally {
            release.complete(Unit)
            file.delete()
        }
    }

    @Test
    fun `global clear invalidates all previously captured tokens`() = runBlocking {
        val file = File.createTempFile("tts-cache-clear", ".mp3")
        try {
            val wasCurrent = TtsCacheFileLock.withVersionedLock(file) { token ->
                assertTrue(TtsCacheFileLock.isCurrent(token))
                TtsCacheFileLock.invalidateAll()
                TtsCacheFileLock.isCurrent(token)
            }
            assertFalse(wasCurrent)
        } finally {
            file.delete()
        }
    }
}
