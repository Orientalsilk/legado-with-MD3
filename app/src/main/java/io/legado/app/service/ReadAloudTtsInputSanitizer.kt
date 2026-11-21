package io.legado.app.service

import io.legado.app.feature.reader.core.cast.CastMarkers

/**
 * 只净化送进 Android TTS 引擎的文本，不改书籍正文、分段结果或角色分析数据。
 *
 * MultiTTS 等本地引擎可能会把普通文本包装成 SSML。这里先保护 XML/SSML
 * 保留字符，并清除 XML 1.0 不允许的控制字符和常见不可见方向控制符。
 *
 * 同时把中文网络文本常见的六个连续英文句点规范为标准中文省略号。
 * 转换会附带一张新旧下标映射表，保证 onRangeStart 仍能定位到原文。
 */
internal object ReadAloudTtsInputSanitizer {

    internal data class SanitizedText(
        val text: String,
        /** 新文本每个边界位置对应原文本的 UTF-16 下标。 */
        val sourceOffsets: IntArray,
    )

    fun sanitize(spoken: CastMarkers.Spoken): CastMarkers.Spoken {
        val sanitized = sanitizeText(spoken.text)
        if (sanitized.text == spoken.text) return spoken

        return CastMarkers.Spoken(
            sanitized.text,
            spoken.storedOf(spoken.text.length),
        ) { index ->
            val safeIndex = index.coerceIn(0, sanitized.text.length)
            spoken.storedOf(sanitized.sourceOffsets[safeIndex])
        }
    }

    internal fun sanitizeText(input: String): SanitizedText {
        if (input.isEmpty()) return SanitizedText(input, intArrayOf(0))

        val output = StringBuilder(input.length)
        val sourceOffsets = ArrayList<Int>(input.length + 1).apply { add(0) }

        fun appendOriginal(from: Int, until: Int) {
            output.append(input, from, until)
            for (offset in from + 1..until) {
                sourceOffsets.add(offset)
            }
        }

        fun appendReplacement(character: Char, sourceEnd: Int) {
            output.append(character)
            sourceOffsets.add(sourceEnd)
        }

        fun skipUntil(sourceEnd: Int) {
            // 被移除字符归入前一个输出边界，避免进度下标落在不可见字符前。
            sourceOffsets[sourceOffsets.lastIndex] = sourceEnd
        }

        var index = 0
        while (index < input.length) {
            // 中文小说常见的六点省略号（......）会被一些本地 SSML 引擎拒绝。
            // 输出规范的中文省略号，并把第二个输出字符映射到整段原始省略号末尾。
            if (input[index] == '.') {
                var end = index + 1
                while (end < input.length && input[end] == '.') end++
                if (end - index >= 6) {
                    output.append("……")
                    sourceOffsets.add(index + 1)
                    sourceOffsets.add(end)
                    index = end
                    continue
                }
            }

            // 多于标准两个字符的连续 Unicode 省略号也收敛为一组。
            if (input[index] == '…') {
                var end = index + 1
                while (end < input.length && input[end] == '…') end++
                if (end - index > 2) {
                    output.append("……")
                    sourceOffsets.add(index + 1)
                    sourceOffsets.add(end)
                    index = end
                    continue
                }
            }

            val codePoint = Character.codePointAt(input, index)
            val charCount = Character.charCount(codePoint)
            val end = (index + charCount).coerceAtMost(input.length)

            when (codePoint) {
                // 防止文本直接插入引擎构造的 SSML 时破坏 XML 结构。
                '&'.code -> appendReplacement('＆', end)
                '<'.code -> appendReplacement('＜', end)
                '>'.code -> appendReplacement('＞', end)

                // 换行、回车、制表符作为普通分隔空格传入，避免成为 SSML 格式空白。
                '\n'.code, '\r'.code, '\t'.code,
                0x2028, 0x2029 -> appendReplacement(' ', end)

                else -> {
                    if (!isXml10Character(codePoint) || isInvisibleControl(codePoint)) {
                        skipUntil(end)
                    } else {
                        appendOriginal(index, end)
                    }
                }
            }
            index = end
        }

        return SanitizedText(output.toString(), sourceOffsets.toIntArray())
    }

    private fun isXml10Character(codePoint: Int): Boolean =
        codePoint == 0x9 ||
            codePoint == 0xA ||
            codePoint == 0xD ||
            codePoint in 0x20..0xD7FF ||
            codePoint in 0xE000..0xFFFD ||
            codePoint in 0x10000..0x10FFFF

    private fun isInvisibleControl(codePoint: Int): Boolean =
        codePoint == 0x00AD || // soft hyphen
            codePoint == 0x061C || // Arabic letter mark
            codePoint == 0x200B || // zero-width space
            codePoint == 0x200E || // left-to-right mark
            codePoint == 0x200F || // right-to-left mark
            codePoint in 0x202A..0x202E || // bidi embedding/override controls
            codePoint in 0x2060..0x206F || // word joiner and invisible format controls
            codePoint == 0xFEFF // BOM / zero-width no-break space
}
