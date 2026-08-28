/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpotifyShadedHeadersResolverTest {
    @Test
    fun acceptsOnlyImmutableIterableStringArrayHeaderContainers() {
        assertTrue(
            SpotifyShadedHeadersResolver.isShadedHeadersConstructor(
                HeaderFixture::class.java.getDeclaredConstructor(Array<String>::class.java)
            )
        )
        assertFalse(
            SpotifyShadedHeadersResolver.isShadedHeadersConstructor(
                NonIterableFixture::class.java.getDeclaredConstructor(Array<String>::class.java)
            )
        )
    }

    private class HeaderFixture(private val values: Array<String>) : Iterable<String> {
        fun value(name: String): String = name
        override fun iterator(): Iterator<String> = values.iterator()
    }

    private class NonIterableFixture(private val values: Array<String>) {
        fun value(name: String): String = name
    }
}
