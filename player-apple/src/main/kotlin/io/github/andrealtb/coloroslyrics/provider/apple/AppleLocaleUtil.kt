/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.apple

object AppleLocaleUtil {
    fun systemLyricsLanguage(classLoader: ClassLoader): String? = runCatching {
        val type = classLoader.loadClass(ApplePlayerConstants.LOCALE_UTIL)
        val method = type.getDeclaredMethod("getSystemLyricsLanguage")
        method.isAccessible = true
        method.invoke(null) as? String
    }.getOrNull()?.takeIf { !it.isNullOrBlank() }
}
