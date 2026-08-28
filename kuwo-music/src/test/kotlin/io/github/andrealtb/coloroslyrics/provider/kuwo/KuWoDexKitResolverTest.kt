package io.github.andrealtb.coloroslyrics.provider.kuwo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KuWoDexKitResolverTest {
    @Test
    fun lyricFetchShapeRejectsUnrelatedMethods() {
        val unrelated = FakeTargets::class.java.getDeclaredMethod(
            "unrelated",
            String::class.java,
            Boolean::class.javaPrimitiveType,
            String::class.java
        )

        assertFalse(KuWoDexKitResolver.isLyricFetchMethod(unrelated))
    }

    @Test
    fun parserShapeAcceptsInstanceStringParser() {
        val parser = FakeParser::class.java.getDeclaredMethod("parse", String::class.java)
        val staticParser = FakeTargets::class.java.getDeclaredMethod("staticParse", String::class.java)

        assertTrue(KuWoDexKitResolver.isLrcxParserMethod(parser))
        assertFalse(KuWoDexKitResolver.isLrcxParserMethod(staticParser))
    }

    class FakeParser {
        fun parse(value: String): FakeParserResult = FakeParserResult(value)
    }

    class FakeParserResult(private val value: String) {
        fun lines(): List<String> = listOf(value)
    }

    class FakeTargets {
        companion object {
            @JvmStatic
            fun unrelated(first: String, enabled: Boolean, third: String): Any =
                "$first|$enabled|$third"

            @JvmStatic
            fun staticParse(value: String): FakeParserResult = FakeParserResult(value)
        }
    }
}
