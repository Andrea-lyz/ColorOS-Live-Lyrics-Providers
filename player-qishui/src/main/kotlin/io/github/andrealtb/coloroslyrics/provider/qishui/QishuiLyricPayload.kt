/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("PropertyName")

package io.github.andrealtb.coloroslyrics.provider.qishui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class QishuiNetResponseCache(
    val lyric: Lyric? = null
) {
    @Serializable
    data class Lyric(
        val type: String? = null,
        val content: String? = null,
        @SerialName("lyric")
        val lyricText: String? = null,
        val lang_translations: Map<String, Translation>? = null
    ) {
        val resolvedContent: String?
            get() = content ?: lyricText
    }

    @Serializable
    data class Translation(
        val content: String? = null,
        @SerialName("lyric")
        val lyricText: String? = null,
        val type: String? = null
    ) {
        val resolvedContent: String?
            get() = content ?: lyricText
    }
}
