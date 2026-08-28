/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

/**
 * Keeps Lite payloads structurally identical to the lyric rows ColorOS SystemUI
 * retains after it drops the timed promotional banner.
 */
object KuGouConceptLyricSanitizePolicy {
    private const val SYSTEM_UI_FILTERED_PROMO_PREFIX = "[听歌就在中国酷狗"
    private const val SYSTEM_UI_FILTERED_PROMO_MARKER = "星耀计划"

    fun shouldExcludeTimedPromoLine(text: String?): Boolean {
        val compact = text.orEmpty().filterNot { it.isWhitespace() }
        return compact.startsWith(SYSTEM_UI_FILTERED_PROMO_PREFIX) &&
            compact.contains(SYSTEM_UI_FILTERED_PROMO_MARKER) &&
            compact.endsWith("]")
    }
}
