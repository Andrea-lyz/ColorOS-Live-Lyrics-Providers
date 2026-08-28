/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.apple

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplePlayerConstantsTest {
    @Test
    fun qualifiedHostIsOfficialAppleMusicOnly() {
        assertEquals("com.apple.android.music", ApplePlayerConstants.HOST_PACKAGE)
        assertContentEquals(
            arrayOf("com.apple.android.music"),
            ApplePlayerConstants.QUALIFIED_HOST_PACKAGES
        )
        assertEquals(
            "io.github.andrealtb.coloroslyrics.provider.apple",
            ApplePlayerConstants.MODULE_PACKAGE
        )
    }

    @Test
    fun castTagsAreRejected() {
        assertTrue(ApplePlayerConstants.isCastSessionTag("CastMediaSession"))
        assertTrue(ApplePlayerConstants.isCastSessionTag("com.google.android.gms.cast"))
        assertFalse(ApplePlayerConstants.isCastSessionTag("MediaPlaybackService"))
        assertFalse(ApplePlayerConstants.isCastSessionTag("com.apple.android.music"))
        assertFalse(ApplePlayerConstants.isCastSessionTag(null))
        assertFalse(ApplePlayerConstants.isCastSessionTag(""))
    }
}
