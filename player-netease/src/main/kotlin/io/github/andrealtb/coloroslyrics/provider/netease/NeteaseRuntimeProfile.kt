/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.netease

/** Runtime path selected from the host package and the MediaSession owner process. */
enum class NeteaseRuntimeProfile {
    OFFICIAL_APPEND,
    CONSTRUCTED;

    companion object {
        fun resolve(hostPackage: String, processName: String): NeteaseRuntimeProfile? {
            return when (hostPackage) {
                NeteasePlayerConstants.HOST_PACKAGE -> when (processName) {
                    NeteasePlayerConstants.HOST_PACKAGE -> OFFICIAL_APPEND
                    NeteasePlayerConstants.NETEASE_PLAY_PROCESS -> CONSTRUCTED
                    else -> null
                }
                NeteasePlayerConstants.HONOR_HOST_PACKAGE -> when (processName) {
                    NeteasePlayerConstants.HONOR_HOST_PACKAGE,
                    NeteasePlayerConstants.HONOR_PLAY_PROCESS -> OFFICIAL_APPEND
                    else -> null
                }
                else -> null
            }
        }
    }
}
