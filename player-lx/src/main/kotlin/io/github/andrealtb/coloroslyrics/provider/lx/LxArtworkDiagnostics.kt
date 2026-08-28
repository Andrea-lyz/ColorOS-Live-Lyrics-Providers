/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.lx

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticHasher
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import java.util.Locale

internal object LxArtworkDiagnostics {
    private val bitmapLanes = arrayOf(
        "display" to MediaMetadata.METADATA_KEY_DISPLAY_ICON,
        "art" to MediaMetadata.METADATA_KEY_ART,
        "album" to MediaMetadata.METADATA_KEY_ALBUM_ART
    )
    private val uriLanes = arrayOf(
        "displayUri" to MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        "artUri" to MediaMetadata.METADATA_KEY_ART_URI,
        "albumUri" to MediaMetadata.METADATA_KEY_ALBUM_ART_URI
    )

    fun log(
        stage: String,
        metadata: MediaMetadata?,
        session: MediaSession? = null,
        generation: Long? = null
    ) {
        if (!StructuredDiagnostics.isDebugEnabled) return
        StructuredDiagnostics.logDebug(
            DiagnosticEvent(
                component = "provider/lx",
                area = "artwork",
                event = "ARTWORK_PROBE",
                session = stage,
                generation = generation,
                reason = "session=" + (session?.let { System.identityHashCode(it) } ?: 0),
                message = describeMetadata(metadata)
            )
        )
    }

    fun describeMetadata(metadata: MediaMetadata?): String {
        if (metadata == null) return "metadata=null"
        val bitmaps = bitmapLanes.joinToString(" | ") { (label, key) ->
            "$label=${describeBitmap(runCatching { metadata.getBitmap(key) }.getOrNull())}"
        }
        val uris = uriLanes.joinToString(" | ") { (label, key) ->
            "$label=${DiagnosticHasher.describeUri(metadata.getString(key))}"
        }
        val lyricInfoLength = metadata.getString("lyricInfo")?.length ?: 0
        return "$bitmaps | $uris | lyricInfo=$lyricInfoLength"
    }

    private fun describeBitmap(bitmap: Bitmap?): String {
        if (bitmap == null) return "null"
        if (bitmap.isRecycled) return "recycled"
        val allocation = runCatching { bitmap.allocationByteCount }.getOrDefault(-1)
        return buildString {
            append(bitmap.width).append('x').append(bitmap.height)
            append(':').append(bitmap.config?.name ?: "unknown")
            append(":alpha=").append(bitmap.hasAlpha())
            append(":bytes=").append(allocation)
            append(":generation=").append(bitmap.generationId)
            append(":identity=").append(System.identityHashCode(bitmap))
            append(":sample=").append(sampleBitmap(bitmap))
        }
    }

    private fun sampleBitmap(bitmap: Bitmap): String {
        if (bitmap.config == Bitmap.Config.HARDWARE) return "unavailable-hardware"
        val xs = intArrayOf(0, bitmap.width / 2, bitmap.width - 1)
        val ys = intArrayOf(0, bitmap.height / 2, bitmap.height - 1)
        val colors = ArrayList<Int>(9)
        return runCatching {
            ys.forEach { y -> xs.forEach { x -> colors += bitmap.getPixel(x, y) } }
            val first = colors.first()
            val maxDelta = colors.maxOf { colorDistance(first, it) }
            val unique = colors.toSet().size
            val classification = when {
                maxDelta == 0 -> "solid"
                maxDelta <= 12 -> "near-solid"
                else -> "varied"
            }
            "$classification:unique=$unique:maxDelta=$maxDelta:first=${colorHex(first)}"
        }.getOrElse { "unavailable-${it.javaClass.simpleName}" }
    }

    private fun colorDistance(left: Int, right: Int): Int = maxOf(
        kotlin.math.abs((left ushr 24) - (right ushr 24)),
        kotlin.math.abs(((left ushr 16) and 0xff) - ((right ushr 16) and 0xff)),
        kotlin.math.abs(((left ushr 8) and 0xff) - ((right ushr 8) and 0xff)),
        kotlin.math.abs((left and 0xff) - (right and 0xff))
    )

    private fun colorHex(color: Int): String = String.format(Locale.ROOT, "%08X", color)
}
