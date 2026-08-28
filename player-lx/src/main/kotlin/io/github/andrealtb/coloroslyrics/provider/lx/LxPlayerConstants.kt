/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.lx

object LxPlayerConstants {
    const val MODULE_PACKAGE = "io.github.andrealtb.coloroslyrics.provider.lx"

    const val LX_OFFICIAL_PACKAGE = "cn.toside.music.mobile"
    const val LX_WALNUT_PACKAGE = "com.lxwalnut.music.mobile"

    const val LYRIC_MODULE_OFFICIAL = "cn.toside.music.mobile.lyric.LyricModule"
    const val LYRIC_MODULE_WALNUT = "com.lxwalnut.music.mobile.lyric.LyricModule"
    /**
     * Walnut / LX-X still ships the historic `com.lxnetease` Java namespace inside
     * `com.lxwalnut.music.mobile`. This is a class-name fallback, not a host package.
     */
    const val LYRIC_MODULE_NETEASE = "com.lxnetease.music.mobile.lyric.LyricModule"

    val QUALIFIED_HOST_PACKAGES = arrayOf(
        LX_OFFICIAL_PACKAGE,
        LX_WALNUT_PACKAGE
    )

    val ALL_LYRIC_MODULE_CLASSES = arrayOf(
        LYRIC_MODULE_OFFICIAL,
        LYRIC_MODULE_WALNUT,
        LYRIC_MODULE_NETEASE
    )

    fun lyricModuleCandidates(hostPackage: String): List<String> {
        val preferred = when {
            hostPackage == LX_OFFICIAL_PACKAGE ||
                hostPackage.startsWith("$LX_OFFICIAL_PACKAGE.") ->
                listOf(LYRIC_MODULE_OFFICIAL)
            hostPackage == LX_WALNUT_PACKAGE ||
                hostPackage.startsWith("$LX_WALNUT_PACKAGE.") ->
                listOf(LYRIC_MODULE_WALNUT, LYRIC_MODULE_NETEASE)
            else -> emptyList()
        }
        if (preferred.isEmpty()) return emptyList()
        return (preferred + ALL_LYRIC_MODULE_CLASSES).distinct()
    }
}
