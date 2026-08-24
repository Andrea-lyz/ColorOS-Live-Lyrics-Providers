/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.reflection

import org.junit.Test
import java.net.URLClassLoader
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CandidateResolverTest {

    private class SampleClass {
        fun uniqueAction(arg: String): Int = arg.length
        fun overloadedAction(arg: String): String = arg
        fun overloadedAction(arg: Int): Int = arg
    }

    @Test
    fun testUniqueMethodResolutionSucceeds() {
        val methods = SampleClass::class.java.declaredMethods.filter { it.name == "uniqueAction" }
        val method = CandidateResolver.resolveUniqueMethod(methods, "SampleClass#uniqueAction")
        assertEquals("uniqueAction", method.name)
        assertEquals(Int::class.javaPrimitiveType, method.returnType)
    }

    @Test
    fun testNotFoundMethodThrowsException() {
        val methods = SampleClass::class.java.declaredMethods.filter { it.name == "nonExistent" }
        val exception = assertFailsWith<ReflectionNotFoundException> {
            CandidateResolver.resolveUniqueMethod(methods, "SampleClass#nonExistent")
        }
        assertTrue(exception.message!!.contains("SampleClass#nonExistent"))
    }

    @Test
    fun testAmbiguousMethodThrowsExceptionWithoutFirstSelection() {
        val methods = SampleClass::class.java.declaredMethods.filter { it.name == "overloadedAction" }
        val exception = assertFailsWith<ReflectionAmbiguityException> {
            CandidateResolver.resolveUniqueMethod(methods, "SampleClass#overloadedAction")
        }
        assertTrue(exception.message!!.contains("strictly forbidden"))
        assertTrue(exception.message!!.contains("Found 2 candidates"))
    }

    @Test
    fun testReflectionCacheInvalidationOnClassLoaderChange() {
        val loader1 = URLClassLoader(emptyArray())
        val loader2 = URLClassLoader(emptyArray())

        val cache = ReflectionCache(loader1, "1.0.0")
        assertTrue(cache.isValid(loader1))
        assertFalse(cache.isValid(loader2))

        cache.ensureValid(loader2)
        assertTrue(cache.isValid(loader2))
        assertFalse(cache.isValid(loader1))
    }
}
