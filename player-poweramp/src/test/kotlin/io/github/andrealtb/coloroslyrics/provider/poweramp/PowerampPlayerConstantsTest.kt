/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.poweramp

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PowerampPlayerConstantsTest {
    @Test
    fun hostIsOfficialPowerampOnly() {
        assertContentEquals(
            arrayOf("com.maxmpz.audioplayer"),
            PowerampPlayerConstants.QUALIFIED_HOST_PACKAGES
        )
        assertEquals("io.github.andrealtb.coloroslyrics.provider.poweramp",
            PowerampPlayerConstants.MODULE_PACKAGE)
        assertTrue(PowerampPlayerConstants.isCastSessionTag("CastMediaSession"))
        assertFalse(PowerampPlayerConstants.isCastSessionTag("Poweramp"))
        assertFalse(PowerampPlayerConstants.isCastSessionTag(null))
    }
}
