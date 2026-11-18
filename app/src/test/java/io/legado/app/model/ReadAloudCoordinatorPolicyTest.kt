package io.legado.app.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudCoordinatorPolicyTest {

    @Test
    fun defaultNarratorAndOneCharacterVoiceRequireFileSynthesis() {
        val defaultVoice = "org.nobody.multitts" to ""
        val characterVoice = "org.nobody.multitts" to "female-speaker"

        assertTrue(
            shouldUseFileSynthesisCoordinator(
                useMultiSpeaker = true,
                systemRoutes = setOf(characterVoice),
                defaultSystemRoute = defaultVoice,
                hasFileSynthesisVoice = false,
                needsSessionEffect = false,
            )
        )
    }

    @Test
    fun oneRouteUsedForBothNarratorAndCharacterCanStayOnDirectTts() {
        val onlyVoice = "org.nobody.multitts" to "speaker-a"

        assertFalse(
            shouldUseFileSynthesisCoordinator(
                useMultiSpeaker = true,
                systemRoutes = setOf(onlyVoice),
                defaultSystemRoute = onlyVoice,
                hasFileSynthesisVoice = false,
                needsSessionEffect = false,
            )
        )
    }

    @Test
    fun twoExplicitSystemVoicesRequireFileSynthesis() {
        assertTrue(
            shouldUseFileSynthesisCoordinator(
                useMultiSpeaker = true,
                systemRoutes = setOf(
                    "org.nobody.multitts" to "speaker-a",
                    "org.nobody.multitts" to "speaker-b",
                ),
                defaultSystemRoute = "org.nobody.multitts" to "",
                hasFileSynthesisVoice = false,
                needsSessionEffect = false,
            )
        )
    }

    @Test
    fun httpCloudOrSessionEffectsStillRequireFileSynthesis() {
        val defaultVoice = "org.nobody.multitts" to ""

        assertTrue(
            shouldUseFileSynthesisCoordinator(
                useMultiSpeaker = true,
                systemRoutes = setOf(defaultVoice),
                defaultSystemRoute = defaultVoice,
                hasFileSynthesisVoice = true,
                needsSessionEffect = false,
            )
        )
        assertTrue(
            shouldUseFileSynthesisCoordinator(
                useMultiSpeaker = true,
                systemRoutes = setOf(defaultVoice),
                defaultSystemRoute = defaultVoice,
                hasFileSynthesisVoice = false,
                needsSessionEffect = true,
            )
        )
    }

    @Test
    fun disabledMultiSpeakerDoesNotChangeTheSelectedPlaybackService() {
        assertFalse(
            shouldUseFileSynthesisCoordinator(
                useMultiSpeaker = false,
                systemRoutes = setOf("org.nobody.multitts" to "speaker-a"),
                defaultSystemRoute = "org.nobody.multitts" to "",
                hasFileSynthesisVoice = true,
                needsSessionEffect = true,
            )
        )
    }
}
