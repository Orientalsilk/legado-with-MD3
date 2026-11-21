package io.legado.app.help.readaloud.cast

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.domain.model.AiReasoningLevel
import io.legado.app.ui.config.readConfig.ReadConfig
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.context.GlobalContext

/**
 * 自动逐章 AI 分配的唯一入口：阅读页（翻到某章）和朗读服务（开读某章，哪怕在后台）共用，
 * 同一时刻只跑一章、已分析过的章按账本直接跳过，所以两边同时触发也不会重复请求。
 */
object AutoCastRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val queued = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 朗读服务开读 [index] 章时调用：后台排队分析本章和下一章，不阻塞朗读。 */
    fun requestFrom(book: Book, index: Int, chapters: (Int) -> BookChapter?) {
        // 反复点开始/重进同一章时不排重复请求（AI 失败的章没记账，否则每点一次就多打一次 AI）
        val key = "${book.bookUrl}|$index"
        if (!queued.add(key)) return
        scope.launch {
            try {
                chapters(index)?.let { analyze(book, it) }
                chapters(index + 1)?.let { analyze(book, it, quiet = true) }
            } finally {
                queued.remove(key)
            }
        }
    }

    suspend fun analyze(book: Book, chapter: BookChapter, quiet: Boolean = false) = mutex.withLock {
        val useCase = runCatching { GlobalContext.get().get<AiCastAssignUseCase>() }.getOrNull()
            ?: return@withLock
        val fingerprint = MD5Utils.md5Encode(
            "${chapter.url}|${chapter.title}|${chapter.tag.orEmpty()}",
        )
        try {
            if (AiCastPresetStore.isAutoChapterAnalyzed(book.bookUrl, chapter.index, fingerprint)) {
                return@withLock
            }
            // 只有池名还不够：默认池可能没有任何启用音色。没有真实可用音色时，
            // 不应为了最终无法落地的分配而调用 AI、消耗用户额度。
            if (VoicePoolStore.voicePoolMap().isEmpty()) {
                AppLog.put("自动人物分析跳过：没有可用的声音池音色（${chapter.title}）")
                return@withLock
            }

            var chapterFailed = false
            var aiFallbackUsed = false
            val result = useCase.execute(
                book = book,
                startChapter = chapter.index,
                chapterCount = 1,
                reassign = false,
                reasoningLevel = AiReasoningLevel.entries.firstOrNull {
                    it.storageValue == ReadConfig.speechAnalysisReasoningLevel
                } ?: AiReasoningLevel.OFF,
                persistProgress = false,
                onAiFallback = { aiFallbackUsed = true },
                onProgress = { _, _, _, _, error ->
                    if (error != null) chapterFailed = true
                },
            )
            if (result.isSuccess && !chapterFailed && !aiFallbackUsed) {
                AiCastPresetStore.markAutoChapterAnalyzed(book.bookUrl, chapter.index, fingerprint)
                AppLog.put("自动人物与音色分析完成：${chapter.title}")
            } else if (result.isSuccess && aiFallbackUsed) {
                // 离线兜底成功也不记成 AI 已分析；下次触发时自动再试 AI，避免一次断网永久锁死离线结果。
                AppLog.put("AI 暂不可用，已自动离线补充：${chapter.title}（保留未确定对白，下次自动重试 AI）")
            } else if (!quiet) {
                AppLog.put(
                    "自动人物与音色分析失败：${chapter.title}（" +
                        "${result.exceptionOrNull()?.localizedMessage ?: "章节处理未完成"}），改用离线分配",
                )
                // AI 没配好/断网/限额时不让这章整章念旁白：退到免费的离线分配（不记账，下次 AI 可再来）
                useCase.execute(
                    book = book,
                    startChapter = chapter.index,
                    chapterCount = 1,
                    reassign = false,
                    presetId = OFFLINE_PRESET_ID,
                    persistProgress = false,
                    onProgress = { _, _, _, _, _ -> },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.put("自动人物与音色分析异常：${chapter.title}", e, true)
        }
    }
}
