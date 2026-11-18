package io.legado.app.service

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
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
    fun `queued synthesis is rejected when cache is deleted before it acquires the lock`() = runBlocking {
        val file = File.createTempFile("tts-cache-queued", ".mp3")
        val ownerEntered = CompletableDeferred<Unit>()
        val releaseOwner = CompletableDeferred<Unit>()

        try {
            val owner = async(start = CoroutineStart.UNDISPATCHED) {
                TtsCacheFileLock.withVersionedLock(file) {
                    ownerEntered.complete(Unit)
                    releaseOwner.await()
                }
            }
            ownerEntered.await()

            // UNDISPATCHED runs the waiter until it suspends on the already-held file mutex,
            // so its generation token has been captured before invalidation.
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                TtsCacheFileLock.withVersionedLock(file) { token ->
                    TtsCacheFileLock.isCurrent(token)
                }
            }

            TtsCacheFileLock.invalidate(file)
            releaseOwner.complete(Unit)
            owner.await()

            assertFalse(queued.await())
        } finally {
            releaseOwner.complete(Unit)
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
