package io.github.andrealtb.artwork.am;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

public class AmSharedDownloadTest {
    @Test public void songSpecificFailureDoesNotRejectAnotherSongInTheSameAlbum() throws Exception {
        for (String reason : java.util.List.of("netease_album_no_motion", "resource_limits", "upstream_rate_limit")) {
            var gate = new AmSharedDownload(); var executor = Executors.newFixedThreadPool(2);
            var started = new CountDownLatch(1); var release = new CountDownLatch(1); var joined = new CountDownLatch(1);
            try {
                var owner = executor.submit(() -> {
                    try { return gate.run("album", "song-1", new AmNetwork.Task(), () -> {}, () -> {
                        started.countDown(); await(release);
                        throw new AmFailure(reason.equals("netease_album_no_motion") ? Status.NO_MOTION
                                : reason.equals("resource_limits") ? Status.UNSUPPORTED : Status.RETRY_LATER, reason);
                    }); }
                    catch (AmFailure failure) { return failure.reason; }
                });
                await(started);
                var waiter = executor.submit(() -> {
                    try { return gate.run("album", "song-2", new AmNetwork.Task(), joined::countDown, () -> "ready"); }
                    catch (AmFailure failure) { return failure.reason; }
                });
                await(joined); release.countDown();
                assertEquals(reason, owner.get(3, TimeUnit.SECONDS));
                assertEquals(reason.equals("upstream_rate_limit") ? reason : "ready", waiter.get(3, TimeUnit.SECONDS));
            } finally { release.countDown(); executor.shutdownNow(); }
        }
    }
    @Test public void recoveringWaiterRetriesSharedTransportFailureButStillHonorsRateLimits() throws Exception {
        for (String reason : java.util.List.of("network_io", "upstream_rate_limit")) {
            var gate = new AmSharedDownload(); var executor = Executors.newFixedThreadPool(2);
            var started = new CountDownLatch(1); var release = new CountDownLatch(1); var joined = new CountDownLatch(1);
            var attempts = new AtomicInteger(); var recovery = new AmNetwork.Task(); recovery.recoveringTransport(true);
            try {
                var owner = executor.submit(() -> {
                    try {
                        return gate.run("album", new AmNetwork.Task(), () -> {}, () -> {
                            attempts.incrementAndGet(); started.countDown(); await(release);
                            throw new AmFailure(Status.RETRY_LATER, reason, 30_000);
                        });
                    } catch (AmFailure failure) { return failure.reason; }
                });
                await(started);
                var waiter = executor.submit(() -> {
                    try { return gate.run("album", recovery, joined::countDown, () -> { attempts.incrementAndGet(); return "ready"; }); }
                    catch (AmFailure failure) { return failure.reason; }
                });
                await(joined); release.countDown();
                assertEquals(reason, owner.get(3, TimeUnit.SECONDS));
                assertEquals(reason.equals("network_io") ? "ready" : reason, waiter.get(3, TimeUnit.SECONDS));
                assertEquals(reason.equals("network_io") ? 2 : 1, attempts.get());
            } finally { release.countDown(); executor.shutdownNow(); }
        }
    }
    @Test public void duplicateSourceQueriesJoinLookupBeforeReopeningCachedVideo() throws Exception {
        var gate = new AmSharedDownload();
        var executor = Executors.newFixedThreadPool(2);
        var started = new CountDownLatch(1); var release = new CountDownLatch(1); var joined = new CountDownLatch(1);
        var cached = new AtomicReference<String>();
        var appleLookups = new AtomicInteger(); var neteaseDownloads = new AtomicInteger(); var pins = new AtomicInteger();
        AmSharedDownload.Work<String> work = () -> ArtworkSources.automaticCached(true, () -> null, () -> {
            String file = cached.get();
            return file == null ? null : file + ":lease-" + pins.incrementAndGet();
        }, () -> {
            appleLookups.incrementAndGet(); started.countDown(); await(release);
            throw new AmFailure(Status.NO_MOTION, "confirmed_album_no_motion");
        }, () -> {
            neteaseDownloads.incrementAndGet(); cached.set("netease.mp4");
            return cached.get() + ":lease-" + pins.incrementAndGet();
        });
        String small = AmCache.key(AmIdentityTest.query("Style", "1989", 288), "cn") + ":1";
        String large = AmCache.key(AmIdentityTest.query("Style", "1989", 1080), "cn") + ":1";
        assertEquals("display size must not duplicate identity/network work", small, large);
        try {
            var owner = executor.submit(() -> gate.run(small, new AmNetwork.Task(), () -> {}, work));
            await(started);
            var waiter = executor.submit(() -> gate.run(large, new AmNetwork.Task(), joined::countDown, work));
            await(joined);
            assertEquals(1, appleLookups.get()); assertEquals(0, neteaseDownloads.get());
            release.countDown();
            assertEquals("netease.mp4:lease-1", owner.get(3, TimeUnit.SECONDS));
            assertEquals("netease.mp4:lease-2", waiter.get(3, TimeUnit.SECONDS));
            assertEquals(1, appleLookups.get()); assertEquals(1, neteaseDownloads.get()); assertEquals(2, pins.get());
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    @Test public void switchingSurfaceJoinsDownloadThenReopensCachedFile() throws Exception {
        var gate = new AmSharedDownload();
        var executor = Executors.newFixedThreadPool(2);
        var started = new CountDownLatch(1); var release = new CountDownLatch(1); var joined = new CountDownLatch(1);
        var cached = new AtomicReference<String>(); var downloads = new AtomicInteger(); var pins = new AtomicInteger();
        AmSharedDownload.Work<String> work = () -> {
            if (cached.get() == null) {
                downloads.incrementAndGet(); started.countDown(); await(release); cached.set("shared-1080.mp4");
            }
            pins.incrementAndGet(); return cached.get();
        };
        var small = AmCache.albumAssetKey(AmIdentityTest.query("Style", "1989", 288), "cn", "album");
        var large = AmCache.albumAssetKey(AmIdentityTest.query("Blank Space", "1989", 1080), "cn", "album");
        try {
            var first = executor.submit(() -> gate.run(small, "song-1", new AmNetwork.Task(), () -> {}, work));
            await(started);
            var second = executor.submit(() -> gate.run(large, "song-2", new AmNetwork.Task(), joined::countDown, work));
            await(joined); release.countDown();
            assertEquals(first.get(3, TimeUnit.SECONDS), second.get(3, TimeUnit.SECONDS));
            assertEquals(1, downloads.get()); assertEquals(2, pins.get());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test public void waiterCancellationDoesNotCancelOwnerOrBlockAnotherAlbum() throws Exception {
        var gate = new AmSharedDownload(); var executor = Executors.newFixedThreadPool(2);
        var started = new CountDownLatch(1); var release = new CountDownLatch(1); var joined = new CountDownLatch(1);
        var waiterTask = new AmNetwork.Task();
        try {
            var owner = executor.submit(() -> gate.run("album", new AmNetwork.Task(), () -> {}, () -> {
                started.countDown(); await(release); return "ready";
            }));
            await(started);
            var waiter = executor.submit(() -> {
                try { return gate.run("album", waiterTask, joined::countDown, () -> "should_not_run"); }
                catch (AmFailure error) { return error.reason; }
            });
            await(joined); waiterTask.cancel();
            assertEquals("cancelled", waiter.get(3, TimeUnit.SECONDS));
            assertEquals("other", gate.run("another-album", new AmNetwork.Task(), () -> {}, () -> "other"));
            release.countDown(); assertEquals("ready", owner.get(3, TimeUnit.SECONDS));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test public void concurrentFailureIsSharedButNotPermanentlyRememberedByGate() throws Exception {
        var gate = new AmSharedDownload(); var executor = Executors.newFixedThreadPool(2);
        var started = new CountDownLatch(1); var release = new CountDownLatch(1); var joined = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        AmSharedDownload.Work<String> work = () -> {
            attempts.incrementAndGet(); started.countDown(); await(release);
            throw new AmFailure(Status.UNSUPPORTED, "media_validation_failed", 0, "stage=extractor_set_data_source");
        };
        try {
            var owner = executor.submit(() -> failure(gate, () -> {}, work));
            await(started);
            var waiter = executor.submit(() -> failure(gate, joined::countDown, work));
            await(joined); release.countDown();
            assertEquals("stage=extractor_set_data_source", owner.get(3, TimeUnit.SECONDS));
            assertEquals("stage=extractor_set_data_source", waiter.get(3, TimeUnit.SECONDS));
            assertEquals(1, attempts.get());
            assertEquals("retry", gate.run("album", new AmNetwork.Task(), () -> {}, () -> "retry"));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    private static String failure(AmSharedDownload gate, Runnable waiting, AmSharedDownload.Work<String> work) throws Exception {
        try { return gate.run("album", new AmNetwork.Task(), waiting, work); }
        catch (AmFailure error) { return error.detail; }
    }
}
