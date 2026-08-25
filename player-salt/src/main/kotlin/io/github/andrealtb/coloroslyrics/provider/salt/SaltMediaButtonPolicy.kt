/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.salt

import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics

object SaltMediaButtonPolicy {
    private val startLock = Any()
    @Volatile
    private var lastAcceptedNanos = 0L

    internal var nanoClock: () -> Long = System::nanoTime

    fun shouldAcceptMediaButtonStart(): Boolean {
        val now = nanoClock()
        synchronized(startLock) {
            val accepted = lastAcceptedNanos <= 0L ||
                now - lastAcceptedNanos >=
                SaltPlayerConstants.MEDIA_BUTTON_DEBOUNCE_MS * 1_000_000L
            if (accepted) lastAcceptedNanos = now
            return accepted
        }
    }

    internal fun resetForTesting(clock: () -> Long = System::nanoTime) {
        synchronized(startLock) {
            lastAcceptedNanos = 0L
            nanoClock = clock
        }
    }

    fun isPlayMediaButtonIntent(intent: Intent?): Boolean {
        if (intent == null || !Intent.ACTION_MEDIA_BUTTON.equals(intent.action)) return false
        val event = mediaButtonKeyEvent(intent) ?: return true
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
            event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            event.keyCode == KeyEvent.KEYCODE_HEADSETHOOK
    }

    fun startSaltService(context: Context?, action: String?) {
        if (context == null) return
        val intent = Intent()
        intent.setClassName(
            SaltPlayerConstants.SALT_PACKAGE,
            SaltPlayerConstants.MUSIC_SERVICE_CLASS
        )
        if (!action.isNullOrBlank()) intent.action = action
        runCatching {
            context.startForegroundService(intent)
        }.onFailure { error ->
            StructuredDiagnostics.logError(
                DiagnosticEvent(
                    component = "provider/salt",
                    area = "media-button",
                    event = "SERVICE_START_FAILED",
                    reason = error.javaClass.simpleName,
                    message = "Salt MusicService start rejected."
                )
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun mediaButtonKeyEvent(intent: Intent): KeyEvent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
        }
    }
}
