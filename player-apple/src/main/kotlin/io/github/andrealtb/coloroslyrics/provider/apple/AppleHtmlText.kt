/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.apple

import android.text.Html
import java.util.Locale

object AppleHtmlText {
    private val WHITESPACE = Regex("\\s+")
    private val TAGS = Regex("<[^>]+>")

    fun clean(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val stripped = if (raw.indexOf('<') >= 0 || raw.indexOf('&') >= 0) {
            runCatching {
                Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
            }.getOrElse {
                raw.replace(TAGS, "")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
                    .replace("&nbsp;", " ")
            }
        } else {
            raw
        }
        return stripped
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace(WHITESPACE, " ")
            .trim()
    }

    fun normalizeForCompare(value: String?): String =
        clean(value)
            .lowercase(Locale.ROOT)
            .replace(Regex("[\\s\\p{P}\\p{S}]+"), "")
}
