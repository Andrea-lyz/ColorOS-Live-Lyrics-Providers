package io.github.proify.lyricon.kwprovider.xposed

import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import java.lang.reflect.Method

/**
 * Maps KuWo's own LRCX parser output into the shared rich-lyric model.
 *
 * The host parser owns the vendor-specific scaling rules, entity decoding and
 * word character ranges. Reflecting its result avoids re-implementing those
 * details and keeps lockscreen timing aligned with KuWo's built-in view.
 */
internal object KuWoOfficialLrcxAdapter {
    private const val TAG = "KuWoProvider"

    fun parse(appClassLoader: ClassLoader?, raw: String?): List<RichLyricLine>? {
        if (appClassLoader == null || raw.isNullOrBlank()) return null
        return runCatching {
            val parserClass = appClassLoader.loadClass("j6.f")
            val parser = parserClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            val parseMethod = parserClass.declaredMethods.firstOrNull { method ->
                method.name == "a" &&
                    method.parameterCount == 1 &&
                    method.parameterTypes[0] == String::class.java
            } ?: return null
            parseMethod.isAccessible = true
            val result = parseMethod.invoke(parser, decodeEntities(raw)) ?: return null
            val offset = invokeLong(result, "a")
            val rows = invokeObject(result, "q") as? List<*> ?: return null
            val mapped = rows.mapNotNull { row -> mapRow(row, offset) }
            KuWoLrcxParser.attachTranslations(mapped)
        }.onFailure { throwable ->
            YLog.error(tag = TAG, msg = "KuWo official LRCX adapter failed: $throwable")
        }.getOrNull()
    }

    private fun mapRow(row: Any?, offset: Long): RichLyricLine? {
        if (row == null) return null
        val begin = readIntField(row, "b") ?: return null
        val text = readObjectField(row, "d") as? String ?: return null
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return null
        val absoluteBegin = begin + offset
        val words = readObjectField(row, "j") as? List<*>
        val rawWords = words.orEmpty().mapNotNull { sourceWord ->
            mapWord(sourceWord, absoluteBegin, cleanText)
        }
        val hasUsableWordTiming = rawWords.any { it.end > it.begin }
        val mappedWords = if (!hasUsableWordTiming) {
            null
        } else {
            rawWords
        }
        return RichLyricLine(
            begin = absoluteBegin,
            end = absoluteBegin,
            duration = 0L,
            text = cleanText,
            words = mappedWords?.ifEmpty { null },
            translation = null
        )
    }

    private fun mapWord(sourceWord: Any?, lineBegin: Long, lineText: String): LyricWord? {
        if (sourceWord == null) return null
        var charStart = readIntField(sourceWord, "a") ?: return null
        var charEnd = readIntField(sourceWord, "b") ?: return null
        if (charEnd <= charStart) {
            charEnd = charStart + 1
        }
        val start = charStart.coerceIn(0, lineText.length)
        val end = charEnd.coerceIn(start, lineText.length)
        if (start >= end) return null
        val relativeBegin = readIntField(sourceWord, "d") ?: 0
        val relativeEnd = readIntField(sourceWord, "e") ?: relativeBegin
        val begin = lineBegin + relativeBegin
        val finish = (lineBegin + relativeEnd).coerceAtLeast(begin)
        return LyricWord(
            begin = begin,
            end = finish,
            duration = finish - begin,
            text = lineText.substring(start, end)
        )
    }

    private fun decodeEntities(value: String): String {
        return value
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
    }

    private fun invokeLong(target: Any, methodName: String): Long {
        val method = findNoArgMethod(target, methodName) ?: return 0L
        return when (val value = method.invoke(target)) {
            is Number -> value.toLong()
            else -> 0L
        }
    }

    private fun invokeObject(target: Any, methodName: String): Any? {
        val method = findNoArgMethod(target, methodName) ?: return null
        return runCatching { method.invoke(target) }.getOrNull()
    }

    private fun findNoArgMethod(target: Any, methodName: String): Method? {
        var current: Class<*>? = target.javaClass
        while (current != null) {
            runCatching {
                val method = current.getDeclaredMethod(methodName)
                method.isAccessible = true
                return method
            }
            current = current.superclass
        }
        return null
    }

    private fun readIntField(target: Any, fieldName: String): Int? {
        return when (val value = readObjectField(target, fieldName)) {
            is Number -> value.toInt()
            else -> null
        }
    }

    private fun readObjectField(target: Any, fieldName: String): Any? {
        var current: Class<*>? = target.javaClass
        while (current != null) {
            runCatching {
                val field = current.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(target)
            }
            current = current.superclass
        }
        return null
    }
}
