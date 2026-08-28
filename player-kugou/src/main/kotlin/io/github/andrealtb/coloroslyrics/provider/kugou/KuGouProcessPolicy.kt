/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

object KuGouProcessPolicy {

    fun shouldHook(hostPackage: String, processName: String): Boolean {
        return when (hostPackage) {
            KuGouPlayerConstants.STANDARD_PACKAGE -> isSupportProcess(processName)
            KuGouPlayerConstants.LITE_PACKAGE -> isSupportProcess(processName)
            else -> false
        }
    }

    /**
     * Lite lyric load and TRACK_BOUND live on `.support`. Hooking Lite main as
     * well dual-published KGMediaSession and ColorOS bound the paused main
     * session to the media card (`lyrics-log-20260827-054900.txt`).
     */
    private fun isSupportProcess(processName: String): Boolean =
        processName.endsWith(":support") || processName.endsWith(".support")
}
