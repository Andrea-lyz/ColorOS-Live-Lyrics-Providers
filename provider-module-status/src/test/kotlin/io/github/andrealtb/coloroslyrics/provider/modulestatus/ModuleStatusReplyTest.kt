package io.github.andrealtb.coloroslyrics.provider.modulestatus

import android.content.pm.PackageManager
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class ModuleStatusReplyTest {
    @Test fun notConnectedRevealsNoScope() {
        assertEquals(mapOf<String, Any>("protocol" to 1, "connected" to false), ModuleStatusReply.values(null))
    }

    @Test fun connectedReportsApiAndSortedDistinctScope() {
        val values = ModuleStatusReply.values(ModuleStatusReply.ServiceFacts(102, listOf("b", "a", "b")))
        assertEquals(true, values["connected"])
        assertEquals(102, values["apiVersion"])
        assertEquals(listOf("a", "b"), values["scope"])
    }

    @Test fun onlySameSignerCallersAreAnswered() {
        assertTrue(ModuleStatusReply.allowed(PackageManager.SIGNATURE_MATCH))
        for (other in listOf(PackageManager.SIGNATURE_NO_MATCH, PackageManager.SIGNATURE_UNKNOWN_PACKAGE,
            PackageManager.SIGNATURE_NEITHER_SIGNED, PackageManager.SIGNATURE_FIRST_NOT_SIGNED)) {
            assertFalse(ModuleStatusReply.allowed(other))
        }
    }

    @Test fun waitingWithoutAServiceTimesOut() {
        val start = System.nanoTime()
        assertEquals(null, ModuleStatus.awaitService(50))
        assertTrue(System.nanoTime() - start >= 40_000_000)
    }
}
