package io.legado.app.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPlayChapterRequestGuardTest {
    @Test
    fun currentRequestRequiresMatchingGenerationBookAndChapter() {
        assertTrue(isCurrentAudioChapterRequest(8, 8, "book-a", "book-a", 12, 12))
        assertFalse(isCurrentAudioChapterRequest(9, 8, "book-a", "book-a", 12, 12))
        assertFalse(isCurrentAudioChapterRequest(8, 8, "book-b", "book-a", 12, 12))
        assertFalse(isCurrentAudioChapterRequest(8, 8, "book-a", "book-a", 13, 12))
        assertFalse(isCurrentAudioChapterRequest(8, 8, null, "book-a", 12, 12))
    }
}
