package io.legado.app.help.readaloud.playback

import java.io.File
import java.io.FileInputStream

/**
 * Validates the leading ID3v2 header before an audio file is passed to Media3.
 * Corrupt ID3 size fields can otherwise make extractors request huge peek buffers.
 */
internal object TtsAudioFileGuard {
    const val MAX_SYNTHESIZED_AUDIO_BYTES = 64L * 1024 * 1024
    const val MAX_ID3_TAG_BYTES = 2 * 1024 * 1024
    private const val ID3_HEADER_BYTES = 10

    /**
     * Returns a problem description, or null when the file's ID3 header is safe.
     * Audio without an ID3 header is left to Media3's format sniffing.
     */
    fun validateId3Header(file: File, maxAudioBytes: Long = Long.MAX_VALUE): String? {
        if (!file.isFile || file.length() <= 0L) return "Audio file is missing or empty"
        if (file.length() > maxAudioBytes) return "Audio file exceeds $maxAudioBytes bytes"

        val header = ByteArray(ID3_HEADER_BYTES)
        val count = FileInputStream(file).use { input ->
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read < 0) break
                offset += read
            }
            offset
        }
        if (count < 3 || header[0] != 'I'.code.toByte() ||
            header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()
        ) return null
        if (count != ID3_HEADER_BYTES) return "Truncated ID3 header"
        val major = header[3].toInt() and 0xFF
        val minor = header[4].toInt() and 0xFF
        if (major !in 2..4 || minor == 0xFF) return "Invalid ID3 version"
        val sizeBytes = header.copyOfRange(6, 10)
        if (sizeBytes.any { (it.toInt() and 0x80) != 0 }) return "Malformed ID3 synchsafe size"
        val tagBytes = ((sizeBytes[0].toInt() and 0x7F) shl 21) or
            ((sizeBytes[1].toInt() and 0x7F) shl 14) or
            ((sizeBytes[2].toInt() and 0x7F) shl 7) or
            (sizeBytes[3].toInt() and 0x7F)
        if (tagBytes > MAX_ID3_TAG_BYTES) return "ID3 metadata exceeds $MAX_ID3_TAG_BYTES bytes"
        val footerBytes = if (major == 4 && (header[5].toInt() and 0x10) != 0) 10L else 0L
        val declaredTotal = ID3_HEADER_BYTES + tagBytes.toLong() + footerBytes
        if (declaredTotal > file.length()) return "ID3 metadata length exceeds file size"
        return null
    }
}
