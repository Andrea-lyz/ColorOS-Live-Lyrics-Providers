/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.paprovider.xposed

import android.util.Log
import com.highcapable.yukihookapi.hook.log.YLog

object PowerampLog {
    @Volatile
    private var yukiLogAvailable = true

    fun isDebugEnabled(tag: String = Constants.LOG_TAG): Boolean =
        runCatching { Log.isLoggable(tag, Log.DEBUG) }.getOrDefault(false)

    fun debug(tag: String = Constants.LOG_TAG, msg: String, e: Throwable? = null) {
        if (!isDebugEnabled(tag)) return
        safeLog(tag, msg, e, Log::d) {
            YLog.debug(tag = tag, msg = msg, e = e)
        }
    }

    fun info(tag: String = Constants.LOG_TAG, msg: String, e: Throwable? = null) {
        safeLog(tag, msg, e, Log::i) {
            YLog.info(tag = tag, msg = msg, e = e)
        }
    }

    fun error(tag: String = Constants.LOG_TAG, msg: String, e: Throwable? = null) {
        safeLog(tag, msg, e, Log::e) {
            YLog.error(tag = tag, msg = msg, e = e)
        }
    }

    private inline fun safeLog(
        tag: String,
        msg: String,
        e: Throwable?,
        fallback: (String, String, Throwable?) -> Int,
        block: () -> Unit
    ) {
        if (yukiLogAvailable) {
            try {
                block()
                return
            } catch (_: Throwable) {
                yukiLogAvailable = false
            }
        }
        try {
            fallback(tag, msg, e)
        } catch (_: Throwable) {
            // Logging must never affect the host player process.
        }
    }
}
