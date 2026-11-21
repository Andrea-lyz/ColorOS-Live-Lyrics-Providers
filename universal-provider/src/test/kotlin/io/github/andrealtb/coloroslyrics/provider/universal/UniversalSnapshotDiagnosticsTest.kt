/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.universal

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UniversalSnapshotDiagnosticsTest {
    @Test
    fun summaryCarriesFlagsButNoSongText() {
        val summary = UniversalDiagnostics.snapshotSummary(
            """
            source=system_server
            boundPlayer=true
            selectionRevision=10
            sessions=1
            package=com.sonify.music
            title=我年轻过
            artist=刘德华
            titleSource=lookup
            generation=3
            rawTitle=作词：刘德华
            demux=polluted-unresolved
            lyricStatus=success
            history0=我年轻过	刘德华	qq	success
            """.trimIndent()
        )
        assertEquals(
            "selectionRevision=10 sessions=1 ownerPresent=true boundPlayer=true generation=3 " +
                "titlePresent=true titleSource=lookup demux=polluted-unresolved lyricStatus=success",
            summary
        )
        assertFalse(summary.contains("我年轻过"))
        assertFalse(summary.contains("刘德华"))
        assertFalse(summary.contains("sonify"))
    }

    @Test
    fun summaryReportsMissingIdentity() {
        val summary = UniversalDiagnostics.snapshotSummary(
            "selectionRevision=3\nsessions=0\npackage=(none)\ntitle=(missing)\nlyricStatus=pending\n"
        )
        assertTrue(summary.contains("ownerPresent=false"))
        assertTrue(summary.contains("titlePresent=false"))
        assertTrue(summary.contains("titleSource=?"))
    }
}
