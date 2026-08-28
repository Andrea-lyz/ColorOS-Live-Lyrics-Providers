/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpotifyRetryPolicyTest {
    @Test
    fun headerWaitUsesBoundedExponentialBackoff() {
        assertEquals(150L, SpotifyRetryPolicy.nextHeaderWaitDelayMs(0))
        assertEquals(2_400L, SpotifyRetryPolicy.nextHeaderWaitDelayMs(4))
        assertNull(SpotifyRetryPolicy.nextHeaderWaitDelayMs(5))
        assertEquals(1, SpotifyRetryPolicy.MAX_UNAUTHORIZED_RETRIES)
    }
}
