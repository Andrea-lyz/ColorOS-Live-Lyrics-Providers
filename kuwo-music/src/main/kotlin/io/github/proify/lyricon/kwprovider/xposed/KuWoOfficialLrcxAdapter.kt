package io.github.proify.lyricon.kwprovider.xposed

import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayDeque

/**
 * Maps KuWo's own LRCX parser output into the shared rich-lyric model.
 *
 * The host parser owns the vendor-specific scaling rules, entity decoding and
 * word character ranges. Reflecting its result avoids re-implementing those
 * details and keeps lockscreen timing aligned with KuWo's built-in view.
 */
internal object KuWoOfficialLrcxAdapter {
    private const val TAG = "KuWoProvider"

    @Volatile
    private var resolvedParserMethod: Method? = null

    fun setResolvedParserMethod(method: Method?) {
        resolvedParserMethod = method?.apply { isAccessible = true }
    }

    fun parse(appClassLoader: ClassLoader?, raw: String?): List<RichLyricLine>? {
        if (appClassLoader == null || raw.isNullOrBlank()) return null
        return runCatching {
            val parserClass = resolvedParserMethod?.declaringClass
                ?: appClassLoader.loadClass("j6.f")
            val parser = parserClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            val parseMethod = resolvedParserMethod ?: parserClass.declaredMethods.firstOrNull { method ->
                method.name == "a" &&
                    method.parameterCount == 1 &&
                    method.parameterTypes[0] == String::class.java
            } ?: return null
            parseMethod.isAccessible = true
            val result = parseMethod.invoke(parser, decodeEntities(raw)) ?: return null
            val offset = readOffset(result)
            val rows = readLyricRows(result) ?: return null
            val mapped = rows.mapNotNull { row -> mapRow(row, offset) }
            KuWoLrcxParser.attachTranslations(mapped)
        }.onFailure { throwable ->
            YLog.error(tag = TAG, msg = "KuWo official LRCX adapter failed: $throwable")
        }.getOrNull()
    }

    private fun mapRow(row: Any?, offset: Long): RichLyricLine? {
        if (row == null) return null
        val begin = readIntField(row, "b", 0) ?: return null
        val text = (readObjectField(row, "d") as? String)
            ?: readFieldValueByType(row, String::class.java, 0) as? String
            ?: return null
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return null
        val absoluteBegin = begin + offset
        val words = (readObjectField(row, "j") as? List<*>)
            ?: readFieldValueByType(row, List::class.java, 0) as? List<*>
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
        var charStart = readIntField(sourceWord, "a", 0) ?: return null
        var charEnd = readIntField(sourceWord, "b", 1) ?: return null
        if (charEnd <= charStart) {
            charEnd = charStart + 1
        }
        val start = charStart.coerceIn(0, lineText.length)
        val end = charEnd.coerceIn(start, lineText.length)
        if (start >= end) return null
        val relativeBegin = readIntField(sourceWord, "d", 3) ?: 0
        val relativeEnd = readIntField(sourceWord, "e", 4) ?: relativeBegin
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

    private fun readOffset(target: Any): Long {
        val method = findNoArgMethod(target, "a")
            ?: target.javaClass.methods.singleOrNull { candidate ->
                candidate.parameterCount == 0 &&
                    (candidate.returnType == Long::class.javaPrimitiveType ||
                        candidate.returnType == Long::class.javaObjectType)
            }
            ?: return 0L
        return when (val value = method.invoke(target)) {
            is Number -> value.toLong()
            else -> 0L
        }
    }

    private fun readLyricRows(target: Any): List<*>? {
        val known = findNoArgMethod(target, "q")
            ?.let { method -> runCatching { method.invoke(target) as? List<*> }.getOrNull() }
        if (looksLikeLyricRows(known)) return known
        return target.javaClass.methods.asSequence()
            .filter { method ->
                method.parameterCount == 0 && List::class.java.isAssignableFrom(method.returnType)
            }
            .mapNotNull { method ->
                runCatching { method.invoke(target) as? List<*> }.getOrNull()
            }
            .firstOrNull(::looksLikeLyricRows)
    }

    private fun looksLikeLyricRows(rows: List<*>?): Boolean {
        val sample = rows?.firstOrNull { it != null } ?: return false
        val fields = instanceFields(sample.javaClass)
        return fields.any { it.type == String::class.java } &&
            fields.count { Number::class.java.isAssignableFrom(boxedType(it.type)) } >= 2
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

    private fun readIntField(target: Any, fieldName: String, fallbackOrdinal: Int): Int? {
        val value = readObjectField(target, fieldName)
            ?: readNumberFieldByOrdinal(target, fallbackOrdinal)
        return when (value) {
            is Number -> value.toInt()
            else -> null
        }
    }

    private fun readNumberFieldByOrdinal(target: Any, ordinal: Int): Any? {
        return instanceFields(target.javaClass)
            .filter { field -> Number::class.java.isAssignableFrom(boxedType(field.type)) }
            .getOrNull(ordinal)
            ?.let { field -> runCatching { field.get(target) }.getOrNull() }
    }

    private fun readFieldValueByType(target: Any, type: Class<*>, ordinal: Int): Any? {
        return instanceFields(target.javaClass)
            .filter { field -> type.isAssignableFrom(field.type) }
            .getOrNull(ordinal)
            ?.let { field -> runCatching { field.get(target) }.getOrNull() }
    }

    private fun instanceFields(type: Class<*>): List<Field> {
        val hierarchy = ArrayDeque<Class<*>>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            hierarchy.addFirst(current)
            current = current.superclass
        }
        return hierarchy.flatMap { owner ->
            owner.declaredFields.filterNot { field -> Modifier.isStatic(field.modifiers) }
                .onEach { field -> field.isAccessible = true }
        }
    }

    private fun boxedType(type: Class<*>): Class<*> = when (type) {
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Long::class.javaPrimitiveType -> Long::class.javaObjectType
        Float::class.javaPrimitiveType -> Float::class.javaObjectType
        Double::class.javaPrimitiveType -> Double::class.javaObjectType
        Short::class.javaPrimitiveType -> Short::class.javaObjectType
        Byte::class.javaPrimitiveType -> Byte::class.javaObjectType
        else -> type
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
