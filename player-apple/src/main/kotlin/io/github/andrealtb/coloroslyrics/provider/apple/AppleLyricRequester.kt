/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.apple

import android.app.Application
import android.os.Handler
import android.os.Looper
import java.lang.reflect.Method

/**
 * Owns a Provider-constructed [PlayerLyricsViewModel]. The official lyrics
 * fragment's instance may already be cleared or still bound to the previous
 * song; hitchhiking it makes skip-time `loadLyrics` a no-op until a later retry.
 */
class AppleLyricRequester(
    private val classLoader: ClassLoader,
    private val application: Application,
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) {
    @Volatile
    private var playerLyricsViewModel: Any? = null

    @Volatile
    private var loadLyricsMethod: Method? = null

    fun setLoadLyricsMethod(method: Method) {
        method.isAccessible = true
        loadLyricsMethod = method
    }

    fun requestDownload(playbackItem: Any): Boolean = runCatching {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            callLoadLyrics(playbackItem)
        } else {
            mainHandler.post { runCatching { callLoadLyrics(playbackItem) } }
        }
        true
    }.getOrDefault(false)

    private fun callLoadLyrics(playbackItem: Any) {
        val viewModel = ensurePlayerLyricsViewModel()
        val method = loadLyricsMethod
        if (method != null && method.declaringClass.isInstance(viewModel)) {
            method.invoke(viewModel, playbackItem)
            return
        }
        AppleNativeCalls.call(viewModel, ApplePlayerConstants.LOAD_LYRICS, playbackItem)
    }

    private fun ensurePlayerLyricsViewModel(): Any {
        playerLyricsViewModel?.let { return it }
        return classLoader
            .loadClass(ApplePlayerConstants.PLAYER_LYRICS_VIEW_MODEL)
            .getConstructor(Application::class.java)
            .newInstance(application)
            .also { playerLyricsViewModel = it }
    }
}
