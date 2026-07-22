/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.lxprovider.xposed.variant.lxwalnut

import io.github.proify.lyricon.lxprovider.xposed.variant.main.LXMusic

/**
 * LX-X packages the app as {@code com.lxwalnut.music.mobile}; the checked source tree still uses
 * the historic {@code com.lxnetease} Java namespace. Keep both class candidates inside the one
 * permitted host process so a namespace-only repack does not broaden the module scope.
 */
class LxWalnut : LXMusic(
    "com.lxwalnut.music.mobile.lyric.LyricModule",
    "com.lxnetease.music.mobile.lyric.LyricModule"
)
