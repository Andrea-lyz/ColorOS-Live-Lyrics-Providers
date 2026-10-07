package io.github.andrealtb.artwork.am;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

public class ArtworkParallelLookupTest {
    private static void await(CountDownLatch signal) {
        try { assertTrue("worker did not reach checkpoint", signal.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    @Test public void fasterNeteaseLookupCannotDownloadOrBeatAnAvailableAppleCover() throws Exception {
        var workers = Executors.newSingleThreadExecutor(); var caller = Executors.newSingleThreadExecutor();
        var appleStarted = new CountDownLatch(1); var neteaseReady = new CountDownLatch(1); var releaseApple = new CountDownLatch(1);
        var downloads = new AtomicInteger();
        try {
            var result = caller.submit(() -> ArtworkSources.parallel(workers, new AmNetwork.Task(52_000), () -> {
                appleStarted.countDown(); await(releaseApple); return "apple-video";
            }, child -> { neteaseReady.countDown(); return "netease-url"; }, (prepared, child) -> {
                downloads.incrementAndGet(); return "netease-video";
            }));
            await(appleStarted); await(neteaseReady);
            assertFalse("NetEase must wait for Apple's decision", result.isDone());
            assertEquals(0, downloads.get());
            releaseApple.countDown();
            assertEquals("apple-video", result.get(3, TimeUnit.SECONDS));
            assertEquals(0, downloads.get());
        } finally { releaseApple.countDown(); caller.shutdownNow(); workers.shutdownNow(); }
    }

    @Test public void appleMissUsesTheAlreadyPreparedNeteaseResourceExactlyOnce() throws Exception {
        var workers = Executors.newSingleThreadExecutor(); var caller = Executors.newSingleThreadExecutor();
        var ready = new CountDownLatch(1); var releaseApple = new CountDownLatch(1);
        var lookups = new AtomicInteger(); var downloads = new AtomicInteger();
        List<AmRecentAlbums.Outcome> outcomes = new ArrayList<>();
        try {
            var result = caller.submit(() -> ArtworkSources.resolve(false, () -> ArtworkSources.parallel(workers,
                    new AmNetwork.Task(52_000), () -> {
                        await(ready); await(releaseApple); throw new AmFailure(Status.NO_MOTION, "confirmed_album_no_motion");
                    }, child -> { lookups.incrementAndGet(); ready.countDown(); return "prepared-url"; }, (prepared, child) -> {
                        child.check(); assertEquals("prepared-url", prepared); downloads.incrementAndGet(); return "netease-video";
                    }), outcomes::add));
            await(ready);
            assertEquals(1, lookups.get()); assertEquals(0, downloads.get());
            releaseApple.countDown();
            assertEquals("netease-video", result.get(3, TimeUnit.SECONDS));
            assertEquals(1, lookups.get()); assertEquals(1, downloads.get());
            assertEquals(List.of(AmRecentAlbums.Outcome.MATCHED), outcomes);
        } finally { releaseApple.countDown(); caller.shutdownNow(); workers.shutdownNow(); }
    }

    @Test public void appleSuccessDoesNotWaitForSlowNeteaseAndCancelsItsExchangeTask() throws Exception {
        var workers = Executors.newSingleThreadExecutor(); var caller = Executors.newSingleThreadExecutor();
        var started = new CountDownLatch(1); var cancelled = new CountDownLatch(1);
        var childTask = new AtomicReference<AmNetwork.Task>();
        try {
            var result = caller.submit(() -> ArtworkSources.parallel(workers, new AmNetwork.Task(52_000), () -> {
                await(started); return "apple-video";
            }, child -> {
                childTask.set(child); started.countDown();
                try { new CountDownLatch(1).await(10, TimeUnit.SECONDS); fail("unused lookup was not interrupted"); }
                catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
                finally { cancelled.countDown(); }
                throw new AmFailure(Status.ERROR, "cancelled");
            }, (prepared, child) -> { fail("unused source must not download"); return "netease"; }));
            assertEquals("apple-video", result.get(3, TimeUnit.SECONDS));
            await(cancelled); assertTrue(childTask.get().cancelled.get());
        } finally { caller.shutdownNow(); workers.shutdownNow(); }
    }

    @Test public void neteaseWithoutResourceDoesNotAffectAppleDownload() throws Exception {
        var workers = Executors.newSingleThreadExecutor(); var failed = new CountDownLatch(1);
        try {
            assertEquals("apple-video", ArtworkSources.parallel(workers, new AmNetwork.Task(52_000), () -> {
                await(failed); return "apple-video";
            }, child -> { failed.countDown(); throw new AmFailure(Status.NO_MOTION, "netease_album_no_motion"); },
                    (prepared, child) -> { fail("empty NetEase result"); return "netease"; }));
        } finally { workers.shutdownNow(); }
    }

    @Test public void bothUnavailablePublishOneFinalFailureAndNeverDownloadNetease() throws Exception {
        var workers = Executors.newSingleThreadExecutor();
        var failure = new AmFailure(Status.NO_MOTION, "netease_album_no_motion");
        List<AmRecentAlbums.Outcome> outcomes = new ArrayList<>();
        try {
            ArtworkSources.resolve(false, () -> ArtworkSources.parallel(workers, new AmNetwork.Task(52_000),
                    () -> { throw new AmFailure(Status.NO_MOTION, "confirmed_album_no_motion"); },
                    child -> { throw failure; }, (prepared, child) -> { fail("no video available"); return "video"; }), outcomes::add);
            fail();
        } catch (AmFailure expected) {
            assertSame(failure, expected); assertEquals(List.of(AmRecentAlbums.Outcome.NO_MOTION), outcomes);
        } finally { workers.shutdownNow(); }
    }

    @Test public void saturatedLookupPoolKeepsSequentialFallbackWithoutDroppingTheRequest() throws Exception {
        var workers = Executors.newSingleThreadExecutor(); workers.shutdownNow();
        List<String> order = new ArrayList<>();
        assertEquals("netease-video", ArtworkSources.parallel(workers, new AmNetwork.Task(52_000), () -> {
            order.add("apple"); throw new AmFailure(Status.NO_MOTION, "confirmed_album_no_motion");
        }, child -> { order.add("lookup"); return "prepared"; }, (prepared, child) -> {
            order.add("download"); return "netease-video";
        }));
        assertEquals(List.of("apple", "lookup", "download"), order);
    }

    @Test public void sourceTasksKeepIndependentDeadlinesAndCancellationOwnership() throws Exception {
        var parent = new AmNetwork.Task(52_000); var child = parent.fork();
        long original = parent.limit(0);
        try { parent.check(); fail(); }
        catch (AmFailure expected) { assertEquals("network_deadline", expected.reason); }
        child.check(); // AM's temporary 38-second cap must not shorten the NetEase task.
        parent.restore(original); child.release(); parent.check();
        assertTrue(child.cancelled.get()); assertFalse(parent.cancelled.get());
        var other = parent.fork(); parent.cancel();
        assertTrue(other.cancelled.get());
        try { other.check(); fail(); }
        catch (AmFailure expected) { assertEquals("cancelled", expected.reason); }
        finally { other.release(); }
        var late = parent.fork(); assertTrue(late.cancelled.get()); late.release();
    }

    @Test public void cancellingParentAfterLookupCannotTriggerFallbackDownload() throws Exception {
        var workers = Executors.newSingleThreadExecutor(); var parent = new AmNetwork.Task(52_000);
        var ready = new CountDownLatch(1); var childTask = new AtomicReference<AmNetwork.Task>();
        try {
            ArtworkSources.parallel(workers, parent, () -> {
                await(ready); parent.cancel(); parent.check(); return "apple";
            }, child -> { childTask.set(child); ready.countDown(); return "prepared"; },
                    (prepared, child) -> { fail("cancelled request cannot download"); return "netease"; });
            fail();
        } catch (AmFailure expected) {
            assertEquals("cancelled", expected.reason); assertTrue(childTask.get().cancelled.get());
        } finally { workers.shutdownNow(); }
    }
}
