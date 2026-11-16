/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.scoperequest

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every dedicated Provider in the matrix carries the scope request entry (through the shared
 * settings library or directly); the universal Provider, which binds players in its own app and
 * hooks system_server, never does (plan §7.0, §7.4).
 */
class ScopeRequestWiringTest {
    private val root = File("..").canonicalFile
    private val matrix = File(root, "release/v5-provider-matrix.json").readText()

    private fun providers(): List<Pair<String, String>> {
        val providerSection = matrix.substringAfter("\"providers\"")
        return Regex("\"module\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"processPolicy\"\\s*:\\s*\\{[^}]*?\"kind\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(providerSection).map { it.groupValues[1] to it.groupValues[2] }.toList()
    }

    private fun wired(module: String): Boolean {
        val build = File(root, "$module/build.gradle.kts").readText()
        return build.contains("project(\":provider-scope-request\")") || build.contains("project(\":provider-settings-api102\")")
    }

    @Test fun dedicatedProvidersCarryTheEntryAndUniversalDoesNot() {
        val all = providers()
        assertEquals(13, all.size)
        val (universal, dedicated) = all.partition { it.second == "system-server-bound-list" }
        assertEquals(listOf("universal-provider"), universal.map { it.first })
        val missing = dedicated.map { it.first }.filterNot(::wired)
        assertTrue("dedicated Providers without the scope request entry: $missing", missing.isEmpty())
        assertTrue("universal Provider must not carry the scope request entry", universal.none { wired(it.first) })
    }
}
