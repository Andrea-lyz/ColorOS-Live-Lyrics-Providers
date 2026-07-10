/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.Application
import com.highcapable.yukihookapi.hook.log.YLog
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

class LyricRequester(
    private val classLoader: ClassLoader,
    private val application: Application
) {
    private var playerLyricsViewModel: Any? = null
    private var loadLyricsMethod: Method? = null

    fun setPlayerLyricsViewModel(instance: Any?) {
        if (instance == null) return
        playerLyricsViewModel = instance
    }

    fun setLoadLyricsMethod(method: Method) {
        method.isAccessible = true
        loadLyricsMethod = method
    }

    fun requestDownload(playbackItem: Any): Boolean {
        return try {
            callLoadLyrics(playbackItem)
            true
        } catch (e: Exception) {
            YLog.error("LyricRequester: Failed to trigger download from PlaybackItem", e)
            false
        }
    }

    private fun callLoadLyrics(playbackItem: Any) {
        val viewModel = ensurePlayerLyricsViewModel()
        val method = loadLyricsMethod
        if (method != null && method.declaringClass.isInstance(viewModel)) {
            method.invoke(viewModel, playbackItem)
            return
        }
        XposedHelpers.callMethod(viewModel, "loadLyrics", playbackItem)
    }

    private fun ensurePlayerLyricsViewModel(): Any {
        playerLyricsViewModel?.let { return it }
        return classLoader
            .loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel")
            .getConstructor(Application::class.java)
            .newInstance(application)
            .also { playerLyricsViewModel = it }
    }
}
