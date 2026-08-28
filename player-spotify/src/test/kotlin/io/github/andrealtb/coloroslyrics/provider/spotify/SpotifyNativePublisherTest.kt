/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

import io.github.andrealtb.coloroslyrics.provider.core.publisher.NativeLyricInfoPublisher
import org.junit.Test
import kotlin.test.assertEquals

class SpotifyNativePublisherTest {
    @Test
    fun failedPlatformSessionCommitIsReported() {
        assertEquals(
            NativeLyricInfoPublisher.Result.COMMIT_FAILED,
            SpotifyNativePublisher.classifyCommit(NativeLyricInfoPublisher.Result.PUBLISHED, false)
        )
    }

    @Test
    fun rejectedCoreResultIsPreserved() {
        assertEquals(
            NativeLyricInfoPublisher.Result.PAYLOAD_TOO_LARGE,
            SpotifyNativePublisher.classifyCommit(
                NativeLyricInfoPublisher.Result.PAYLOAD_TOO_LARGE,
                false
            )
        )
    }
}
