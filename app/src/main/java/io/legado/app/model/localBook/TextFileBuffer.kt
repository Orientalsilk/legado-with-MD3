package io.legado.app.model.localBook

import java.io.EOFException
import java.io.InputStream
import kotlin.math.min

/** TXT 正文滑窗，8MiB。左闭右开：[bufferStart, bufferEnd)。 */
internal const val TXT_BUFFER_SIZE = 8 * 1024 * 1024

/**
 * 缓冲区和章节字节区间没有交集时，整窗重读。
 * 边界是排他的：章节从 bufferEnd 开始，或刚好在 bufferStart 结束，都不算命中。
 */
internal fun txtBufferMiss(
    hasBuffer: Boolean,
    bufferStart: Long,
    bufferEnd: Long,
    start: Long,
    end: Long,
): Boolean {
    if (!hasBuffer) return true
    return start >= bufferEnd || end <= bufferStart
}

/** 章节跨过缓冲区某一侧时，不能按相对下标 copy，得按文件偏移重读。 */
internal fun txtBufferCrossesBoundary(
    bufferStart: Long,
    bufferEnd: Long,
    start: Long,
    end: Long,
): Boolean {
    return (start < bufferEnd && end > bufferEnd) || (start < bufferStart && end > bufferStart)
}

internal fun txtBufferWindowStart(offset: Long, windowSize: Long = TXT_BUFFER_SIZE.toLong()): Long {
    if (windowSize <= 0L || offset <= 0L) return 0L
    return windowSize * (offset / windowSize)
}

/**
 * [InputStream.skip] 允许少跳，content:// 和部分压缩流经常这样。
 * 少跳或不跳时改读单字节，保证位置真的到了 [byteCount]。
 */
internal fun InputStream.skipFully(byteCount: Long) {
    var remaining = byteCount
    val junk = ByteArray(8192)
    while (remaining > 0L) {
        val skipped = skip(remaining)
        if (skipped > 0L) {
            remaining -= skipped
            continue
        }
        val want = min(junk.size.toLong(), remaining).toInt()
        val n = if (want <= 0) -1 else read(junk, 0, want)
        if (n > 0) {
            remaining -= n
            continue
        }
        if (n < 0 || read() == -1) {
            throw EOFException("无法跳过 $byteCount 字节，还剩 $remaining")
        }
        remaining--
    }
}

/** 读满 [length]，或读到 EOF。返回实际字节数，调用方不能把没读到的尾巴当正文。 */
internal fun InputStream.readFully(dest: ByteArray, offset: Int = 0, length: Int = dest.size - offset): Int {
    var readTotal = 0
    while (readTotal < length) {
        val n = read(dest, offset + readTotal, length - readTotal)
        if (n < 0) break
        if (n == 0) {
            val one = read()
            if (one < 0) break
            dest[offset + readTotal] = one.toByte()
            readTotal++
            continue
        }
        readTotal += n
    }
    return readTotal
}

internal fun InputStream.readWindow(windowStart: Long, windowSize: Int = TXT_BUFFER_SIZE): ByteArray? {
    skipFully(windowStart)
    val raw = ByteArray(windowSize)
    val read = readFully(raw)
    if (read <= 0) return null
    return if (read == raw.size) raw else raw.copyOf(read)
}
