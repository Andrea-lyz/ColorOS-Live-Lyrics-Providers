/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.paprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerampBridgeTaggedLyricParserTest {
    @Test
    fun parsesInlineBracketWordTimingAndTranslationWithoutCreatingExtraRows() {
        val raw = """
            [01:57.617]Now [01:58.170]all [01:58.882]we [01:59.524]know [02:01.689]is [02:01.929]don't [02:02.545]let [02:03.209]go[02:04.569]
            [01:57.617]现在我们唯一的心愿就是不要放手
            [02:05.833]We [02:06.025]are [02:06.289]alone [02:06.969]just [02:07.335]you [02:07.663]and [02:08.095]me[02:08.744]
            [02:05.833]除了彼此 我们身边再无他人
        """.trimIndent()

        val lines = PowerampBridgeTaggedLyricParser.parse(raw)

        assertEquals(2, lines.size)
        assertEquals(117_617L, lines[0].begin)
        assertEquals("Now all we know is don't let go", lines[0].text)
        assertEquals("现在我们唯一的心愿就是不要放手", lines[0].translation)
        assertEquals(8, lines[0].segments.size)
        assertEquals(124_569L, lines[0].end)
        assertEquals("We are alone just you and me", lines[1].text)
        assertEquals(128_744L, lines[1].end)
    }

    @Test
    fun ignoresPlainLrcSoTheExistingSongConversionRemainsTheFallback() {
        val lines = PowerampBridgeTaggedLyricParser.parse(
            "[00:10.000]First line\n[00:15.000]Second line"
        )

        assertTrue(lines.isEmpty())
    }
}
