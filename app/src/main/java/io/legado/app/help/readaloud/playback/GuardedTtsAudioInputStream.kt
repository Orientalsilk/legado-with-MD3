package io.legado.app.help.readaloud.playback

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream

/**
 * Rejects malformed/oversized ID3 headers and caps a single HTTP TTS audio response.
 * A normal synthesized utterance should be far smaller than this generous limit.
 */
internal class GuardedTtsAudioInputStream private constructor(
    private val pushback: PushbackInputStream,
    private val maxBytes: Long,
    private val maxId3TagBytes: Int,
) : FilterInputStream(pushback) {

    constructor(
        source: InputStream,
        maxBytes: Long = MAX_TTS_AUDIO_BYTES,
        maxId3TagBytes: Int = MAX_ID3_TAG_BYTES,
    ) : this(PushbackInputStream(source, ID3_HEADER_SIZE), maxBytes, maxId3TagBytes)

    private var bytesRead = 0L

    init {
        require(maxBytes > 0)
        require(maxId3TagBytes >= 0)
        validateId3Header()
    }

    private fun validateId3Header() {
        val header = ByteArray(ID3_HEADER_SIZE)
        var count = 0
        while (count < header.size) {
            val read = pushback.read(header, count, header.size - count)
            if (read < 0) break
            count += read
        }
        if (count > 0) pushback.unread(header, 0, count)
        if (count != ID3_HEADER_SIZE ||
            header[0] != 'I'.code.toByte() ||
            header[1] != 'D'.code.toByte() ||
            header[2] != '3'.code.toByte()
        ) return

        val sizeBytes = header.copyOfRange(6, 10)
        if (sizeBytes.any { it.toInt() and 0x80 != 0 }) {
            throw IOException("Malformed HTTP TTS ID3 size header")
        }
        val tagBytes = ((sizeBytes[0].toInt() and 0x7F) shl 21) or
            ((sizeBytes[1].toInt() and 0x7F) shl 14) or
            ((sizeBytes[2].toInt() and 0x7F) shl 7) or
            (sizeBytes[3].toInt() and 0x7F)
        if (tagBytes > maxId3TagBytes) {
            throw IOException("HTTP TTS ID3 metadata too large: $tagBytes bytes")
        }
    }

    override fun read(): Int {
        checkLimit()
        val value = pushback.read()
        if (value >= 0) bytesRead++
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        checkLimit()
        val allowed = (maxBytes - bytesRead).coerceAtMost(length.toLong()).toInt()
        if (allowed == 0) {
            // At the exact limit, distinguish a legitimate EOF from a truncated oversized stream.
            checkLimit()
            return -1
        }
        val count = pushback.read(buffer, offset, allowed)
        if (count > 0) bytesRead += count
        return count
    }

    private fun checkLimit() {
        if (bytesRead < maxBytes) return
        if (pushback.read() < 0) return
        throw IOException("HTTP TTS audio stream exceeds $maxBytes bytes")
    }

    companion object {
        const val MAX_TTS_AUDIO_BYTES = 64L * 1024 * 1024
        const val MAX_ID3_TAG_BYTES = 2 * 1024 * 1024
        private const val ID3_HEADER_SIZE = 10
    }
}
