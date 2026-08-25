/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.salt

import org.junit.Test
import java.lang.reflect.Method
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SaltPublisherMethodResolverTest {

    @Test
    fun prefersLiteralInvokeSuspendName() {
        val method = SaltPublisherMethodResolver.findInvokeSuspendMethod(
            NamedPublisher::class.java
        )
        assertEquals("invokeSuspend", method.name)
        assertEquals(1, method.parameterTypes.size)
        assertEquals(Object::class.java, method.parameterTypes[0])
    }

    @Test
    fun fallsBackToUniqueSingleObjectParameterMethod() {
        val method = SaltPublisherMethodResolver.findInvokeSuspendMethod(
            ObfuscatedPublisher::class.java
        )
        assertEquals("mo135", method.name)
        assertEquals(1, method.parameterTypes.size)
        assertEquals(Object::class.java, method.parameterTypes[0])
    }

    @Test
    fun matchesFixture12_3_0CjkPublisherName() {
        val method = SaltPublisherMethodResolver.findInvokeSuspendMethod(
            CjkFixturePublisher::class.java
        )
        assertEquals("迉", method.name)
        assertEquals(1, method.parameterTypes.size)
        assertEquals(Object::class.java, method.parameterTypes[0])
    }

    @Test
    fun matchesFixture12_2_1CjkPublisherName() {
        val method = SaltPublisherMethodResolver.findInvokeSuspendMethod(
            Cjk221Publisher::class.java
        )
        assertEquals("庄", method.name)
    }

    @Test
    fun rejectsPublisherWithoutSuspendShape() {
        val error = assertFailsWith<NoSuchMethodException> {
            SaltPublisherMethodResolver.findInvokeSuspendMethod(
                NoCandidatePublisher::class.java
            )
        }
        assertTrue(error.message!!.contains("NoCandidatePublisher"))
    }

    @Test
    fun rejectsAmbiguousStructuralCandidates() {
        val error = assertFailsWith<NoSuchMethodException> {
            SaltPublisherMethodResolver.findInvokeSuspendMethod(
                AmbiguousPublisher::class.java
            )
        }
        assertTrue(error.message!!.contains("Ambiguous"))
    }

    @Test
    fun namedCandidatesAreFound() {
        val method = SaltPublisherMethodResolver.findNamedInvokeSuspend(
            NamedPublisher::class.java
        )
        assertNotNull(method)
        assertEquals("invokeSuspend", method?.name)
    }

    private class NamedPublisher {
        @Suppress("UNUSED_PARAMETER")
        fun invoke(a: Any?, b: Any?): Any? = null

        @Suppress("UNUSED_PARAMETER")
        fun invokeSuspend(o: Any?): Any? = null
    }

    private class ObfuscatedPublisher {
        @Suppress("UNUSED_PARAMETER")
        fun mo321(a: Any?, b: Any?): Any? = null

        @Suppress("UNUSED_PARAMETER")
        fun mo135(o: Any?): Any? = null
    }

    private class CjkFixturePublisher {
        @Suppress("UNUSED_PARAMETER")
        fun mo119(a: Any?, b: Any?): Any? = null

        @Suppress("UNUSED_PARAMETER")
        fun mo173(a: Any?, b: Any?): Any? = null

        @Suppress("UNUSED_PARAMETER")
        fun 迉(o: Any?): Any? = null
    }

    private class Cjk221Publisher {
        @Suppress("UNUSED_PARAMETER")
        fun 庄(o: Any?): Any? = null
    }

    private class NoCandidatePublisher {
        @Suppress("UNUSED_PARAMETER")
        fun invoke(a: Any?, b: Any?): Any? = null
    }

    private class AmbiguousPublisher {
        @Suppress("UNUSED_PARAMETER")
        fun first(o: Any?): Any? = null

        @Suppress("UNUSED_PARAMETER")
        fun second(o: Any?): Any? = null
    }
}
