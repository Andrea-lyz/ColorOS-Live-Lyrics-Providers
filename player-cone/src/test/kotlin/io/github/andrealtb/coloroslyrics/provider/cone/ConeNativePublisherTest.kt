/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.cone

import io.github.andrealtb.coloroslyrics.provider.core.publisher.NativeLyricInfoPublisher
import org.junit.Test
import kotlin.test.assertEquals

class ConeNativePublisherTest {

    @Test
    fun classifyCommit_preservesPublicationResultWhenSucceeded() {
        val result = ConeNativePublisher.classifyCommit(
            NativeLyricInfoPublisher.Result.PUBLISHED,
            sessionCommitSucceeded = true
        )
        assertEquals(NativeLyricInfoPublisher.Result.PUBLISHED, result)
    }

    @Test
    fun classifyCommit_returnsCommitFailedWhenPublishSucceededButSessionCommitFailed() {
        val result = ConeNativePublisher.classifyCommit(
            NativeLyricInfoPublisher.Result.PUBLISHED,
            sessionCommitSucceeded = false
        )
        assertEquals(NativeLyricInfoPublisher.Result.COMMIT_FAILED, result)
    }
}
