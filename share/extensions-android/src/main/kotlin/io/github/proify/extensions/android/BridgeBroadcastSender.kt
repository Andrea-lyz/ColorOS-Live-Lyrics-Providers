/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.extensions.android

import android.content.Context
import android.content.Intent
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import io.github.proify.extensions.bridge.BridgePayloadSizeAction
import io.github.proify.extensions.bridge.BridgePayloadSizingPolicy
import java.util.LinkedHashMap

object BridgeBroadcastSender {
    const val MAX_PARCEL_BYTES = 512 * 1024

    private const val EVENT_LYRIC_READY = "lyricReady"
    private const val EXTRA_EVENT_TYPE = "eventType"
    private const val EXTRA_LYRIC = "lyric"
    private const val EXTRA_RAW_LYRIC = "rawLyric"
    private const val WARNING_THROTTLE_MS = 60_000L
    private const val MAX_WARNING_KEYS = 64

    private val lastWarningAtByKey = object : LinkedHashMap<String, Long>(
        MAX_WARNING_KEYS,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > MAX_WARNING_KEYS
        }
    }

    data class Outcome(
        val parcelBytes: Int,
        val downgradedWordTiming: Boolean
    )

    fun send(
        context: Context,
        intent: Intent,
        logTag: String,
        source: String
    ): Outcome {
        val originalBytes = parcelDataSize(intent)
        val plainLyric = intent.getStringExtra(EXTRA_LYRIC).orEmpty()
        val rawLyric = intent.getStringExtra(EXTRA_RAW_LYRIC).orEmpty()
        val canDowngrade = intent.getStringExtra(EXTRA_EVENT_TYPE) == EVENT_LYRIC_READY &&
            plainLyric.isNotBlank() &&
            rawLyric.isNotBlank() &&
            rawLyric != plainLyric

        when (BridgePayloadSizingPolicy.decide(
            parcelBytes = originalBytes,
            maxParcelBytes = MAX_PARCEL_BYTES,
            canDowngradeWordTiming = canDowngrade
        )) {
            BridgePayloadSizeAction.SEND -> {
                context.sendBroadcast(intent)
                return Outcome(originalBytes, downgradedWordTiming = false)
            }
            BridgePayloadSizeAction.DOWNGRADE_WORD_TIMING -> {
                intent.putExtra(EXTRA_RAW_LYRIC, plainLyric)
                val downgradedBytes = parcelDataSize(intent)
                if (BridgePayloadSizingPolicy.decide(
                        parcelBytes = downgradedBytes,
                        maxParcelBytes = MAX_PARCEL_BYTES,
                        canDowngradeWordTiming = false
                    ) == BridgePayloadSizeAction.SEND
                ) {
                    if (Log.isLoggable(logTag, Log.DEBUG)) {
                        Log.d(
                            logTag,
                            "Bridge payload downgraded to line timing | source=$source " +
                                "bytes=$originalBytes->$downgradedBytes"
                        )
                    }
                    context.sendBroadcast(intent)
                    return Outcome(downgradedBytes, downgradedWordTiming = true)
                }
                throw oversized(source, downgradedBytes, downgraded = true)
            }
            BridgePayloadSizeAction.REJECT -> {
                throw oversized(source, originalBytes, downgraded = false)
            }
        }
    }

    fun shouldReportFailure(error: Throwable): Boolean {
        return error !is BridgePayloadRejectedException || error.reportable
    }

    private fun parcelDataSize(intent: Intent): Int {
        val parcel = Parcel.obtain()
        return try {
            intent.writeToParcel(parcel, 0)
            parcel.dataSize()
        } finally {
            parcel.recycle()
        }
    }

    private fun oversized(
        source: String,
        parcelBytes: Int,
        downgraded: Boolean
    ): BridgePayloadRejectedException {
        val key = "$source:${if (downgraded) "line" else "original"}"
        return BridgePayloadRejectedException(
            message = "Bridge payload rejected before Binder send | source=$source " +
                "bytes=$parcelBytes limit=$MAX_PARCEL_BYTES downgraded=$downgraded",
            reportable = shouldReportWarning(key)
        )
    }

    @Synchronized
    private fun shouldReportWarning(key: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        val last = lastWarningAtByKey[key]
        if (last != null && now >= last && now - last < WARNING_THROTTLE_MS) return false
        lastWarningAtByKey[key] = now
        return true
    }
}

class BridgePayloadRejectedException(
    message: String,
    val reportable: Boolean
) : IllegalStateException(message)
