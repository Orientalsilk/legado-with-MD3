package io.legado.app.model.localBook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException

class TextFileBufferTest {

    @Test
    fun txtBufferMiss_whenEmptyOrOutside() {
        assertTrue(txtBufferMiss(0, 10, 0, 0))
        assertTrue(txtBufferMiss(100, 10, 0, 50))
        assertFalse(txtBufferMiss(10, 5, 0, 20))
    }

    @Test
    fun txtBufferCrossesBoundary() {
        assertTrue(txtBufferCrossesBoundary(8, 5, 0, 10))
        assertFalse(txtBufferCrossesBoundary(0, 10, 0, 20))
        assertFalse(txtBufferCrossesBoundary(5, 3, 0, 10))
    }

    @Test
    fun skipFully_readsExact() {
        val data = ByteArray(100) { it.toByte() }
        val stream = ByteArrayInputStream(data)
        skipFully(stream, 30)
        val buf = ByteArray(10)
        readFully(stream, buf, 0, 10)
        assertEquals(30.toByte(), buf[0])
        assertEquals(39.toByte(), buf[9])
    }

    @Test(expected = EOFException::class)
    fun skipFully_throwsOnShortStream() {
        val stream = ByteArrayInputStream(ByteArray(5))
        skipFully(stream, 10)
    }

    @Test
    fun readWindow_returnsAlignedSlice() {
        val data = ByteArray(50) { it.toByte() }
        val stream = ByteArrayInputStream(data)
        val window = readWindow(stream, 10, 20, 5)
        assertEquals(20, window.size)
        assertEquals(10.toByte(), window[0])
        assertEquals(29.toByte(), window[19])
    }
}
