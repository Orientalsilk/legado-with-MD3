package io.legado.app.help.readaloud.playback

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardedTtsAudioInputStreamTest {
    @Test fun preservesOrdinaryAudioBytes() {
        val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 1, 2, 3)
        GuardedTtsAudioInputStream(ByteArrayInputStream(audio)).use {
            assertArrayEquals(audio, it.readBytes())
        }
    }

    @Test fun rejectsOversizedSynchsafeId3Header() {
        val header = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0x02, 0, 0)
        val error = runCatching { GuardedTtsAudioInputStream(ByteArrayInputStream(header)) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error?.message.orEmpty().contains("ID3 metadata too large"))
    }

    @Test fun rejectsMalformedId3SizeBytes() {
        val header = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 0x80.toByte())
        val error = runCatching { GuardedTtsAudioInputStream(ByteArrayInputStream(header)) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error?.message.orEmpty().contains("Malformed"))
    }

    @Test fun capsStreamLengthAndAllowsExactLimitEof() {
        GuardedTtsAudioInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), maxBytes = 4).use {
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), it.readBytes())
        }
        val error = runCatching {
            GuardedTtsAudioInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)), maxBytes = 4).use { it.readBytes() }
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error?.message.orEmpty().contains("exceeds"))
    }
}