/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KuGouLyricManagerResolverTest {

    @Test
    fun acceptsTheDocumentedLoadMethodShape() {
        assertTrue(
            KuGouLyricManagerResolver.matchesLoadMethod(
                KuGouPlayerConstants.LYRIC_MANAGER_CLASS,
                listOf(String::class.java, Boolean::class.javaPrimitiveType),
                listOf(KuGouPlayerConstants.LOAD_FAILURE_STRING)
            )
        )
    }

    @Test
    fun rejectsWrongClassNameOrSignature() {
        assertFalse(
            KuGouLyricManagerResolver.matchesLoadMethod(
                "fm5.e",
                listOf(String::class.java, Boolean::class.javaPrimitiveType),
                listOf(KuGouPlayerConstants.LOAD_FAILURE_STRING)
            )
        )
        assertFalse(
            KuGouLyricManagerResolver.matchesLoadMethod(
                KuGouPlayerConstants.LYRIC_MANAGER_CLASS,
                listOf(ByteArray::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType),
                listOf(KuGouPlayerConstants.LOAD_FAILURE_STRING)
            )
        )
        assertFalse(
            KuGouLyricManagerResolver.matchesLoadMethod(
                KuGouPlayerConstants.LYRIC_MANAGER_CLASS,
                listOf(String::class.java, Boolean::class.javaPrimitiveType),
                listOf("unrelated")
            )
        )
    }
}
