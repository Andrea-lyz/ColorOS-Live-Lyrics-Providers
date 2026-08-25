package io.github.andrealtb.coloroslyrics.provider.salt

import io.github.andrealtb.coloroslyrics.provider.core.publisher.NativeLyricInfoPublisher
import org.junit.Test
import kotlin.test.assertEquals

class SaltNativePublisherTest {
    @Test fun failedPlatformSessionCommitIsReported() {
        assertEquals(NativeLyricInfoPublisher.Result.COMMIT_FAILED,
            SaltNativePublisher.classifyCommit(NativeLyricInfoPublisher.Result.PUBLISHED, false))
    }

    @Test fun rejectedCoreResultIsPreserved() {
        assertEquals(NativeLyricInfoPublisher.Result.PAYLOAD_TOO_LARGE,
            SaltNativePublisher.classifyCommit(NativeLyricInfoPublisher.Result.PAYLOAD_TOO_LARGE, false))
    }
}
