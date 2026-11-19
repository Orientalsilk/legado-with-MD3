package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.HighlightTagRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class BookExtensionsTest {

    @Test
    fun displayTags_keepsCustomAndSourceTagsSeparateAndDeduplicated() {
        val book = Book(
            kind = "奇幻,连载",
            customTag = "收藏,奇幻",
        )

        assertEquals(listOf("奇幻", "连载"), book.getSourceTagList())
        assertEquals(listOf("收藏", "奇幻"), book.getCustomTagList())
        assertEquals(listOf("收藏", "奇幻", "连载"), book.getDisplayTagList())
        assertEquals("奇幻,连载", book.kind)
        assertEquals("收藏,奇幻", book.customTag)
    }

    @Test
    fun parseHighlightedTags_ordersMatchesByRuleOrder() {
        val rules = listOf(
            HighlightTagRule(
                id = 2,
                title = "次要",
                pattern = "动作",
                order = 1,
            ),
            HighlightTagRule(
                id = 1,
                title = "优先",
                pattern = "奇幻",
                order = 0,
            ),
        )

        val (highlighted, regular) = parseHighlightedTags(
            kindLabels = listOf("动作", "普通", "奇幻"),
            rules = rules,
        )

        assertEquals(listOf("优先", "次要"), highlighted.map { it.title })
        assertEquals(listOf(listOf("奇幻"), listOf("动作")), highlighted.map { it.matchedLabels })
        assertEquals(listOf("普通"), regular)
    }

    @Test
    fun getStartDate_keepsSavedDateEvenWhenSimulatingIsOff() {
        val book = Book()
        book.setReadSimulating(false)
        book.setStartDate(LocalDate.of(2030, 1, 2))

        assertEquals(LocalDate.of(2030, 1, 2), book.getStartDate())
    }

    @Test
    fun getStartDate_invalidLegacyValueDoesNotThrowOrBecomeToday() {
        val book = Book()
        book.setReadSimulating(true)
        book.config.startDate = "0001-00-00"

        assertNull(book.getStartDate())
    }

    @Test
    fun datePickerRoundTripDoesNotShiftADayAheadOfUtc() {
        val date = LocalDate.of(2026, 11, 19)
        assertEquals(date, pickerMillisToLocalDate(localDateToPickerMillis(date)))

        val shownAsUtc = Instant.ofEpochMilli(
            date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        ).atZone(ZoneOffset.UTC).toLocalDate()
        assertEquals(LocalDate.of(2026, 11, 18), shownAsUtc)
    }
}
