package io.legado.app.domain.usecase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentifyBookCharactersUseCaseTest {

    @Test
    fun `default character prompt does not assign TTS voices`() {
        val prompt = IdentifyBookCharactersUseCase.defaultPrompt()

        assertTrue(prompt.contains("\"characters\""))
        assertTrue(prompt.contains("\"aliases\""))
        assertTrue(prompt.contains("\"personality\""))
        assertTrue(prompt.contains("Do not infer voice gender"))
        assertTrue(prompt.contains("Do not assign any TTS voice"))
        assertFalse(prompt.contains("\"voiceAgeBand\""))
        assertFalse(prompt.contains("\"voiceGender\""))
        assertFalse(prompt.contains("voice pool:"))
    }
}
