package io.legado.app.help.readaloud.playback

import android.util.Base64
import io.legado.app.domain.model.readaloud.CloudTtsAudio
import io.legado.app.domain.model.readaloud.CloudTtsEngine
import io.legado.app.domain.model.readaloud.CloudTtsProviderType
import io.legado.app.domain.model.readaloud.CloudTtsSynthesisRequest
import io.legado.app.domain.model.readaloud.CloudTtsVoiceDescriptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

internal class MimoCloudTtsProvider : CloudTtsProvider {
    override val type = CloudTtsProviderType.Mimo

    override fun fetchVoices(engine: CloudTtsEngine): List<CloudTtsVoiceDescriptor> =
        when (engine.model) {
            VOICE_CLONE_MODEL -> listOf(CloudTtsVoiceDescriptor(
                id = CLONE_VOICE_ID,
                displayName = "MiMo 声音克隆",
                locale = "zh-CN",
                sampleRate = SAMPLE_RATE,
            ))
            VOICE_DESIGN_MODEL -> listOf(CloudTtsVoiceDescriptor(
                id = DESIGN_VOICE_ID,
                displayName = "MiMo 描述生成音色",
                locale = "zh-CN",
                sampleRate = SAMPLE_RATE,
            ))
            else -> BUILT_IN_VOICES
        }

