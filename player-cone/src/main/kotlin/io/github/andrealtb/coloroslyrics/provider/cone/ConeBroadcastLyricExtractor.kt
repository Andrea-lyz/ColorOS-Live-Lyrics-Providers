/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.cone

import android.content.Intent

object ConeBroadcastLyricExtractor {

    fun extract(intent: Intent?): String? {
        if (intent == null) return null
        return extract(intent.action, intent.getStringExtra(ConePlayerConstants.EXTRA_LYRIC_TEXT))
    }

    fun extract(action: String?, rawLyric: String?): String? {
        if (action != ConePlayerConstants.ACTION_CURRENT_LYRIC_CHANGED) return null
        if (rawLyric.isNullOrBlank()) return null
        val trimmed = rawLyric.trim()
        return if (ConeLyricFilter.isUsableTimedLyric(trimmed)) trimmed else null
    }
}
