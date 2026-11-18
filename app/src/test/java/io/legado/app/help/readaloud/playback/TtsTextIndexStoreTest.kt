package io.legado.app.help.readaloud.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `identical upsert refreshes recency before oldest entries are evicted`() {
        val file = newIndexFile()
        repeat(2005) { TtsTextIndexStore.upsert(file, "k$it", "t$it") }

        // k5 is the oldest surviving item. An identical write must move it to the newest position.
        TtsTextIndexStore.upsert(file, "k5", "t5")
        TtsTextIndexStore.upsert(file, "k2005", "t2005")

        val index = TtsTextIndexStore.read(file)

        assertEquals(2000, index.size)
        assertFalse(index.containsKey("k6"))
        assertEquals("t5", index["k5"])
        assertEquals("t2005", index["k2005"])
    }

    @Test
    fun `failed disk commit keeps previous in-memory snapshot`() {
        val file = newIndexFile()
        TtsTextIndexStore.upsert(file, "kept", "old")
        val backup = File(file.parentFile, "tts_cache_index.backup")
        val committedContent = file.readText()
        assertTrue(file.renameTo(backup))

        // A non-empty directory at the destination forces the atomic replacement to fail.
        assertTrue(file.mkdir())
        File(file, "child").writeText("block replacement")
        val result = runCatching { TtsTextIndexStore.upsert(file, "not-committed", "new") }

        assertTrue(result.isFailure)
        assertEquals(committedContent, backup.readText())
        assertEquals(mapOf("kept" to "old"), TtsTextIndexStore.read(file))

        file.listFiles()?.forEach { it.delete() }
        assertTrue(file.delete())
        assertTrue(backup.renameTo(file))
    }

    @Test
    fun `external cache deletion invalidates the in memory index`() {
        val file = newIndexFile()
        TtsTextIndexStore.upsert(file, "stale", "old text")

        assertTrue(file.delete())
        assertTrue(TtsTextIndexStore.read(file).isEmpty())

        file.writeText("{\"fresh\":\"new text\"}")
        assertEquals(mapOf("fresh" to "new text"), TtsTextIndexStore.read(file))
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
