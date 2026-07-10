package io.github.proify.lyricon.paprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackMetadataCacheTest {
    @Test
    fun acceptsPowerampIntegerDuration() {
        assertEquals(295_000L, bundleLong(295_000, 0L))
    }

    @Test
    fun preservesLongAndSafelyFallsBackForInvalidValues() {
        assertEquals(295_000L, bundleLong(295_000L, 0L))
        assertEquals(295_000L, bundleLong("295000", 0L))
        assertEquals(0L, bundleLong("not-a-number", 0L))
        assertEquals(-1L, bundleLong(null, -1L))
    }
}
