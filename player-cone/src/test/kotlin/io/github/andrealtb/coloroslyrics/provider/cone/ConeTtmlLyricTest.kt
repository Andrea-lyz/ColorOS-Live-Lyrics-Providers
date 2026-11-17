/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.cone

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.publisher.ColorOSLyricJsonEncoder
import io.github.andrealtb.coloroslyrics.provider.core.publisher.TtmlPayloadNormalizer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConeTtmlLyricTest {
    private val track = TrackIdentity(id = "1", title = "Duet", artist = "A & B")

    private val duet = """<?xml version="1.0" encoding="utf-8"?>
<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
  <head><metadata><ttm:agent type="person" xml:id="v1"/><ttm:agent type="person" xml:id="v2"/></metadata></head>
  <body>
    <div>
      <p begin="00:01.000" end="00:03.000" ttm:agent="v1"><span begin="00:01.000" end="00:02.000">Hello</span> <span begin="00:02.000" end="00:03.000">there</span></p>
      <p begin="00:04.000" end="00:06.000" ttm:agent="v2"><span begin="00:04.000" end="00:05.000">Hi</span> <span begin="00:05.000" end="00:06.000">back</span></p>
    </div>
  </body>
</tt>"""

    @Test
    fun broadcastOfATtmlDocumentIsAccepted() {
        val extracted = ConeBroadcastLyricExtractor.extract(
            ConePlayerConstants.ACTION_CURRENT_LYRIC_CHANGED,
            duet
        )
        assertEquals(duet, extracted)
        assertNull(
            ConeBroadcastLyricExtractor.extract(
                ConePlayerConstants.ACTION_CURRENT_LYRIC_CHANGED,
                "<html><body>not a lyric</body></html>"
            )
        )
    }

    @Test
    fun ttmlCandidateCarriesParsedLinesAndTheNormalizedDocument() {
        val candidate = ConeLyricCandidatePolicy().evaluate(ConeLyricSource.BROADCAST, duet, track)

        assertNotNull(candidate)
        assertEquals(listOf("Hello there", "Hi back"), candidate.lines.map { it.text })
        assertEquals(2, candidate.lines[0].words?.size)
        val ttml = assertNotNull(candidate.ttml)
        assertTrue(ttml.startsWith("<tt "))
        // Indentation is removed, the inline word space is kept.
        assertTrue(!ttml.contains('\n'))
        assertTrue(ttml.contains("Hello</span> <span"))
    }

    @Test
    fun lrcCandidateHasNoTtmlLane() {
        val candidate = ConeLyricCandidatePolicy()
            .evaluate(ConeLyricSource.BROADCAST, "[00:01.00]Hello\n[00:05.00]World", track)

        assertNotNull(candidate)
        assertNull(candidate.ttml)
    }

    @Test
    fun documentsTheBridgeWouldMisreadAreNotForwarded() {
        assertNull(TtmlPayloadNormalizer.normalize(duet.replace("<body>", "<body><audio lyricOffset=\"1.5\"/>")))
        assertNull(TtmlPayloadNormalizer.normalize("[00:01.00]Hello"))
        val huge = duet.replace("Hello", "x".repeat(TtmlPayloadNormalizer.MAX_TTML_CHARS))
        assertNull(TtmlPayloadNormalizer.normalize(huge))
        // The lyric itself still publishes from the parsed lines.
        assertNotNull(ConeLyricCandidatePolicy().evaluate(ConeLyricSource.BROADCAST, huge, track))
    }

    @Test
    fun encodedPayloadKeepsTheLrcLanesAndAddsTheTtmlLane() {
        val candidate = assertNotNull(
            ConeLyricCandidatePolicy().evaluate(ConeLyricSource.BROADCAST, duet, track)
        )

        val withTtml = assertNotNull(
            ColorOSLyricJsonEncoder.encode(track, candidate.lines, 1L, "ink.trantor.coneplayer", candidate.ttml)
        )
        val withoutTtml = assertNotNull(
            ColorOSLyricJsonEncoder.encode(track, candidate.lines, 1L, "ink.trantor.coneplayer")
        )

        assertEquals(withoutTtml.plainLyric, withTtml.plainLyric)
        assertEquals(withoutTtml.rawLyric, withTtml.rawLyric)
        assertTrue(withTtml.jsonValue.contains("\"ttml\":\"<tt "))
        assertTrue(!withoutTtml.jsonValue.contains("\"ttml\""))
    }

    /**
     * Writes the lyricInfo JSON for a real document so the Bridge test suite can consume exactly
     * what this Provider publishes. Lyrics are copyrighted and stay outside the repository:
     * set CLL_TTML_TEST_FILE to the document and CLL_LYRICINFO_OUT to the output path.
     */
    @Test
    fun externalDocumentEncodesToLyricInfo() {
        val source = System.getenv("CLL_TTML_TEST_FILE").orEmpty()
        val output = System.getenv("CLL_LYRICINFO_OUT").orEmpty()
        assumeTrue("CLL_TTML_TEST_FILE / CLL_LYRICINFO_OUT were not supplied", source.isNotEmpty() && output.isNotEmpty())
        val document = File(source).readText()

        val candidate = assertNotNull(
            ConeLyricCandidatePolicy().evaluate(ConeLyricSource.BROADCAST, document, track)
        )
        val encoded = assertNotNull(
            ColorOSLyricJsonEncoder.encode(track, candidate.lines, 1L, "ink.trantor.coneplayer", candidate.ttml)
        )
        assertNotNull(candidate.ttml)
        File(output).writeText(encoded.jsonValue)
    }
}
