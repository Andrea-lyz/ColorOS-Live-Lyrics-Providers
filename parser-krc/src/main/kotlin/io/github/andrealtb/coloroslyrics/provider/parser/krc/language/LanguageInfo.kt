/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.parser.krc.language

import kotlinx.serialization.Serializable

@Serializable
data class LanguageInfo(
    val content: List<LyricSection> = emptyList(),
    val version: Int = 1
) {
    fun getRoma() = content.find { it.type == LyricSection.TYPE_ROMA }
    fun getTranslate() = content.find { it.type == LyricSection.TYPE_TRANSLATE }
}
