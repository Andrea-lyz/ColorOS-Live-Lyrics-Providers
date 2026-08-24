/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.core.config

import android.content.Context
import android.content.SharedPreferences

object ProviderDebugConfig {

    const val PREFS_NAME_SUFFIX = "_provider_debug_prefs"
    const val KEY_DEBUG_ENABLED = "provider_debug_logging_enabled"

    fun isDebugEnabled(context: Context?, playerTag: String): Boolean {
        if (context == null) return false

        return runCatching {
            val prefs = getPreferences(context, playerTag)
            prefs.getBoolean(KEY_DEBUG_ENABLED, false)
        }.getOrDefault(false)
    }

    fun setDebugEnabled(context: Context?, playerTag: String, enabled: Boolean): Boolean {
        if (context == null) return false

        return runCatching {
            val prefs = getPreferences(context, playerTag)
            prefs.edit().putBoolean(KEY_DEBUG_ENABLED, enabled).commit()
        }.getOrDefault(false)
    }

    private fun getPreferences(context: Context, playerTag: String): SharedPreferences {
        val safeTag = playerTag.trim().lowercase().replace(Regex("""[^a-z0-9_]"""), "_")
        return context.getSharedPreferences("${safeTag}$PREFS_NAME_SUFFIX", Context.MODE_PRIVATE)
    }
}
