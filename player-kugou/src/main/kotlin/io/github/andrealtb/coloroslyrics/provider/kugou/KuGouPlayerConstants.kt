/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

object KuGouPlayerConstants {
    const val MODULE_PACKAGE = "io.github.andrealtb.coloroslyrics.provider.kugou"
    const val STANDARD_PACKAGE = "com.kugou.android"
    const val LITE_PACKAGE = "com.kugou.android.lite"
    const val MEDIA_SESSION_TAG = "KGMediaSession"
    const val LYRIC_MANAGER_CLASS = "com.kugou.framework.lyric.LyricManager"
    const val LYRIC_DATA_CLASS = "com.kugou.framework.lyric.LyricData"
    const val LOAD_FAILURE_STRING = "file is not krc or lyc or txt file"
    const val SOURCE_INTERNAL = "kugou-internal"
    const val METADATA_KEY_LYRIC_INFO = "lyricInfo"

    val QUALIFIED_HOST_PACKAGES = arrayOf(
        STANDARD_PACKAGE,
        LITE_PACKAGE
    )

    fun isLite(hostPackage: String): Boolean = hostPackage == LITE_PACKAGE

    fun isStandard(hostPackage: String): Boolean = hostPackage == STANDARD_PACKAGE

    fun isPrimarySessionTag(tag: String?): Boolean =
        tag.isNullOrBlank() || tag == MEDIA_SESSION_TAG
}
