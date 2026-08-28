/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.poweramp

import io.github.andrealtb.coloroslyrics.provider.core.publisher.NativeLyricInfoPublisher
import org.junit.Test
import kotlin.test.assertEquals

class PowerampNativePublisherTest {
    @Test
    fun failedPlatformSessionCommitIsReported() {
        assertEquals(
            NativeLyricInfoPublisher.Result.COMMIT_FAILED,
            PowerampNativePublisher.classifyCommit(NativeLyricInfoPublisher.Result.PUBLISHED, false)
        )
    }

    @Test
    fun rejectedCoreResultIsPreserved() {
        assertEquals(
            NativeLyricInfoPublisher.Result.PAYLOAD_TOO_LARGE,
            PowerampNativePublisher.classifyCommit(
                NativeLyricInfoPublisher.Result.PAYLOAD_TOO_LARGE,
                false
            )
        )
    }
}
