/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qishui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QishuiResolutionPolicyTest {
    @Test
    fun retryScheduleIsBounded() {
        assertEquals(0L, QishuiResolutionPolicy.delayBeforeAttempt(0))
        assertEquals(4_500L, QishuiResolutionPolicy.delayBeforeAttempt(7))
        assertNull(QishuiResolutionPolicy.delayBeforeAttempt(8))
    }
}
