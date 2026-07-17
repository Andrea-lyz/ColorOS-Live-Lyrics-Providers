/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.extensions.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class BridgePayloadSizingPolicyTest {
    @Test
    fun payloadAtBudgetIsAccepted() {
        assertEquals(
            BridgePayloadSizeAction.SEND,
            BridgePayloadSizingPolicy.decide(512, 512, canDowngradeWordTiming = true)
        )
    }

    @Test
    fun oversizedWordPayloadIsDowngradedBeforeRejection() {
        assertEquals(
            BridgePayloadSizeAction.DOWNGRADE_WORD_TIMING,
            BridgePayloadSizingPolicy.decide(513, 512, canDowngradeWordTiming = true)
        )
    }

    @Test
    fun oversizedNonLyricPayloadIsRejected() {
        assertEquals(
            BridgePayloadSizeAction.REJECT,
            BridgePayloadSizingPolicy.decide(513, 512, canDowngradeWordTiming = false)
        )
    }
}
