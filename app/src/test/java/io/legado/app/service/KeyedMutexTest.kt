package io.legado.app.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KeyedMutexTest {

    @Test
    fun queuedWaitersKeepTheSameKeyLockedUntilAllCallersLeave() = runBlocking {
        val keyedMutex = KeyedMutex()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondAttempting = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val thirdAttempting = CompletableDeferred<Unit>()
        val thirdEntered = CompletableDeferred<Unit>()

        val first = launch {
            keyedMutex.withLock("same-audio-file") {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()

        val second = launch {
            secondAttempting.complete(Unit)
            keyedMutex.withLock("same-audio-file") {
                secondEntered.complete(Unit)
                releaseSecond.await()
            }
        }
        secondAttempting.await()
        yield() // let the second caller queue on the first caller's Mutex

        releaseFirst.complete(Unit)
        secondEntered.await()

        // The first caller has returned, but the second now owns the lock.
        // A third caller must not create a fresh lock for the same file.
        val third = launch {
            thirdAttempting.complete(Unit)
            keyedMutex.withLock("same-audio-file") {
                thirdEntered.complete(Unit)
            }
        }
        thirdAttempting.await()
        yield()
        assertFalse("third caller entered while second still held the key", thirdEntered.isCompleted)

        releaseSecond.complete(Unit)
        thirdEntered.await()

        first.join()
        second.join()
        third.join()
        assertEquals(0, keyedMutex.registeredKeyCount())
    }

    @Test
    fun differentKeysCanProceedIndependently() = runBlocking {
        val keyedMutex = KeyedMutex()
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val first = launch {
            keyedMutex.withLock("file-a") {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()

        val second = launch {
            keyedMutex.withLock("file-b") {
                secondEntered.complete(Unit)
            }
        }
        secondEntered.await()

        releaseFirst.complete(Unit)
        first.join()
        second.join()
        assertEquals(0, keyedMutex.registeredKeyCount())
    }
}
