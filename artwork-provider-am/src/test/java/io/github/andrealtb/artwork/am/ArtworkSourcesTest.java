package io.github.andrealtb.artwork.am;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

public class ArtworkSourcesTest {
    @Test public void cachedNeteaseVideoDoesNotWaitForAnotherAppleNetworkAttempt() throws Exception {
        List<String> order = new ArrayList<>();
        assertEquals("netease-file", ArtworkSources.automaticCached(true,
                () -> { order.add("apple-cache"); return null; },
                () -> { order.add("netease-cache"); return "netease-file"; },
                () -> { fail("cached fallback must not wait for Apple network"); return "apple"; },
                () -> { fail("verified fallback must not redownload"); return "netease"; }));
        assertEquals(List.of("apple-cache", "netease-cache"), order);
    }
    @Test public void cachedAppleVideoStillOutranksNetease() throws Exception {
        assertEquals("apple-file", ArtworkSources.automaticCached(true, () -> "apple-file",
                () -> { fail("Apple file already available"); return "netease-file"; },
                () -> { fail("no remote lookup needed"); return "apple"; },
                () -> { fail("no fallback needed"); return "netease"; }));
    }
    @Test public void coldQueriesStillTryAppleBeforeNeteaseAndKeepTheFinalOutcome() throws Exception {
        List<String> order = new ArrayList<>();
        List<AmRecentAlbums.Outcome> outcomes = new ArrayList<>();
        assertEquals("netease", ArtworkSources.resolve(false, () -> ArtworkSources.automaticCached(true,
                () -> { order.add("apple-cache"); return null; },
                () -> { order.add("netease-cache"); return null; },
                () -> { order.add("apple-network"); throw new AmFailure(Status.NO_MOTION, "confirmed_album_no_motion"); },
                () -> { assertTrue(outcomes.isEmpty()); order.add("netease-network"); return "netease"; }), outcomes::add));
        assertEquals(List.of("apple-cache", "netease-cache", "apple-network", "netease-network"), order);
        assertEquals(List.of(AmRecentAlbums.Outcome.MATCHED), outcomes);
    }
    @Test public void disabledFallbackCannotUseItsCachedFile() throws Exception {
        assertEquals("apple", ArtworkSources.automaticCached(false,
                () -> { fail("single-source resolver handles its own cache"); return null; },
                () -> { fail("disabled NetEase cache must not be read"); return "netease-file"; },
                () -> "apple", () -> { fail("disabled source"); return "netease"; }));
    }
    @Test public void cancellationWhileCheckingCachesCannotStartNetworkWork() throws Exception {
        AmFailure cancelled = new AmFailure(Status.ERROR, "cancelled");
        try {
            ArtworkSources.automaticCached(true, () -> null, () -> { throw cancelled; },
                    () -> { fail("cancelled lookup"); return "apple"; },
                    () -> { fail("cancelled fallback"); return "netease"; });
            fail();
        } catch (AmFailure expected) { assertSame(cancelled, expected); }
    }
    @Test public void appleMissDoesNotPublishAnIntermediateFailureBeforeNeteaseSuccess() throws Exception {
        for (Status status : List.of(Status.RETRY_LATER, Status.NO_MOTION, Status.AMBIGUOUS)) {
            List<AmRecentAlbums.Outcome> outcomes = new ArrayList<>();
            assertEquals("NetEase", ArtworkSources.resolve(false, () -> ArtworkSources.automatic(true,
                    () -> { throw new AmFailure(status, "catalog_match_unconfirmed"); },
                    () -> { assertTrue(outcomes.isEmpty()); return "NetEase"; }), outcomes::add));
            assertEquals(List.of(AmRecentAlbums.Outcome.MATCHED), outcomes);
        }
    }
    @Test public void finalNetworkFailureIsReportedOnceAndCancellationDoesNotEraseHistory() throws Exception {
        for (String reason : List.of("network_io", "cancelled")) {
            List<AmRecentAlbums.Outcome> outcomes = new ArrayList<>();
            AmFailure failure = new AmFailure(reason.equals("cancelled") ? Status.ERROR : Status.RETRY_LATER, reason);
            try {
                ArtworkSources.resolve(false, () -> ArtworkSources.automatic(true,
                        () -> { throw new AmFailure(Status.NO_MOTION, "confirmed_album_no_motion"); },
                        () -> { assertTrue(outcomes.isEmpty()); throw failure; }), outcomes::add);
                fail();
            } catch (AmFailure expected) { assertSame(failure, expected); }
            assertEquals(reason.equals("cancelled") ? List.of() : List.of(AmRecentAlbums.Outcome.FAILED), outcomes);
        }
    }
    @Test public void manualBindingIsMarkedBoundOnlyAfterItSucceeds() throws Exception {
        List<AmRecentAlbums.Outcome> outcomes = new ArrayList<>();
        assertEquals("bound", ArtworkSources.resolve(true, () -> {
            assertTrue(outcomes.isEmpty()); return "bound";
        }, outcomes::add));
        assertEquals(List.of(AmRecentAlbums.Outcome.BOUND), outcomes);
    }
    @Test public void appleSuccessNeverCallsNeteaseEvenWhenItIsEnabled() throws Exception {
        assertEquals("AM", ArtworkSources.automatic(true, () -> "AM", () -> { fail("NetEase must not be called"); return "NetEase"; }));
    }
    @Test public void enabledFallbackRunsOnlyAfterAppleFails() throws Exception {
        List<String> order = new ArrayList<>();
        assertEquals("NetEase", ArtworkSources.automatic(true, () -> {
            order.add("AM"); throw new AmFailure(Status.NO_MOTION, "confirmed_album_no_motion");
        }, () -> { order.add("NetEase"); return "NetEase"; }));
        assertEquals(List.of("AM", "NetEase"), order);
    }
    @Test public void disabledSourceAndCancelledRequestsNeverReachFallback() throws Exception {
        for (String reason : List.of("cancelled", "provider_disabled", "confirmed_album_no_motion")) {
            boolean enabled = !reason.equals("confirmed_album_no_motion");
            AmFailure original = new AmFailure(Status.ERROR, reason);
            try { ArtworkSources.automatic(enabled, () -> { throw original; }, () -> { fail("unauthorized fallback"); return "bad"; }); fail(); }
            catch (AmFailure expected) { assertSame(original, expected); }
        }
    }
}
