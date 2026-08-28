/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpotifyCronetHeaderResolverTest {
    @Test
    fun acceptsOnlyConcreteStringPairAddHeaderMethods() {
        assertTrue(
            SpotifyCronetHeaderResolver.isConcreteAddHeaderMethod(
                Fixture::class.java.getDeclaredMethod(
                    "addHeader",
                    String::class.java,
                    String::class.java
                )
            )
        )
        assertFalse(
            SpotifyCronetHeaderResolver.isConcreteAddHeaderMethod(
                Fixture::class.java.getDeclaredMethod("addHeader", String::class.java)
            )
        )
        assertFalse(
            SpotifyCronetHeaderResolver.isConcreteAddHeaderMethod(
                Fixture::class.java.getDeclaredMethod(
                    "putHeader",
                    String::class.java,
                    String::class.java
                )
            )
        )
    }

    private class Fixture {
        fun addHeader(name: String, value: String) = name + value
        fun addHeader(name: String) = name
        fun putHeader(name: String, value: String) = name + value
    }
}
