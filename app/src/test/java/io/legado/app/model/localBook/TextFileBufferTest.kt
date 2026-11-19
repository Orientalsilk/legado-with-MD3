package io.legado.app.model.localBook

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.math.min

class TextFileBufferTest {

    @Test
    fun missWhenChapterTouchesEitherExclusiveEdge() {
        assertTrue(txtBufferMiss(true, 0, 100, 100, 120))
        assertTrue(txtBufferMiss(true, 0, 100, -20, 0))
        assertTrue(txtBufferMiss(false, 0, 100, 10, 20))
        assertFalse(txtBufferMiss(true, 0, 100, 0, 100))
        assertFalse(txtBufferMiss(true, 0, 100, 10, 20))
    }

    @Test
    fun crossWhenChapterSticksOutOfTheWindow() {
        assertTrue(txtBufferCrossesBoundary(0, 100, 90, 110))
        assertTrue(txtBufferCrossesBoundary(100, 200, 80, 150))
        assertFalse(txtBufferCrossesBoundary(0, 100, 0, 100))
        assertFalse(txtBufferCrossesBoundary(0, 100, 10, 20))
    }

    @Test
    fun windowStartAlignsDownToBlock() {
        val size = TXT_BUFFER_SIZE.toLong()
        assertEquals(0L, txtBufferWindowStart(0))
        assertEquals(0L, txtBufferWindowStart(size - 1))
        assertEquals(size, txtBufferWindowStart(size))
        assertEquals(size * 2, txtBufferWindowStart(size * 2 + 5))
    }

    @Test
    fun shortSkipAndShortReadStillLandOnTheRightBytes() {
        val data = ByteArray(1000) { (it and 0xff).toByte() }
        val input = ShortInputStream(data)
        input.skipFully(10)
        val buf = ByteArray(20)
        assertEquals(20, input.readFully(buf))
        assertEquals(data[10], buf[0])
        assertEquals(data[29], buf[19])
    }

    @Test
    fun readWindowDoesNotTrustAvailableOrPartialFill() {
        val data = ByteArray(50) { (it + 1).toByte() }
        val loaded = ShortInputStream(data).readWindow(8, windowSize = 16)
        assertEquals(16, loaded?.size)
        assertEquals(data[8], loaded?.get(0))
        assertEquals(data[23], loaded?.get(15))
    }

    @Test
    fun readWindowAtEofReturnsOnlyTheTail() {
        val data = ByteArray(10) { it.toByte() }
        val loaded = ByteArrayInputStream(data).readWindow(6, windowSize = 16)
        assertEquals(4, loaded?.size)
        assertArrayEquals(byteArrayOf(6, 7, 8, 9), loaded)
    }

    @Test
    fun skipFallsBackToReadWhenSkipReturnsZero() {
        val data = ByteArray(100) { it.toByte() }
        val input = object : InputStream() {
            var pos = 0
            override fun read(): Int = if (pos >= data.size) -1 else data[pos++].toInt() and 0xff
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= data.size) return -1
                val n = min(len, data.size - pos)
                System.arraycopy(data, pos, b, off, n)
                pos += n
                return n
            }
            override fun skip(n: Long): Long = 0L
        }
        input.skipFully(40)
        val buf = ByteArray(2)
        assertEquals(2, input.readFully(buf))
        assertEquals(40.toByte(), buf[0])
        assertEquals(41.toByte(), buf[1])
    }

    @Test
    fun readWindowPastEofIsNull() {
        val data = ByteArray(4)
        assertNull(ByteArrayInputStream(data).readWindow(4, windowSize = 8))
    }

    private class ShortInputStream(private val data: ByteArray) : InputStream() {
        private var pos = 0

        override fun read(): Int {
            if (pos >= data.size) return -1
            return data[pos++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val n = min(len, min(3, data.size - pos))
            if (n <= 0) return -1
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }

        override fun skip(n: Long): Long {
            if (pos >= data.size || n <= 0L) return 0L
            val skipped = min(n, min(2L, (data.size - pos).toLong()))
            pos += skipped.toInt()
            return skipped
        }
    }
}
