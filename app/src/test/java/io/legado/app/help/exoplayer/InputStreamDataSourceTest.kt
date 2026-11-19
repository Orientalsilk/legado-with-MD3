package io.legado.app.help.exoplayer

import android.app.Application
import android.net.Uri
import androidx.media3.datasource.DataSpec
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class InputStreamDataSourceTest {
    @Test fun `failed open closes acquired stream`() {
        var closed = false
        val source = InputStreamDataSource {
            object : ByteArrayInputStream(byteArrayOf(1)) {
                override fun skip(count: Long): Long = throw IOException("failed skip")
                override fun close() { closed = true }
            }
        }
        assertTrue(runCatching { source.open(spec) }.isFailure)
        source.close()
        assertTrue(closed)
    }

    @Test fun `open skips full requested position when stream skip is partial`() {
        val source = InputStreamDataSource {
            object : ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)) {
                override fun skip(count: Long): Long = super.skip(minOf(count, 1L))
            }
        }
        source.open(DataSpec.Builder().setUri(Uri.parse("http://localhost/audio")).setPosition(3).build())
        val buffer = ByteArray(1)
        assertEquals(1, source.read(buffer, 0, 1))
        assertEquals(4, buffer[0].toInt())
        source.close()
    }

    @Test fun `reopening obtains a fresh stream and closes each one`() {
        var opened = 0
        var closed = 0
        val source = InputStreamDataSource {
            opened++
            object : ByteArrayInputStream(byteArrayOf(1)) {
                override fun close() { closed++ }
            }
        }
        repeat(2) {
            source.open(spec)
            assertEquals(1, source.read(ByteArray(1), 0, 1))
            source.close()
        }
        assertEquals(2, opened)
        assertEquals(2, closed)
    }

    private val spec get() = DataSpec.Builder().setUri(Uri.parse("http://localhost/audio")).build()
}
