/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.parser.krc.language

import kotlinx.serialization.Serializable

@Serializable
data class LyricSection(
    val language: Int = 0,
    val lyricContent: List<List<String>> = emptyList(),
    val type: Int = 0
) {
    companion object {
        const val TYPE_ROMA = 0
        const val TYPE_TRANSLATE = 1
    }

    fun flatten(): List<String> {
        return lyricContent.map {
            it.joinToString("")
        }
    }
}
