package io.legado.app.help.readaloud.playback

import java.io.File
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class TtsAudioFileGuardTest {
    @Test fun acceptsAudioWithoutId3() {
        withFile(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 1, 2, 3)) {
            assertNull(TtsAudioFileGuard.validateId3Header(it))
        }
    }

    @Test fun acceptsId3TagThatFitsFile() {
        withFile(id3Header(tagBytes = 3) + byteArrayOf(1, 2, 3)) {
            assertNull(TtsAudioFileGuard.validateId3Header(it))
        }
    }

    @Test fun rejectsId3SizeThatExceedsFileLength() {
        withFile(id3Header(tagBytes = 523_857_104)) {
            assertNotNull(TtsAudioFileGuard.validateId3Header(it))
        }
    }

    @Test fun rejectsNonSynchsafeId3Size() {
        withFile(byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
            4, 0, 0, 0x80.toByte(), 0, 0, 0,
        )) {
            assertNotNull(TtsAudioFileGuard.validateId3Header(it))
        }
    }

    @Test fun rejectsOversizedSynthesizedAudio() {
        withFile(byteArrayOf(1, 2, 3)) {
            assertNotNull(TtsAudioFileGuard.validateId3Header(it, maxAudioBytes = 2))
        }
    }

    private fun id3Header(tagBytes: Int): ByteArray {
        val size = tagBytes.coerceAtLeast(0)
        return byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0,
            ((size shr 21) and 0x7F).toByte(),
            ((size shr 14) and 0x7F).toByte(),
            ((size shr 7) and 0x7F).toByte(),
            (size and 0x7F).toByte(),
        )
    }

    private inline fun withFile(bytes: ByteArray, block: (File) -> Unit) {
        val file = File.createTempFile("tts-audio-guard", ".mp3")
        try {
            file.writeBytes(bytes)
            block(file)
        } finally {
            file.delete()
        }
    }
}
