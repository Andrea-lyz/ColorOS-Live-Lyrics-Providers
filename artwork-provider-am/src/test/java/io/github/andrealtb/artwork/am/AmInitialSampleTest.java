package io.github.andrealtb.artwork.am;

import java.util.ArrayList;
import org.junit.Test;
import static org.junit.Assert.*;

public class AmInitialSampleTest {
    @Test public void invalidTimeNeverCallsFlagsAndIsLoggedBeforeFailure() {
        var events = new ArrayList<String>();
        try {
            AmInitialSample.read(() -> -1, () -> { throw new AssertionError("flags must not be read"); }, events::add);
            fail();
        } catch (AmFailure failure) {
            assertEquals("source_no_initial_sample", failure.reason);
            assertTrue(failure.detail.contains("timeUs=-1 flags=not_read"));
            assertEquals("stage=initial_time timeUs=-1", events.get(0));
        }
    }

    @Test public void throwingFlagsRetainsAlreadyReadTimeAndExactFailureStage() {
        var events = new ArrayList<String>();
        try {
            AmInitialSample.read(() -> 83333, () -> { throw new IllegalArgumentException("private.mp4"); }, events::add);
            fail();
        } catch (AmFailure failure) {
            assertEquals("source_sample_read_failed", failure.reason);
            assertTrue(failure.detail.contains("timeUs=83333"));
            assertTrue(failure.detail.contains("stage=extractor_initial_sample_flags"));
            assertFalse(failure.detail.contains("private.mp4"));
            assertEquals("stage=initial_time timeUs=83333", events.get(0));
        }
    }

    @Test public void diagnosticsDoNotChangeValidOrNonSyncSampleValues() throws Exception {
        var sample = AmInitialSample.read(() -> 0, () -> 1, null);
        assertEquals(0, sample.timeUs()); assertEquals(1, sample.flags());
        var nonSync = AmInitialSample.read(() -> 123, () -> 0, ignored -> { throw new IllegalStateException(); });
        assertEquals(123, nonSync.timeUs()); assertEquals(0, nonSync.flags());
    }

    @Test public void timeFailureNeverCallsFlags() {
        try {
            AmInitialSample.read(() -> { throw new IllegalArgumentException(); },
                    () -> { throw new AssertionError("flags must not be read"); }, null);
            fail();
        } catch (AmFailure failure) {
            assertEquals("source_sample_read_failed", failure.reason);
            assertTrue(failure.detail.contains("stage=extractor_initial_sample_time"));
        }
    }
}
