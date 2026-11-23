package io.legado.app.domain.usecase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class RunCatchingCancellableTest {

    @Test
    fun `rethrows cancellation instead of converting it to fallback failure`() {
        val cancellation = CancellationException("user stopped AI analysis")

        try {
            runBlocking {
                runCatchingCancellable<Int> { throw cancellation }
            }
            fail("CancellationException must propagate to the caller")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun `keeps ordinary provider failures eligible for fallback`() = runBlocking {
        val result = runCatchingCancellable<String> {
            throw IllegalStateException("provider unavailable")
        }

        assertEquals("offline fallback", result.getOrDefault("offline fallback"))
    }

    @Test
    fun `returns successful AI result unchanged`() = runBlocking {
        val result = runCatchingCancellable { "AI result" }

        assertEquals("AI result", result.getOrThrow())
    }
}
