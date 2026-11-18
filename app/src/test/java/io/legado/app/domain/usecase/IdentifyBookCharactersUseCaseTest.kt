package io.legado.app.domain.usecase

import io.legado.app.data.entities.BookCharacterProfile
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
        assertFalse(prompt.contains("male_lead"))
        assertFalse(prompt.contains("voice pool:"))
    }
    @Test
    fun `identity key ignores spacing and harmless punctuation`() {
        assertTrue(
            normalizeCharacterIdentityKey("  林·晚  ") ==
                normalizeCharacterIdentityKey("林晚")
        )
    }

    @Test
    fun `alias merge preserves stored aliases and rejects aliases claimed by another profile`() {
        val merged = mergeCharacterAliases(
            canonicalName = "林晚",
            existingAliases = listOf("晚晚", "用户补充称呼"),
            candidateAliases = listOf("小晚", "周明"),
            candidateName = "林·晚",
            claimedByOthers = setOf(normalizeCharacterIdentityKey("周明")),
        )

        assertTrue(merged.contains("晚晚"))
        assertTrue(merged.contains("用户补充称呼"))
        assertTrue(merged.contains("小晚"))
        assertFalse(merged.contains("周明"))
        assertFalse(merged.contains("林·晚"))
    }

    @Test
    fun `saved custom prompts are constrained to character discovery`() {
        val prompt = enforceCharacterDiscoveryBoundary(
            "Identify characters and recommend voiceGender and voiceAgeBand."
        )

        assertTrue(prompt.startsWith("Identify characters"))
        assertTrue(prompt.contains("Do not infer voice gender"))
        assertTrue(prompt.contains("Do not assign a TTS voice"))
        assertTrue(prompt.contains("Voice assignment is a separate workflow"))
    }

    @Test
    fun `character profile matches canonical name or a unique existing alias`() {
        val profile = BookCharacterProfile(
            id = "p1",
            bookUrl = "book",
            name = "李小花",
            aliasesJson = """["小花","花儿"]""",
        )

        assertTrue(findUniqueCharacterProfile("李小花", listOf(profile)) == profile)
        assertTrue(findUniqueCharacterProfile("小花", listOf(profile)) == profile)
    }

    @Test
    fun `ambiguous alias does not merge two character profiles`() {
        val first = BookCharacterProfile(
            id = "p1",
            bookUrl = "book",
            name = "李小花",
            aliasesJson = """["小花"]""",
        )
        val second = BookCharacterProfile(
            id = "p2",
            bookUrl = "book",
            name = "王小花",
            aliasesJson = """["小花"]""",
        )

        assertTrue(findUniqueCharacterProfile("小花", listOf(first, second)) == null)
    }

    @Test
    fun `proposed aliases are not used as identity evidence`() {
        val profile = BookCharacterProfile(
            id = "p1",
            bookUrl = "book",
            name = "李小花",
            aliasesJson = """["小花"]""",
        )
        val unrelatedCandidateName = "王明"

        assertTrue(findUniqueCharacterProfile(unrelatedCandidateName, listOf(profile)) == null)
    }

}
