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
import io.github.proify.extensions.bridge.LyricLineTruncator
import java.util.LinkedHashMap

/** Sends an injected Provider payload directly to SystemUI's static-whitelist v4 ingress. */
object SystemUiBroadcastSender {
    const val MAX_PARCEL_BYTES = ExternalLyricV4Protocol.MAX_PARCEL_BYTES

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

    /**
     * Sends the v4 payload and, if the standard {@link #submit} flow rejects
     * it as oversized (after the inline line-only downgrade), retries once
     * after dropping middle lines from the lyric extras via
     * {@link LyricLineTruncator}. The retry resets the three lyric extras to
     * the originals provided here so the truncator always sees the full
     * payload, not the already-downgraded string.
     *
     * <p>This path is intentionally Android-only: it lives next to
     * {@code submit} so callers that already pre-computed their lyric strings
     * can opt into the line-fallback by passing them in unchanged.</p>
     */
    fun submitWithLyricLineFallback(
        context: Context,
        payloadIntent: Intent,
        originalLyric: String,
        originalRawLyric: String,
        originalTranslationLyric: String,
        logTag: String,
        source: String
    ): Outcome {
        val initial = runCatching { submit(context, payloadIntent, logTag, source) }
        initial.exceptionOrNull()?.let { error ->
            if (error !is SystemUiBroadcastPayloadRejectedException) throw error
            return retryAfterLineTruncation(
                context = context,
                payloadIntent = payloadIntent,
                originalLyric = originalLyric,
                originalRawLyric = originalRawLyric,
                originalTranslationLyric = originalTranslationLyric,
                logTag = logTag,
                source = source
            )
        }
        return initial.getOrThrow()
    }

    private fun retryAfterLineTruncation(
        context: Context,
        payloadIntent: Intent,
        originalLyric: String,
        originalRawLyric: String,
        originalTranslationLyric: String,
        logTag: String,
        source: String
    ): Outcome {
        val budget = LyricLineTruncator.byteBudget(MAX_PARCEL_BYTES)
        val truncated = LyricLineTruncator.truncatePayload(
            lyric = originalLyric,
            rawLyric = originalRawLyric,
            translationLyric = originalTranslationLyric,
            maxBytes = budget
        )
        payloadIntent.putExtra(ExternalLyricV4Protocol.EXTRA_LYRIC, truncated.lyric.text)
        payloadIntent.putExtra(ExternalLyricV4Protocol.EXTRA_RAW_LYRIC, truncated.rawLyric.text)
        payloadIntent.putExtra(
            ExternalLyricV4Protocol.EXTRA_TRANSLATION_LYRIC,
            truncated.translationLyric.text
        )
        if (Log.isLoggable(logTag, Log.VERBOSE)) {
            Log.d(
                logTag,
                "SystemUI direct broadcast payload truncated after reject | source=$source " +
                    "removedLines=lyric:${truncated.lyric.removedLines}," +
                    "raw:${truncated.rawLyric.removedLines}," +
                    "translation:${truncated.translationLyric.removedLines} " +
                    "budget=$budget"
            )
        }
        return try {
            submit(context, payloadIntent, logTag, source)
        } catch (error: SystemUiBroadcastPayloadRejectedException) {
            // A second reject means the metadata alone exceeds the budget; there
            // is nothing lyric-local we can drop. Surface the original error so
            // the Provider's runCatching flow logs the failure once.
            throw error
        }
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
