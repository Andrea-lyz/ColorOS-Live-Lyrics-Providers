/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.extensions.bridge

enum class BridgePayloadSizeAction {
    SEND,
    DOWNGRADE_WORD_TIMING,
    REJECT
}

object BridgePayloadSizingPolicy {
    fun decide(
        parcelBytes: Int,
        maxParcelBytes: Int,
        canDowngradeWordTiming: Boolean
    ): BridgePayloadSizeAction {
        if (parcelBytes < 0 || maxParcelBytes <= 0) return BridgePayloadSizeAction.REJECT
        if (parcelBytes <= maxParcelBytes) return BridgePayloadSizeAction.SEND
        return if (canDowngradeWordTiming) {
            BridgePayloadSizeAction.DOWNGRADE_WORD_TIMING
        } else {
            BridgePayloadSizeAction.REJECT
        }
    }
}
