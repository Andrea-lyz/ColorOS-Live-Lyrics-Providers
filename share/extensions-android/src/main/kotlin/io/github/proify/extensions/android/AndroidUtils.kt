/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.extensions.android

import java.lang.reflect.Method

/**
 * @author Lin
 */
object AndroidUtils {
    /**
     * v4.1: resolves the framework methods the KuWo provider overrides to force Bluetooth audio
     * state on, without any legacy Xposed helper. Callers install the constant-true hooks via
     * their libxposed API 102 runtime and fail open when this returns null.
     */
    fun findBluetoothA2dpOverrides(classLoader: ClassLoader?): Pair<Method, Method>? {
        if (classLoader == null) return null
        return runCatching {
            val isBluetoothA2dpOn = classLoader.loadClass("android.media.AudioManager")
                .getDeclaredMethod("isBluetoothA2dpOn")
            val isEnabled = classLoader.loadClass("android.bluetooth.BluetoothAdapter")
                .getDeclaredMethod("isEnabled")
            isBluetoothA2dpOn to isEnabled
        }.getOrNull()
    }

//    fun getStringForStateInt(state: Int): String {
//        return when (state) {
//            PlaybackState.STATE_NONE -> "NONE"
//            PlaybackState.STATE_STOPPED -> "STOPPED"
//            PlaybackState.STATE_PAUSED -> "PAUSED"
//            PlaybackState.STATE_PLAYING -> "PLAYING"
//            PlaybackState.STATE_FAST_FORWARDING -> "FAST_FORWARDING"
//            PlaybackState.STATE_REWINDING -> "REWINDING"
//            PlaybackState.STATE_BUFFERING -> "BUFFERING"
//            PlaybackState.STATE_ERROR -> "ERROR"
//            PlaybackState.STATE_CONNECTING -> "CONNECTING"
//            PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "SKIPPING_TO_PREVIOUS"
//            PlaybackState.STATE_SKIPPING_TO_NEXT -> "SKIPPING_TO_NEXT"
//            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "SKIPPING_TO_QUEUE_ITEM"
//            else -> "UNKNOWN"
//        }
//    }
}
