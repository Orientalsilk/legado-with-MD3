package io.legado.app.service

import io.legado.app.feature.reader.core.cast.CastMarkers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudTtsInputSanitizerTest {

    @Test
    fun normalizesSixAsciiDotsAndKeepsOriginalProgressOffsets() {
        val original = "我......还没说完"
        val spoken = CastMarkers.Spoken.passThrough(original, base = 30)

        val cleaned = ReadAloudTtsInputSanitizer.sanitize(spoken)

        assertEquals("我……还没说完", cleaned.text)
        assertEquals(30, cleaned.storedOf(0))
        assertEquals(31, cleaned.storedOf(1))
        assertEquals(37, cleaned.storedOf(3))
        assertEquals(30 + original.length, cleaned.storedOf(cleaned.text.length))
    }

    @Test
    fun normalizesLongUnicodeEllipsisRuns() {
        val cleaned = ReadAloudTtsInputSanitizer.sanitizeText("等………………一下")

        assertEquals("等……一下", cleaned.text)
    }

    @Test
    fun replacesSsmlReservedCharactersWithoutChangingTheirMeaningfulText() {
        val cleaned = ReadAloudTtsInputSanitizer.sanitizeText("A&B <C>")

        assertEquals("A＆B ＜C＞", cleaned.text)
    }

    @Test
    fun removesInvalidAndInvisibleControlsAndNormalizesLineSeparators() {
        val original = "甲\u0000乙\u200B丙\u202E丁\t戊\n己"
        val cleaned = ReadAloudTtsInputSanitizer.sanitizeText(original)

        assertEquals("甲乙丙丁 戊 己", cleaned.text)
        assertEquals(original.length, cleaned.sourceOffsets.last())
        assertTrue(cleaned.sourceOffsets.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun preservesOrdinaryChinesePunctuationAndSupplementaryCharacters() {
        val original = "他问：“我……？！”😊"

        val cleaned = ReadAloudTtsInputSanitizer.sanitizeText(original)

        assertEquals(original, cleaned.text)
    }

    @Test
    fun safeInputReturnsTheOriginalSpokenMapping() {
        val spoken = CastMarkers.Spoken.passThrough("正常的朗读文本。")

        assertSame(spoken, ReadAloudTtsInputSanitizer.sanitize(spoken))
    }

    @Test
    fun sanitizationIsIdempotent() {
        val once = ReadAloudTtsInputSanitizer.sanitizeText("我......&<x>\u0000")
        val twice = ReadAloudTtsInputSanitizer.sanitizeText(once.text)

        assertEquals(once.text, twice.text)
    }
}
