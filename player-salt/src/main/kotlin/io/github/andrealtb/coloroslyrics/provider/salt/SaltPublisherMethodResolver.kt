/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.salt

import io.github.andrealtb.coloroslyrics.provider.reflection.CandidateResolver
import io.github.andrealtb.coloroslyrics.provider.reflection.ReflectionAmbiguityException
import io.github.andrealtb.coloroslyrics.provider.reflection.ReflectionNotFoundException
import java.lang.reflect.Method

object SaltPublisherMethodResolver {
    fun findInvokeSuspendMethod(publisherClass: Class<*>): Method {
        val named = findNamedInvokeSuspend(publisherClass)
        if (named != null) return named

        val structural = publisherClass.declaredMethods.filter { method ->
            method.parameterTypes.size == 1 && method.parameterTypes[0] == Object::class.java
        }
        return try {
            CandidateResolver.resolveUniqueMethod(
                structural,
                "${publisherClass.name}#invokeSuspend(Object)",
                "unique single Object parameter coroutine suspend body"
            )
        } catch (error: ReflectionAmbiguityException) {
            throw NoSuchMethodException(
                "Ambiguous Salt Player publisher method candidates: " +
                    error.candidateSignatures.joinToString(" vs ")
            )
        } catch (error: ReflectionNotFoundException) {
            throw NoSuchMethodException(
                "${publisherClass.name}#invokeSuspend(Object)"
            )
        }
    }

    fun findNamedInvokeSuspend(publisherClass: Class<*>): Method? {
        return publisherClass.declaredMethods.firstOrNull { method ->
            method.name == "invokeSuspend" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == Object::class.java
        }
    }
}
