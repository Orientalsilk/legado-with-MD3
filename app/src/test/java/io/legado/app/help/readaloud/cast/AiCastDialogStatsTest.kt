package io.legado.app.help.readaloud.cast

import io.legado.app.data.entities.ChapterRoleAssignment
import org.junit.Assert.assertEquals
import org.junit.Test

class AiCastDialogStatsTest {

    @Test
    fun countsOnlyAssignmentsWithinCurrentDialogueRange() {
        val rows = listOf(
            assignment(0, "a", "角色甲"),
            assignment(1, "b", "角色乙"),
            assignment(3, "c", "角色丙"),
            assignment(36, "old", "旧正文角色"),
        )

        assertEquals(2, countValidChapterAssignments(rows, dialogueCount = 2))
    }

    @Test
    fun ignoresRowsWithoutARealCharacter() {
        val rows = listOf(
            assignment(0, "", "角色甲"),
            assignment(1, "b", ""),
            assignment(2, "c", "角色丙"),
        )

        assertEquals(1, countValidChapterAssignments(rows, dialogueCount = 3))
    }

    @Test
    fun zeroDialogueAnchorsNeverCountsAssignments() {
        assertEquals(
            0,
            countValidChapterAssignments(
                listOf(assignment(0, "a", "角色甲")),
                dialogueCount = 0,
            ),
        )
    }

    private fun assignment(
        ordinal: Int,
        characterId: String,
        characterName: String,
    ) = ChapterRoleAssignment(
        bookUrl = "stats-test",
        chapterIndex = 0,
        quoteOrdinal = ordinal,
        characterId = characterId,
        characterName = characterName,
    )
}
