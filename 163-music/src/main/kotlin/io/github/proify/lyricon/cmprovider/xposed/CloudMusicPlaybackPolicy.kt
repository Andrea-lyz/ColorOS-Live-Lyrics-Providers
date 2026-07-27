/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.cmprovider.xposed

import io.github.proify.extensions.bridge.PlaybackCommitPolicy
import io.github.proify.extensions.bridge.PlaybackTrackToken

object CloudMusicPlaybackPolicy {
    const val NETEASE_PACKAGE = "com.netease.cloudmusic"
    const val HONOR_PACKAGE = "com.hihonor.cloudmusic"
    const val PLAY_PROCESS_SUFFIX = ":play"

    /**
     * The historical 9.0.40 build publishes its MediaSession from :play,
     * while the Honor build publishes it from the package process.  Keep the
     * decision package-aware so unrelated push/render processes never become
     * a second lyric event source.
     */
    fun isPlaybackProcess(packageName: String?, processName: String?): Boolean {
        if (packageName.isNullOrBlank() || processName.isNullOrBlank()) return false
        return when (packageName) {
            HONOR_PACKAGE -> processName == HONOR_PACKAGE
            NETEASE_PACKAGE -> processName == NETEASE_PACKAGE ||
                processName == NETEASE_PACKAGE + PLAY_PROCESS_SUFFIX
            else -> false
        }
    }

    /**
     * Upstream discovers showLyricSetting with DexKit in both the package and
     * :play processes. Preserve that behavior unless the host APK already
     * contains a conflicting native DexKit library.
     */
    fun allowsDexKitPreferenceDiscovery(hostBundlesDexKit: Boolean): Boolean =
        !hostBundlesDexKit

    /** Compatibility overload used by lightweight policy tests/callers. */
    fun isPlaybackProcess(processName: String?): Boolean =
        processName == NETEASE_PACKAGE || processName == HONOR_PACKAGE ||
            processName == NETEASE_PACKAGE + PLAY_PROCESS_SUFFIX

    fun acceptsDownload(
        current: PlaybackTrackToken?,
        requested: PlaybackTrackToken?,
        responseMediaId: String?
    ): Boolean = PlaybackCommitPolicy.acceptsResult(current, requested, responseMediaId)
}
