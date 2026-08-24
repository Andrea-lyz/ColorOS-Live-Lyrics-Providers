/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.reflection

class ReflectionNotFoundException(
    val targetName: String,
    val searchCriteria: String,
    val hostVersion: String? = null,
    cause: Throwable? = null
) : RuntimeException(
    "Target '$targetName' was not found (criteria='$searchCriteria', hostVersion='${hostVersion ?: "unknown"}').",
    cause
)

class ReflectionAmbiguityException(
    val targetName: String,
    val candidateSignatures: List<String>,
    val hostVersion: String? = null
) : RuntimeException(
    "Target '$targetName' is ambiguous! Found ${candidateSignatures.size} candidates (hostVersion='${hostVersion ?: "unknown"}'). " +
        "Selecting the first candidate without constraints is strictly forbidden. Candidates:\n" +
        candidateSignatures.joinToString("\n") { " - $it" }
)
