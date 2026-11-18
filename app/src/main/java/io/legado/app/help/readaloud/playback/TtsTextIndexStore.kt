package io.legado.app.help.readaloud.playback

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * 朗读缓存的「文件名 → 正文摘录」索引。
 *
 * 预合成会并行写同一份 json。原先每次整文件读改写，再用正则拆字符串：
 * 并发写入会互相覆盖，写出半截文件；正文里的引号和换行还会把后面的条目一起截掉。
 * 读改写收进同一把锁，转义交给 Gson。内存里留一份，避免每一句都重新解析整份索引。
 */
internal object TtsTextIndexStore {
    private const val MAX_ENTRIES = 2000
    private val monitor = Any()
    private val mapType = object : TypeToken<Map<String, String>>() {}.type
    private val json = GsonBuilder().disableHtmlEscaping().create()

    private var cachedPath: String? = null
    private var cachedLength: Long = -1L
    private var cachedLastModified: Long = -1L
    private var cached: LinkedHashMap<String, String>? = null

    fun read(file: File): Map<String, String> = synchronized(monitor) {
        LinkedHashMap(load(file))
    }

    fun upsert(file: File, fileName: String, text: String) {
        if (fileName.isEmpty()) return
        synchronized(monitor) {
            // Copy-on-write: a failed disk commit must not leak uncommitted changes
            // into the in-memory snapshot returned by later reads.
            val index = LinkedHashMap(load(file))
            val shortText = if (text.length > 200) text.take(200) + "…" else text
            val wasMostRecent = index.keys.lastOrNull() == fileName
            val previous = index.remove(fileName)
            if (previous == shortText && wasMostRecent) return@synchronized

            // Reinsert even when the text is unchanged so repeated synthesis refreshes recency.
            index[fileName] = shortText
            while (index.size > MAX_ENTRIES) {
                val oldest = index.keys.firstOrNull() ?: break
                index.remove(oldest)
            }
            write(file, index)
        }
    }

    fun remove(file: File, fileName: String) {
        synchronized(monitor) {
            val index = LinkedHashMap(load(file))
            if (index.containsKey(fileName)) {
                index.remove(fileName)
                write(file, index)
            }
        }
    }

    /** 清缓存时把索引文件和内存副本一起丢掉，避免下一次写入把已删条目写回来。 */
    fun delete(file: File) {
        synchronized(monitor) {
            if (cachedPath == file.absolutePath) {
                cached = null
                cachedPath = null
                cachedLength = -1L
                cachedLastModified = -1L
            }
            file.delete()
        }
    }

    private fun load(file: File): LinkedHashMap<String, String> {
        val path = file.absolutePath
        val exists = file.isFile
        val length = if (exists) file.length() else -1L
        val lastModified = if (exists) file.lastModified() else -1L
        cached?.let {
            if (cachedPath == path && cachedLength == length &&
                cachedLastModified == lastModified
            ) return it
        }
        val loaded = if (!file.isFile || file.length() <= 0L) {
            LinkedHashMap()
        } else {
            runCatching {
                val parsed: Map<String, String>? = json.fromJson(file.readText(), mapType)
                LinkedHashMap<String, String>().apply { parsed?.let { putAll(it) } }
            }.getOrDefault(LinkedHashMap())
        }
        cached = loaded
        cachedPath = path
        cachedLength = length
        cachedLastModified = lastModified
        return loaded
    }

    private fun write(file: File, index: LinkedHashMap<String, String>) {
        val parent = file.parentFile
        parent?.mkdirs()
        val tmp = File.createTempFile("${file.name}-", ".tmp", parent)
        try {
            tmp.writeText(json.toJson(index, mapType))
            try {
                Files.move(tmp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                // 不支持原子移动的文件系统仍通过同目录 rename 替换，绝不直接截断旧索引。
                Files.move(tmp.toPath(), file.toPath(), REPLACE_EXISTING)
            }
            cached = index
            cachedPath = file.absolutePath
            cachedLength = file.length()
            cachedLastModified = file.lastModified()
        } finally {
            tmp.delete()
        }
    }
}
