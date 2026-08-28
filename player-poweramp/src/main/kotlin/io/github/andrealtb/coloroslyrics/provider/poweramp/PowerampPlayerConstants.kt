/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.poweramp

object PowerampPlayerConstants {
    const val MODULE_PACKAGE = "io.github.andrealtb.coloroslyrics.provider.poweramp"
    const val HOST_PACKAGE = "com.maxmpz.audioplayer"
    const val MAIN_SESSION_TAG = "Poweramp"
    const val CAST_SESSION_TAG = "CastMediaSession"
    const val ACTION_TRACK_CHANGED = "com.maxmpz.audioplayer.TRACK_CHANGED"

    val QUALIFIED_HOST_PACKAGES = arrayOf(HOST_PACKAGE)

    fun isCastSessionTag(tag: String?): Boolean =
        tag == CAST_SESSION_TAG
}
