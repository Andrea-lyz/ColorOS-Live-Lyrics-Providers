/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KuGouProcessPolicyTest {

    @Test
    fun standardHooksOnlyTheSupportPlaybackProcess() {
        assertTrue(
            KuGouProcessPolicy.shouldHook(
                KuGouPlayerConstants.STANDARD_PACKAGE,
                "com.kugou.android:support"
            )
        )
        assertTrue(
            KuGouProcessPolicy.shouldHook(
                KuGouPlayerConstants.STANDARD_PACKAGE,
                "com.kugou.android.support"
            )
        )
        assertFalse(
            KuGouProcessPolicy.shouldHook(
                KuGouPlayerConstants.STANDARD_PACKAGE,
                "com.kugou.android"
            )
        )
    }

    @Test
    fun liteHooksSupportButNotMainOrMessage() {
        assertFalse(
            KuGouProcessPolicy.shouldHook(
                KuGouPlayerConstants.LITE_PACKAGE,
                "com.kugou.android.lite"
            )
        )
        assertTrue(
            KuGouProcessPolicy.shouldHook(
                KuGouPlayerConstants.LITE_PACKAGE,
                "com.kugou.android.lite.support"
            )
        )
        assertTrue(
            KuGouProcessPolicy.shouldHook(
                KuGouPlayerConstants.LITE_PACKAGE,
                "com.kugou.android.lite:support"
            )
        )
        assertFalse(
            KuGouProcessPolicy.shouldHook(
                KuGouPlayerConstants.LITE_PACKAGE,
                "com.kugou.android.lite.message"
            )
        )
        assertFalse(
            KuGouProcessPolicy.shouldHook(
                KuGouPlayerConstants.LITE_PACKAGE,
                "com.kugou.android.lite:push"
            )
        )
        assertFalse(
            KuGouProcessPolicy.shouldHook(
                KuGouPlayerConstants.STANDARD_PACKAGE,
                "com.kugou.android.lite"
            )
        )
    }
}
