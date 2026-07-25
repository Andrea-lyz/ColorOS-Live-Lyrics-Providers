/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.krckit.language

import io.github.proify.extensions.ProviderDiagnostics
import kotlinx.serialization.json.Json

object LanguageParser {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun parse(jsonString: String): LanguageInfo? {
        return try {
            json.decodeFromString<LanguageInfo>(jsonString)
        } catch (e: Exception) {
            ProviderDiagnostics.logWarning("krc-language-parse") {
                "Failed to decode language JSON: ${e.message}"
            }
            null
        }
    }
}