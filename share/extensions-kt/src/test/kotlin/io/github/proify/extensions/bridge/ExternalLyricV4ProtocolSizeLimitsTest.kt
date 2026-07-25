/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.extensions.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the v4 size limits declared on the Provider side so that any
 * adjustment to {@link ExternalLyricV4Protocol} is caught by the unit-test
 * suite instead of silently drifting away from the Bridge-side mirror at
 * `io.github.andrealtb.lockscreenlyrics.protocol.ExternalLyricProtocol`.
 *
 * <p>Both sides must be edited in lock-step when raising these values. The
 * test below encodes the current agreement as raw assertions; updating the
 * limits is therefore a two-file change (Provider + Bridge).</p>
 */
class ExternalLyricV4ProtocolSizeLimitsTest {

    @Test
    fun maxParcelBytesMatchesBridgeMirror() {
        assertEquals(524_288, ExternalLyricV4Protocol.MAX_PARCEL_BYTES)
    }

    @Test
    fun maxLyricFieldCharsMatchesBridgeMirror() {
        assertEquals(1_500_000, ExternalLyricV4Protocol.MAX_LYRIC_FIELD_CHARS)
    }

    @Test
    fun maxTotalLyricCharsMatchesBridgeMirror() {
        assertEquals(3_000_000, ExternalLyricV4Protocol.MAX_TOTAL_LYRIC_CHARS)
    }

    @Test
    fun maxMetadataFieldCharsMatchesBridgeMirror() {
        assertEquals(16_384, ExternalLyricV4Protocol.MAX_METADATA_FIELD_CHARS)
    }

    @Test
    fun lyricFieldsStayInsideParcelByteBudget() {
        // Total lyric char budget is intentionally larger than the parcel
        // byte budget once a worst-case UTF-8 / UTF-16 encoding is applied.
        // This is the gap that LyricLineTruncator is responsible for closing:
        // the Provider drops middle lines so the marshalled payload fits
        // inside MAX_PARCEL_BYTES. The test pins the assumption so any
        // adjustment to the char limits that would silently break the
        // truncator contract becomes a deliberate review.
        val totalLyricBytesWorstCase = ExternalLyricV4Protocol.MAX_TOTAL_LYRIC_CHARS * 4L
        assertTrue(
            "Total lyric char budget must remain larger than the parcel byte " +
                "budget once a 4-bytes-per-char worst case is applied (got " +
                "$totalLyricBytesWorstCase vs ${ExternalLyricV4Protocol.MAX_PARCEL_BYTES})",
            totalLyricBytesWorstCase > ExternalLyricV4Protocol.MAX_PARCEL_BYTES
        )
    }
}