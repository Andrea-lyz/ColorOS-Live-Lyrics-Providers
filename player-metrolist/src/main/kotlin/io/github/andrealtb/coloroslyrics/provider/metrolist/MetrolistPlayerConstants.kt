/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.metrolist

object MetrolistPlayerConstants {
    const val MODULE_PACKAGE = "io.github.andrealtb.coloroslyrics.provider.metrolist"
    const val HOST_PACKAGE = "com.metrolist.music"
    const val MUSIC_SERVICE_CLASS = "com.metrolist.music.playback.MusicService"
    const val MUSIC_SERVICE_ON_CREATE = "onCreate"
    const val MUSIC_SERVICE_ON_EVENTS = "onEvents"
    const val CURRENT_MEDIA_METADATA_FIELD = "currentMediaMetadata"

    val QUALIFIED_HOST_PACKAGES = arrayOf(HOST_PACKAGE)
}
