/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.apple

import kotlinx.serialization.Serializable

@Serializable
data class AppleSongModel(
    val adamId: String,
    val name: String? = null,
    val artist: String? = null,
    val durationMs: Long = 0L,
    val lyrics: List<AppleLyricLineModel> = emptyList()
)

@Serializable
data class AppleLyricLineModel(
    val begin: Int = 0,
    val end: Int = 0,
    val duration: Int = 0,
    val htmlLineText: String? = null,
    val htmlTranslationLineText: String? = null,
    val htmlBackgroundVocalsLineText: String? = null,
    val htmlPronunciationLineText: String? = null,
    val words: List<AppleLyricWordModel> = emptyList(),
    val backgroundWords: List<AppleLyricWordModel> = emptyList()
)

@Serializable
data class AppleLyricWordModel(
    val begin: Int = 0,
    val end: Int = 0,
    val duration: Int = 0,
    val text: String? = null,
    val whitespace: Boolean = false
)
