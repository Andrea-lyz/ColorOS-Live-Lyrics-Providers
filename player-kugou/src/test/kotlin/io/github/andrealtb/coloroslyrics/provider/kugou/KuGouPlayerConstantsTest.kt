/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KuGouPlayerConstantsTest {

    @Test
    fun hostsShareOneModulePackage() {
        assertEquals(
            "io.github.andrealtb.coloroslyrics.provider.kugou",
            KuGouPlayerConstants.MODULE_PACKAGE
        )
        assertTrue(
            KuGouPlayerConstants.QUALIFIED_HOST_PACKAGES.contentEquals(
                arrayOf("com.kugou.android", "com.kugou.android.lite")
            )
        )
        assertTrue(KuGouPlayerConstants.isStandard("com.kugou.android"))
        assertTrue(KuGouPlayerConstants.isLite("com.kugou.android.lite"))
        assertEquals("kugou-internal", KuGouPlayerConstants.SOURCE_INTERNAL)
        assertTrue(KuGouPlayerConstants.isPrimarySessionTag("KGMediaSession"))
        assertTrue(KuGouPlayerConstants.isPrimarySessionTag(null))
        assertTrue(KuGouPlayerConstants.isPrimarySessionTag(""))
        assertFalse(KuGouPlayerConstants.isPrimarySessionTag("CastMediaSession"))
    }
}
