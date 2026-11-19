package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileUtilsAtomicWriteTest {

    @Test
    fun writeAtomic_replacesTargetAndCleansTmp() {
        val dir = createTempDir("fileutils-atomic")
        try {
            val target = File(dir, "data.txt")
            target.writeText("old")
            FileUtils.writeAtomic(target, "new content")
            assertEquals("new content", target.readText())
            val leftovers = dir.listFiles()?.filter { it.name.contains(".tmp") || it.name.contains(".park") } ?: emptyList()
            assertTrue("tmp/park should be cleaned", leftovers.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun replaceAtomic_copiesThenRenames() {
        val dir = createTempDir("fileutils-replace")
        try {
            val src = File(dir, "src.bin")
            val dest = File(dir, "dest.bin")
            src.writeBytes(byteArrayOf(1, 2, 3, 4))
            dest.writeBytes(byteArrayOf(9))
            FileUtils.replaceAtomic(src, dest)
            assertEquals(listOf(1.toByte(), 2, 3, 4), dest.readBytes().toList())
            val leftovers = dir.listFiles()?.filter { it.name.contains(".tmp") || it.name.contains(".park") } ?: emptyList()
            assertTrue(leftovers.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
