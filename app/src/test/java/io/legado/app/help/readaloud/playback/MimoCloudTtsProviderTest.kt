package io.legado.app.help.readaloud.playback

import android.app.Application

import io.legado.app.domain.model.readaloud.CloudTtsEngine
import io.legado.app.domain.model.readaloud.CloudTtsProviderType
import io.legado.app.domain.model.readaloud.CloudTtsSynthesisRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MimoCloudTtsProviderTest {
    @Test
    fun `exposes the official built in voice directory`() {
        val voices = MimoCloudTtsProvider().fetchVoices(
            CloudTtsEngine(
                id = "mimo",
                name = "MiMo",
                provider = CloudTtsProviderType.Mimo,
                model = "mimo-v2.5-tts",
            ),
        )

        assertEquals(
            listOf("mimo_default", "冰糖", "茉莉", "苏打", "白桦", "Mia", "Chloe", "Milo", "Dean"),
            voices.map { it.id },
        )
        assertTrue(voices.all { it.styles.isNotEmpty() })
    }

    @Test
    fun `exposes a dedicated selectable voice for the voice clone model`() {
        val voices = MimoCloudTtsProvider().fetchVoices(
            CloudTtsEngine(
                id = "mimo-clone",
                name = "MiMo Clone",
                provider = CloudTtsProviderType.Mimo,
                model = "mimo-v2.5-tts-voiceclone",
            ),
        )

        assertEquals(1, voices.size)
        assertEquals("mimo_voice_clone", voices.single().id)
        assertEquals("MiMo 声音克隆", voices.single().displayName)
    }

    @Test
    fun `exposes a dedicated selectable voice for the voice design model`() {
        val voices = MimoCloudTtsProvider().fetchVoices(
            CloudTtsEngine(
                id = "mimo-design",
                name = "MiMo Design",
                provider = CloudTtsProviderType.Mimo,
                model = "mimo-v2.5-tts-voicedesign",
            ),
        )

        assertEquals(1, voices.size)
        assertEquals("mimo_voice_design", voices.single().id)
        assertEquals("MiMo 描述生成音色", voices.single().displayName)
    }

    @Test
    fun `voice clone sends reference data URL and uses non streaming response`() {
        val provider = MimoCloudTtsProvider()
        val referenceAudio = "data:audio/mpeg;base64,QUJD"
        val engine = CloudTtsEngine(
            id = "mimo-clone",
            name = "MiMo Clone",
            provider = CloudTtsProviderType.Mimo,
            model = "mimo-v2.5-tts-voiceclone",
            optionsJson = """{"referenceAudio":"$referenceAudio"}""",
        )
        val body = provider.buildRequestBody(
            engine,
            CloudTtsSynthesisRequest(text = "你好", voiceId = "mimo_voice_clone"),
        )

        assertEquals(referenceAudio, body.getJSONObject("audio").getString("voice"))
        assertEquals("wav", body.getJSONObject("audio").getString("format"))
        assertFalse(body.getBoolean("stream"))
        assertEquals("assistant", body.getJSONArray("messages").getJSONObject(0).getString("role"))
    }

    @Test
    fun `voice design sends description as user context and omits voice field`() {
        val provider = MimoCloudTtsProvider()
        val body = provider.buildRequestBody(
            CloudTtsEngine(
                id = "mimo-design",
                name = "MiMo Design",
                provider = CloudTtsProviderType.Mimo,
                model = "mimo-v2.5-tts-voicedesign",
            ),
            CloudTtsSynthesisRequest(
                text = "你好",
                voiceId = "mimo_voice_design",
                instructions = "清晰自然的成年男声",
            ),
        )
        val messages = body.getJSONArray("messages")

        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("清晰自然的成年男声", messages.getJSONObject(0).getString("content"))
        assertEquals("assistant", messages.getJSONObject(1).getString("role"))
        assertEquals("你好", messages.getJSONObject(1).getString("content"))
        assertFalse(body.getJSONObject("audio").has("voice"))
        assertFalse(body.getBoolean("stream"))
    }

    @Test
    fun `built in MiMo keeps pcm16 streaming request`() {
        val body = MimoCloudTtsProvider().buildRequestBody(
            CloudTtsEngine(
                id = "mimo",
                name = "MiMo",
                provider = CloudTtsProviderType.Mimo,
                model = "mimo-v2.5-tts",
            ),
            CloudTtsSynthesisRequest(text = "你好", voiceId = "苏打"),
        )

        assertEquals("pcm16", body.getJSONObject("audio").getString("format"))
        assertEquals("苏打", body.getJSONObject("audio").getString("voice"))
        assertTrue(body.getBoolean("stream"))
    }

}
