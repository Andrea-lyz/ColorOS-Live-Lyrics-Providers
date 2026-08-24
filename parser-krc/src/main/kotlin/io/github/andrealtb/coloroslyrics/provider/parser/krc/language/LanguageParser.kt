/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.parser.krc.language

import kotlinx.serialization.json.Json

object LanguageParser {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun parse(jsonString: String): LanguageInfo? {
        return runCatching {
            json.decodeFromString<LanguageInfo>(jsonString)
        }.getOrNull()
    }
}