    override fun synthesize(
        engine: CloudTtsEngine,
        request: CloudTtsSynthesisRequest,
    ): CloudTtsAudio {
        require(engine.apiKey.isNotBlank()) { "MiMo API Key 不能为空" }
        require(engine.model.isNotBlank()) { "MiMo 模型不能为空" }
        require(request.text.isNotBlank()) { "朗读文本不能为空" }

        val isVoiceClone = engine.model == VOICE_CLONE_MODEL
        val isVoiceDesign = engine.model == VOICE_DESIGN_MODEL
        val body = buildRequestBody(engine, request)
        val baseUrl = engine.baseUrl.ifBlank { DEFAULT_BASE_URL }.trimEnd('/')
        val httpRequest = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("api-key", engine.apiKey)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return cloudTtsHttpClient.newCall(httpRequest).execute().use { response ->
            response.requireCloudTtsSuccessful("MiMo TTS")
            if (isVoiceClone || isVoiceDesign) {
                val payload = response.body.string()
                val encoded = JSONObject(payload).optJSONArray("choices")
                    ?.optJSONObject(0)?.optJSONObject("message")
                    ?.optJSONObject("audio")?.optString("data")
                val modelLabel = if (isVoiceClone) "声音克隆" else "音色设计"
                require(!encoded.isNullOrBlank()) { "MiMo $modelLabel 没有返回音频数据" }
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                require(bytes.isNotEmpty()) { "MiMo $modelLabel 返回了空音频" }
                CloudTtsAudio(bytes, CLONE_OUTPUT_FORMAT)
            } else {
                val pcm = ByteArrayOutputStream()
                response.body.source().use { source ->
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line()?.trim().orEmpty()
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") break
                        val encoded = runCatching {
                            JSONObject(payload).optJSONArray("choices")?.optJSONObject(0)
                                ?.optJSONObject("delta")?.optJSONObject("audio")?.optString("data")
                        }.getOrNull()
                        if (!encoded.isNullOrBlank()) pcm.write(Base64.decode(encoded, Base64.DEFAULT))
                    }
                }
                val bytes = pcm.toByteArray()
                require(bytes.isNotEmpty()) { "MiMo TTS 没有返回音频数据" }
                CloudTtsAudio(pcm16MonoToWav(bytes, SAMPLE_RATE), "wav", SAMPLE_RATE)
            }
        }
    }

    internal fun buildRequestBody(
        engine: CloudTtsEngine,
        request: CloudTtsSynthesisRequest,
    ): JSONObject {
        val isVoiceClone = engine.model == VOICE_CLONE_MODEL
        val isVoiceDesign = engine.model == VOICE_DESIGN_MODEL
        if (isVoiceDesign) {
            require(request.instructions.isNotBlank()) {
                "MiMo 音色设计模型需要填写音色描述（音色预设的「指令」）"
            }
        }
        val audio = JSONObject().put(
            "format",
            if (isVoiceClone || isVoiceDesign) CLONE_OUTPUT_FORMAT else "pcm16",
        )
        when {
            isVoiceClone -> audio.put("voice", parseReferenceAudio(engine.optionsJson))
            isVoiceDesign -> Unit
            else -> audio.put("voice", request.voiceId)
        }
        return JSONObject().apply {
            put("model", engine.model)
            put("messages", buildMessages(request))
            put("audio", audio)
            // Voice clone/design currently return a completed audio payload, not low-latency chunks.
            put("stream", !(isVoiceClone || isVoiceDesign))
        }
    }

    private fun buildMessages(request: CloudTtsSynthesisRequest): JSONArray {
        val expression = request.style.takeIf(String::isNotBlank)?.let { "($it)" }.orEmpty()
        return JSONArray().apply {
            request.instructions.takeIf(String::isNotBlank)?.let { instruction ->
                put(JSONObject().put("role", "user").put("content", instruction))
            }
            put(JSONObject()
                .put("role", "assistant")
                .put("content", "$expression${request.text}"))
        }
    }

    private fun parseReferenceAudio(optionsJson: String): String {
        val referenceAudio = runCatching {
            JSONObject(optionsJson).optString(REFERENCE_AUDIO_OPTION)
        }.getOrDefault("")
        require(referenceAudio.startsWith("data:audio/mpeg;base64,") ||
            referenceAudio.startsWith("data:audio/wav;base64,")) {
            "MiMo 声音克隆需要在引擎「参考音频配置」中填写 MP3/WAV Base64 Data URL"
        }
        require(referenceAudio.length <= MAX_REFERENCE_AUDIO_DATA_URL_LENGTH) {
            "MiMo 参考音频 Base64 Data URL 超过 10 MB 限制"
        }
        require(referenceAudio.substringAfter(',').isNotBlank()) {
            "MiMo 参考音频内容不能为空"
        }
        return referenceAudio
    }

    private companion object {
        const val DEFAULT_BASE_URL = "https://api.xiaomimimo.com/v1"
        const val SAMPLE_RATE = 24_000
        const val VOICE_CLONE_MODEL = "mimo-v2.5-tts-voiceclone"
        const val VOICE_DESIGN_MODEL = "mimo-v2.5-tts-voicedesign"
        const val CLONE_VOICE_ID = "mimo_voice_clone"
        const val DESIGN_VOICE_ID = "mimo_voice_design"
        const val CLONE_OUTPUT_FORMAT = "wav"
        const val REFERENCE_AUDIO_OPTION = "referenceAudio"
        const val MAX_REFERENCE_AUDIO_DATA_URL_LENGTH = 10 * 1024 * 1024
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val STYLES = listOf("开心", "悲伤", "愤怒", "惊讶", "恐惧", "厌恶", "小声", "平静")
        val BUILT_IN_VOICES = listOf(
            CloudTtsVoiceDescriptor("mimo_default", "MiMo 默认", styles = STYLES, sampleRate = SAMPLE_RATE),
            CloudTtsVoiceDescriptor("冰糖", "冰糖", "zh-CN", "Female", STYLES, sampleRate = SAMPLE_RATE),
            CloudTtsVoiceDescriptor("茉莉", "茉莉", "zh-CN", "Female", STYLES, sampleRate = SAMPLE_RATE),
            CloudTtsVoiceDescriptor("苏打", "苏打", "zh-CN", "Male", STYLES, sampleRate = SAMPLE_RATE),
            CloudTtsVoiceDescriptor("白桦", "白桦", "zh-CN", "Male", STYLES, sampleRate = SAMPLE_RATE),
            CloudTtsVoiceDescriptor("Mia", "Mia", "en", "Female", STYLES, sampleRate = SAMPLE_RATE),
            CloudTtsVoiceDescriptor("Chloe", "Chloe", "en", "Female", STYLES, sampleRate = SAMPLE_RATE),
            CloudTtsVoiceDescriptor("Milo", "Milo", "en", "Male", STYLES, sampleRate = SAMPLE_RATE),
            CloudTtsVoiceDescriptor("Dean", "Dean", "en", "Male", STYLES, sampleRate = SAMPLE_RATE),
        )
    }
}
