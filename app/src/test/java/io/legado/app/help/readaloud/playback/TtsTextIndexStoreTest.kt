package io.legado.app.help.readaloud.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class TtsTextIndexStoreTest {

    @Test
    fun `quotes and newlines round trip`() {
        val file = newIndexFile()
        TtsTextIndexStore.upsert(file, "abc", "他说\"你好\"\n下一行")

        assertEquals("他说\"你好\"\n下一行", TtsTextIndexStore.read(file)["abc"])
    }

    @Test
    fun `legacy escaped json is readable`() {
        val file = newIndexFile()
        file.writeText("{\"abc\":\"say \\\"hi\\\"\\nnext\",\"def\":\"plain\"}")

        val index = TtsTextIndexStore.read(file)

        assertEquals("say \"hi\"\nnext", index["abc"])
        assertEquals("plain", index["def"])
    }

    @Test
    fun `parallel upserts do not drop entries`() = runBlocking {
        val file = newIndexFile()
        (0 until 40).map { index ->
            launch(Dispatchers.Default) {
                TtsTextIndexStore.upsert(file, "k$index", "正文\"$index\"\n行")
            }
        }.joinAll()

        val index = TtsTextIndexStore.read(file)
        assertEquals(40, index.size)
        assertEquals("正文\"7\"\n行", index["k7"])
    }

    @Test
    fun `index keeps the newest two thousand entries`() {
        val file = newIndexFile()
        repeat(2005) { TtsTextIndexStore.upsert(file, "k$it", "t$it") }

        val index = TtsTextIndexStore.read(file)

        assertEquals(2000, index.size)
        assertFalse(index.containsKey("k0"))
        assertFalse(index.containsKey("k4"))
        assertEquals("t5", index["k5"])
        assertEquals("t2004", index["k2004"])
    }

    @Test
    fun `delete forgets the cached copy`() {
        val file = newIndexFile()
        TtsTextIndexStore.upsert(file, "abc", "正文")
        TtsTextIndexStore.delete(file)
        file.parentFile?.mkdirs()
        file.writeText("{\"fresh\":\"new\"}")

        assertEquals("new", TtsTextIndexStore.read(file)["fresh"])
        assertFalse(TtsTextIndexStore.read(file).containsKey("abc"))
    }

    private fun newIndexFile(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "tts-index-${System.nanoTime()}")
        dir.mkdirs()
        return File(dir, "tts_cache_index.json")
    }
}
