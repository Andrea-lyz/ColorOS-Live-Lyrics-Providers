/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.reflection

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Strict candidate resolution helper that forbids blind first candidate selection.
 */
object CandidateResolver {

    fun resolveUniqueMethod(
        candidates: List<Method>,
        targetName: String,
        searchCriteria: String = "method matching signature",
        hostVersion: String? = null
    ): Method {
        return when (candidates.size) {
            1 -> candidates[0]
            0 -> throw ReflectionNotFoundException(targetName, searchCriteria, hostVersion)
            else -> throw ReflectionAmbiguityException(
                targetName,
                candidates.map { m ->
                    "${m.declaringClass.name}#${m.name}(${m.parameterTypes.joinToString { it.simpleName }}): ${m.returnType.simpleName}"
                },
                hostVersion
            )
        }
    }

    fun resolveUniqueField(
        candidates: List<Field>,
        targetName: String,
        searchCriteria: String = "field matching signature",
        hostVersion: String? = null
    ): Field {
        return when (candidates.size) {
            1 -> candidates[0]
            0 -> throw ReflectionNotFoundException(targetName, searchCriteria, hostVersion)
            else -> throw ReflectionAmbiguityException(
                targetName,
                candidates.map { f ->
                    "${f.declaringClass.name}#${f.name}: ${f.type.simpleName}"
                },
                hostVersion
            )
        }
    }

    fun resolveUniqueConstructor(
        candidates: List<Constructor<*>>,
        targetName: String,
        searchCriteria: String = "constructor matching signature",
        hostVersion: String? = null
    ): Constructor<*> {
        return when (candidates.size) {
            1 -> candidates[0]
            0 -> throw ReflectionNotFoundException(targetName, searchCriteria, hostVersion)
            else -> throw ReflectionAmbiguityException(
                targetName,
                candidates.map { c ->
                    "${c.declaringClass.name}(${c.parameterTypes.joinToString { it.simpleName }})"
                },
                hostVersion
            )
        }
    }
}
