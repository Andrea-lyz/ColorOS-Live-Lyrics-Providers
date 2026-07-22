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
import io.github.proify.extensions.bridge.ExternalLyricV4Protocol
import java.util.LinkedHashMap

/** Sends an injected Provider payload directly to SystemUI's static-whitelist v4 ingress. */
object SystemUiBroadcastSender {
    const val MAX_PARCEL_BYTES = 512 * 1024

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

    fun submit(
        context: Context,
        payloadIntent: Intent,
        logTag: String,
        source: String
    ): Outcome {
        prepareDirectBroadcast(context, payloadIntent, source)
        val originalBytes = parcelDataSize(payloadIntent)
        val plainLyric = payloadIntent.getStringExtra(ExternalLyricV4Protocol.EXTRA_LYRIC).orEmpty()
        val rawLyric = payloadIntent.getStringExtra(ExternalLyricV4Protocol.EXTRA_RAW_LYRIC).orEmpty()
        val canDowngrade =
            payloadIntent.getStringExtra(ExternalLyricV4Protocol.EXTRA_EVENT_TYPE) ==
                ExternalLyricV4Protocol.EVENT_LYRIC_READY &&
            plainLyric.isNotBlank() &&
            rawLyric.isNotBlank() &&
            rawLyric != plainLyric

        when (BridgePayloadSizingPolicy.decide(
            parcelBytes = originalBytes,
            maxParcelBytes = MAX_PARCEL_BYTES,
            canDowngradeWordTiming = canDowngrade
        )) {
            BridgePayloadSizeAction.SEND -> {
                context.sendBroadcast(payloadIntent)
                return Outcome(originalBytes, downgradedWordTiming = false)
            }
            BridgePayloadSizeAction.DOWNGRADE_WORD_TIMING -> {
                payloadIntent.putExtra(ExternalLyricV4Protocol.EXTRA_RAW_LYRIC, plainLyric)
                val downgradedBytes = parcelDataSize(payloadIntent)
                if (BridgePayloadSizingPolicy.decide(
                        parcelBytes = downgradedBytes,
                        maxParcelBytes = MAX_PARCEL_BYTES,
                        canDowngradeWordTiming = false
                    ) == BridgePayloadSizeAction.SEND
                ) {
                    if (Log.isLoggable(logTag, Log.VERBOSE)) {
                        Log.d(
                            logTag,
                            "SystemUI direct broadcast payload downgraded to line timing | source=$source " +
                                "bytes=$originalBytes->$downgradedBytes"
                        )
                    }
                    context.sendBroadcast(payloadIntent)
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
        return error !is SystemUiBroadcastPayloadRejectedException || error.reportable
    }

    private fun prepareDirectBroadcast(context: Context, payloadIntent: Intent, source: String) {
        val playerPackage = context.packageName
        payloadIntent.action = ExternalLyricV4Protocol.ACTION_DIRECT_LYRIC_CAPTURED
        payloadIntent.setPackage(ExternalLyricV4Protocol.SYSTEMUI_PACKAGE)
        payloadIntent.putExtra(
            ExternalLyricV4Protocol.EXTRA_PROTOCOL_VERSION,
            ExternalLyricV4Protocol.PROTOCOL_VERSION
        )
        payloadIntent.putExtra(ExternalLyricV4Protocol.EXTRA_SOURCE, source)
        payloadIntent.putExtra(ExternalLyricV4Protocol.EXTRA_PLAYER_PACKAGE, playerPackage)
        payloadIntent.putExtra(ExternalLyricV4Protocol.EXTRA_SENDER_PACKAGE, playerPackage)
        payloadIntent.putExtra(
            ExternalLyricV4Protocol.EXTRA_SENDER_KIND,
            ExternalLyricV4Protocol.SENDER_KIND_PROVIDER
        )
    }

    private fun parcelDataSize(payloadIntent: Intent): Int {
        val parcel = Parcel.obtain()
        return try {
            payloadIntent.writeToParcel(parcel, 0)
            parcel.dataSize()
        } finally {
            parcel.recycle()
        }
    }

    private fun oversized(
        source: String,
        parcelBytes: Int,
        downgraded: Boolean
    ): SystemUiBroadcastPayloadRejectedException {
        val key = "$source:${if (downgraded) "line" else "original"}"
        return SystemUiBroadcastPayloadRejectedException(
            message = "SystemUI direct broadcast payload rejected before send | source=$source " +
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

class SystemUiBroadcastPayloadRejectedException(
    message: String,
    val reportable: Boolean
) : IllegalStateException(message)
