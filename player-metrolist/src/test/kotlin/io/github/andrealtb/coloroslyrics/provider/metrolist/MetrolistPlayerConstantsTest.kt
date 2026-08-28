/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.metrolist

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class MetrolistPlayerConstantsTest {
    @Test
    fun qualifiedHostIsOfficialMetrolistOnly() {
        assertEquals("com.metrolist.music", MetrolistPlayerConstants.HOST_PACKAGE)
        assertContentEquals(
            arrayOf("com.metrolist.music"),
            MetrolistPlayerConstants.QUALIFIED_HOST_PACKAGES
        )
        assertEquals(
            "io.github.andrealtb.coloroslyrics.provider.metrolist",
            MetrolistPlayerConstants.MODULE_PACKAGE
        )
    }
}

