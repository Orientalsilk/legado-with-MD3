package io.legado.app.help.readaloud.cast

import io.legado.app.data.appDb
import io.legado.app.data.entities.ChapterRoleAssignment
import io.legado.app.feature.reader.core.cast.CastMarkers
import io.legado.app.feature.reader.core.source.ReaderChapterSourceParser
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI 分配悬浮窗的本章统计：(已分配句数, 一级对话锚点总数)。
 *
 * 锚点计数与注入/渲染侧完全同源（ContentProcessor 后 textList → [ReaderChapterSourceParser.castAnchorText]
 * → CastQuoteTracker），保证「是否全部已分配」的判断与屏幕上看到的胶囊一一对应。
 */
object AiCastDialogStats {

    suspend fun load(bookUrl: String, chapterIndex: Int): Pair<Int, Int> =
        withContext(Dispatchers.IO) {
            val book = appDb.bookDao.getBook(bookUrl) ?: return@withContext 0 to 0
            val chapter = appDb.bookChapterDao.getChapter(bookUrl, chapterIndex)
                ?: return@withContext 0 to 0
            val content = BookHelp.getContent(book, chapter) ?: return@withContext 0 to 0
            val paragraphs = ReaderChapterSourceParser.castAnchorText(
                paragraphs = ContentProcessor.get(book)
                    .getContent(book, chapter, content, includeTitle = false)
                    .textList,
                adaptSpecialStyle = AppConfig.adaptSpecialStyle,
            )
            val tracker = CastMarkers.CastQuoteTracker()
            var anchors = 0
            for (paragraph in paragraphs) {
                for (ch in paragraph) {
                    if (tracker.feed(ch)) anchors++
                }
            }
            val assigned = countValidChapterAssignments(
                appDb.chapterRoleAssignmentDao.getForChapter(bookUrl, chapterIndex),
                anchors,
            )
            assigned to anchors
        }
}


/**
 * 只统计当前正文中仍存在的对话锚点。
 *
 * 章节正文被更新或重新解析后，旧分配行可能保留更大的 quoteOrdinal；
 * 这些行不对应当前正文，不能虚增已分配数或让 UI 误报「全部完成」。
 * 此处只修正统计，不删除数据库记录，也不影响后续恢复旧正文时的数据。
 */
internal fun countValidChapterAssignments(
    assignments: List<ChapterRoleAssignment>,
    dialogueCount: Int,
): Int = assignments.count { row ->
    row.quoteOrdinal in 0 until dialogueCount &&
        row.characterId.isNotBlank() &&
        row.characterName.isNotBlank()
}
