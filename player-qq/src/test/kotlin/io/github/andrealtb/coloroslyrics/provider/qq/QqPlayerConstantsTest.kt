/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qq

import org.junit.Assert.assertEquals
import org.junit.Test

class QqPlayerConstantsTest {
    @Test
    fun moduleAndHostAreOfficialQqOnly() {
        assertEquals("io.github.andrealtb.coloroslyrics.provider.qq", QqPlayerConstants.MODULE_PACKAGE)
        assertEquals("com.tencent.qqmusic", QqPlayerConstants.HOST_PACKAGE)
        assertEquals("qqmusic-internal", QqPlayerConstants.SOURCE_INTERNAL)
        assertEquals(listOf("com.tencent.qqmusic"), QqPlayerConstants.QUALIFIED_HOST_PACKAGES.toList())
    }
}
