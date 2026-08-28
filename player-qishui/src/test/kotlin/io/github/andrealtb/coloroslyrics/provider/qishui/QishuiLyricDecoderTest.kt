/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qishui

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QishuiLyricDecoderTest {
    @Test
    fun selectsChineseTranslationAndRejectsRomajiLane() {
        val cache = QishuiNetResponseCache(
            QishuiNetResponseCache.Lyric(
                type = "lrc",
                content = "[00:01.000]Hello",
                lang_translations = linkedMapOf(
                    "ROMAJI" to QishuiNetResponseCache.Translation(
                        type = "lrc",
                        content = "[00:01.000]Herro"
                    ),
                    "ZH-HANS-CN" to QishuiNetResponseCache.Translation(
                        type = "lrc",
                        content = "[00:01.080]你好"
                    )
                )
            )
        )
        val line = QishuiLyricDecoder.decode(cache, Locale.SIMPLIFIED_CHINESE).single()
        assertEquals("你好", line.secondary)
    }

    @Test
    fun romajiOnlyPayloadDoesNotBecomeTranslation() {
        val cache = QishuiNetResponseCache(
            QishuiNetResponseCache.Lyric(
                type = "lrc",
                content = "[00:01.000]こんにちは",
                lang_translations = mapOf(
                    "romaji" to QishuiNetResponseCache.Translation(
                        type = "lrc",
                        content = "[00:01.000]konnichiwa"
                    )
                )
            )
        )
        assertNull(QishuiLyricDecoder.decode(cache, Locale.JAPANESE).single().secondary)
    }
}
