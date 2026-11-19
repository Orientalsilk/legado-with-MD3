package io.legado.app.help.readaloud.playback

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import io.legado.app.constant.AppLog
import io.legado.app.domain.model.readaloud.TtsEngineDescriptor
import io.legado.app.domain.model.readaloud.TtsEngineKind
import io.legado.app.domain.model.readaloud.TtsNativeVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class SystemTtsVoiceCatalog(context: Context) {

    private val appContext = context.applicationContext

    suspend fun getEngines(): List<TtsEngineDescriptor> = withTts("") { tts ->
        tts.engines
            .distinctBy { it.name }
            .map { engine ->
                TtsEngineDescriptor(
                    id = engineId(engine.name),
                    kind = TtsEngineKind.System,
                    sourceId = engine.name,
                    displayName = engine.label.ifBlank { engine.name },
                    providerName = engine.name,
                    supportsVoiceDiscovery = true,
                )
            }
            .sortedBy { it.displayName.lowercase() }
    } ?: emptyList()

    suspend fun getVoices(enginePackage: String): List<TtsNativeVoice> =
        withTts(enginePackage) { tts ->
            tts.voices
                .orEmpty()
                .map { voice ->
                    TtsNativeVoice(
                        id = voice.name,
                        engineId = engineId(enginePackage),
                        displayName = voice.name,
                        locale = voice.locale.toLanguageTag(),
                        quality = voice.quality,
                        latency = voice.latency,
                        requiresNetwork = voice.isNetworkConnectionRequired,
                    )
                }
                .sortedWith(compareBy<TtsNativeVoice> { it.locale }.thenBy { it.displayName })
                // 有的引擎（小米系统语音引擎）让多个 locale 复用同一个 Voice.name，
                // 而我们只用 name 去 setVoice：重复项既没意义又会让列表 key 撞车
                .distinctBy { it.id }
        } ?: emptyList()

    private suspend fun <T> withTts(
        enginePackage: String,
        block: suspend (TextToSpeech) -> T,
    ): T? {
        // 实例必须在有 Looper 的线程上建，读音色却会同步等引擎回包，所以读放在 IO 上
        val tts = withTimeoutOrNull(ENGINE_INIT_TIMEOUT_MS) {
            withContext(Dispatchers.Main.immediate) { createTts(enginePackage) }
        } ?: run {
            AppLog.putDebug("系统 TTS 引擎初始化超时: $enginePackage")
            return null
        }
        // 读音色是阻塞的 binder 调用，withContext 里的超时要等它自己返回才生效，等于没超时；
        // 放进独立协程里 await，超时后调用方立刻继续（finally 里 shutdown 会让卡住的调用退出）。
        val work = CoroutineScope(Dispatchers.IO + SupervisorJob()).async { block(tts) }
        return try {
            val result = withTimeoutOrNull(VOICE_CATALOG_TIMEOUT_MS) { work.await() }
            if (result == null) AppLog.putDebug("系统 TTS 音色目录读取超时")
            result
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            AppLog.putDebug("系统 TTS 音色目录读取失败: ${error.localizedMessage}")
            null
        } finally {
            work.cancel()
            withContext(Dispatchers.Main.immediate) {
                runCatching { tts.stop() }
                runCatching { tts.shutdown() }
            }
        }
    }

    private suspend fun createTts(enginePackage: String): TextToSpeech? =
        suspendCancellableCoroutine { continuation ->
            var instance: TextToSpeech? = null
            val listener = TextToSpeech.OnInitListener { status ->
                val initialized = instance
                if (!continuation.isActive) {
                    initialized?.shutdown()
                } else if (status == TextToSpeech.SUCCESS) {
                    continuation.resume(initialized)
                } else {
                    AppLog.putDebug("系统 TTS 初始化失败: $enginePackage status=$status")
                    initialized?.shutdown()
                    continuation.resume(null)
                }
            }
            instance = if (enginePackage.isBlank()) {
                TextToSpeech(appContext, listener)
            } else {
                TextToSpeech(appContext, listener, enginePackage)
            }
            continuation.invokeOnCancellation { instance.shutdown() }
        }

    companion object {
        private const val ENGINE_INIT_TIMEOUT_MS = 10_000L
        private const val VOICE_CATALOG_TIMEOUT_MS = 12_000L

        fun engineId(sourceId: String): String = "system:$sourceId"
    }
}
