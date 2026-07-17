package io.github.proify.lyricon.qishuiprovider.xposed

import io.github.proify.extensions.json
import io.github.proify.lyricon.qishuiprovider.xposed.parser.NetResponseCache
import io.github.proify.lyricon.qishuiprovider.xposed.parser.toRichLyric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetResponseCacheParserTest {

    @Test
    fun parsesBundledEnglishKrcFixtureWithAbsoluteWordTimes() {
        val payload = json.decodeFromString<NetResponseCache>(readResource("2.json"))
        val lines = payload.toRichLyric()

        assertTrue(lines.size > 20)
        val first = lines.first()
        assertEquals(22_010L, first.begin)
        assertEquals(26_450L, first.end)
        assertEquals("Every night in my dreams", first.text)
        assertEquals("每当夜幕降临 梦中的你浮现", first.translation)
        assertEquals("Every ", first.words.orEmpty()[0].text)
        assertEquals(22_010L, first.words.orEmpty()[0].begin)
        assertEquals("night ", first.words.orEmpty()[1].text)
        assertEquals(23_170L, first.words.orEmpty()[1].begin)
        assertEquals(26_450L, first.words.orEmpty().last().end)
    }

    @Test
    fun parsesBundledChineseKrcFixtureAsTimedSegments() {
        val payload = json.decodeFromString<NetResponseCache>(readResource("1.json"))
        val lines = payload.toRichLyric()

        assertTrue(lines.size > 20)
        val first = lines.first()
        assertEquals(0L, first.begin)
        assertEquals("忘了有多久再没听到你", first.text)
        assertEquals("忘", first.words.orEmpty().first().text)
        assertEquals(0L, first.words.orEmpty().first().begin)
        assertEquals(260L, first.words.orEmpty().first().end)
        assertEquals(5_070L, first.end)
    }

    private fun readResource(name: String): String {
        return requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "Missing test resource $name"
        }.bufferedReader().use { it.readText() }
    }
}
