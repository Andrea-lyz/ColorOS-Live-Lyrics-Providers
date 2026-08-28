/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.apple

import io.github.andrealtb.coloroslyrics.provider.core.publisher.NativeLyricInfoPublisher
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppleNativePublisherTest {
    @Test
    fun failedPlatformSessionCommitIsReported() {
        assertEquals(
            NativeLyricInfoPublisher.Result.COMMIT_FAILED,
            AppleNativePublisher.classifyCommit(NativeLyricInfoPublisher.Result.PUBLISHED, false)
        )
    }

    @Test
    fun rejectedCoreResultIsPreserved() {
        assertEquals(
            NativeLyricInfoPublisher.Result.PAYLOAD_TOO_LARGE,
            AppleNativePublisher.classifyCommit(
                NativeLyricInfoPublisher.Result.PAYLOAD_TOO_LARGE,
                false
            )
        )
    }

    @Test
    fun gzipRoundTripPreservesJson() {
        val payload = "{\"adamId\":\"1\",\"lyrics\":[]}"
        val restored = AppleDiskSongCache.gunzip(AppleDiskSongCache.gzip(payload))
        assertEquals(payload, restored)
        assertTrue(AppleDiskSongCache.gzip(payload).size < payload.length + 64)
    }
}
