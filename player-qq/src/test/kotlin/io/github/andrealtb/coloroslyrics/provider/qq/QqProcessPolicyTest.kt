/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qq

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QqProcessPolicyTest {

    @Test
    fun hooksOnlyThePlayerServiceProcess() {
        assertTrue(
            QqProcessPolicy.shouldHook(
                QqPlayerConstants.HOST_PACKAGE,
                "com.tencent.qqmusic:QQPlayerService"
            )
        )
        assertFalse(
            QqProcessPolicy.shouldHook(
                QqPlayerConstants.HOST_PACKAGE,
                "com.tencent.qqmusic"
            )
        )
        assertFalse(
            QqProcessPolicy.shouldHook(
                QqPlayerConstants.HOST_PACKAGE,
                "com.tencent.qqmusic:push"
            )
        )
        assertFalse(
            QqProcessPolicy.shouldHook(
                "com.tencent.qqmusicpad",
                "com.tencent.qqmusicpad:QQPlayerService"
            )
        )
    }
}
