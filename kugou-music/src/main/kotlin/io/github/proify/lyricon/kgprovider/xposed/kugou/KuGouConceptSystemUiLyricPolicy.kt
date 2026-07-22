/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 */

package io.github.proify.lyricon.kgprovider.xposed.kugou

/**
 * Keeps the Lite payload's raw word-timed lane structurally identical to the lyric rows that
 * ColorOS SystemUI retains in its RecyclerView.
 */
internal object KuGouConceptSystemUiLyricPolicy {
    private const val SYSTEM_UI_FILTERED_PROMO_PREFIX = "[听歌就在中国酷狗"
    private const val SYSTEM_UI_FILTERED_PROMO_MARKER = "星耀计划"

    fun shouldExcludeTimedPromoLine(text: String?): Boolean {
        val compact = text.orEmpty().filterNot { it.isWhitespace() }
        return compact.startsWith(SYSTEM_UI_FILTERED_PROMO_PREFIX) &&
            compact.contains(SYSTEM_UI_FILTERED_PROMO_MARKER) &&
            compact.endsWith("]")
    }
}
