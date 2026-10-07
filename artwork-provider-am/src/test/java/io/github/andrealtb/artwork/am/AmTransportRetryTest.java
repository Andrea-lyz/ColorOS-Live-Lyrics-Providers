package io.github.andrealtb.artwork.am;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

public class AmTransportRetryTest {
    @Test public void transientFailuresRecoverInsideOneRequestAndPublishOnlyTheFinalSuccess() throws Exception {
        var task = new AmNetwork.Task(52_000); var attempts = new AtomicInteger();
        List<Long> delays = new ArrayList<>(); List<Boolean> recovery = new ArrayList<>();
        List<AmRecentAlbums.Outcome> outcomes = new ArrayList<>();
        long remaining = task.remainingMs();
        assertEquals("ready", ArtworkSources.resolve(false, () -> AmTransportRetry.run(task, () -> {
            assertTrue(outcomes.isEmpty()); recovery.add(task.recoveringTransport());
            if (attempts.incrementAndGet() < 3) throw new AmFailure(Status.RETRY_LATER, "network_io", 30_000);
            return "ready";
        }, (retry, delay, reason) -> {
            assertEquals("network_io", reason); assertEquals(delays.size() + 1, retry); delays.add(delay);
        }, (current, delay) -> current.check()), outcomes::add));
        assertEquals(List.of(1_000L, 3_000L), delays);
        assertEquals(List.of(false, true, true), recovery);
        assertEquals(List.of(AmRecentAlbums.Outcome.MATCHED), outcomes);
        assertFalse(task.recoveringTransport()); assertTrue(task.remainingMs() <= remaining);
    }

    @Test public void persistentNetworkFailureStopsAfterTwoRecoveryAttempts() throws Exception {
        var task = new AmNetwork.Task(52_000); var attempts = new AtomicInteger();
        var failure = new AmFailure(Status.RETRY_LATER, "network_io", 30_000);
        List<AmRecentAlbums.Outcome> outcomes = new ArrayList<>();
        try {
            ArtworkSources.resolve(false, () -> AmTransportRetry.run(task, () -> {
                attempts.incrementAndGet(); throw failure;
            }, (retry, delay, reason) -> {}, (current, delay) -> {}), outcomes::add);
            fail();
        } catch (AmFailure expected) { assertSame(failure, expected); }
        assertEquals(3, attempts.get()); assertFalse(task.recoveringTransport());
        assertEquals(List.of(AmRecentAlbums.Outcome.FAILED), outcomes);
    }

    @Test public void rateLimitsLoginPolicyIdentityAndNoMotionAreNotRetried() throws Exception {
        List<AmFailure> failures = List.of(
                new AmFailure(Status.RETRY_LATER, "upstream_rate_limit", 300_000),
                new AmFailure(Status.RETRY_LATER, "upstream_access_denied", 60_000),
                new AmFailure(Status.RETRY_LATER, "catalog_match_unconfirmed", 60_000),
                new AmFailure(Status.RETRY_LATER, "network_deadline", 30_000),
                new AmFailure(Status.NETWORK_BLOCKED, "netease_login_required"),
                new AmFailure(Status.NETWORK_BLOCKED, "network_policy"),
                new AmFailure(Status.NO_MOTION, "netease_album_no_motion"),
                new AmFailure(Status.AMBIGUOUS, "multiple_catalog_matches"),
                new AmFailure(Status.UNSUPPORTED, "resource_limits"),
                new AmFailure(Status.ERROR, "cancelled"));
        for (AmFailure failure : failures) {
            var attempts = new AtomicInteger();
            try {
                AmTransportRetry.run(new AmNetwork.Task(52_000), () -> { attempts.incrementAndGet(); throw failure; },
                        (retry, delay, reason) -> fail("unexpected retry: " + reason), (current, delay) -> fail());
                fail();
            } catch (AmFailure expected) { assertSame(failure, expected); }
            assertEquals(failure.reason, 1, attempts.get());
        }
        for (String reason : List.of("network_io", "network_connect_timeout", "network_headers_timeout", "network_read_timeout", "network_stage_timeout"))
            assertTrue(reason, AmTransportRetry.retryable(Status.RETRY_LATER, reason));
    }

    @Test public void insufficientRequestBudgetDoesNotResetTheDeadlineOrStartAnotherRound() throws Exception {
        var task = new AmNetwork.Task(1_500); var attempts = new AtomicInteger();
        var failure = new AmFailure(Status.RETRY_LATER, "network_io", 30_000);
        try {
            AmTransportRetry.run(task, () -> { attempts.incrementAndGet(); throw failure; },
                    (retry, delay, reason) -> fail("no budget for a recovery round"), (current, delay) -> fail());
            fail();
        } catch (AmFailure expected) { assertSame(failure, expected); }
        assertEquals(1, attempts.get()); assertTrue(task.remainingMs() <= 1_500);
    }

    @Test public void cancellationDuringRecoveryWaitStopsBeforeAnotherNetworkAttempt() throws Exception {
        var task = new AmNetwork.Task(52_000); var executor = Executors.newSingleThreadExecutor();
        var waiting = new CountDownLatch(1); var attempts = new AtomicInteger();
        try {
            var result = executor.submit(() -> {
                try {
                    return AmTransportRetry.run(task, () -> {
                        attempts.incrementAndGet(); throw new AmFailure(Status.RETRY_LATER, "network_io", 30_000);
                    }, (retry, delay, reason) -> waiting.countDown());
                } catch (AmFailure failure) { return failure.reason; }
            });
            assertTrue(waiting.await(3, TimeUnit.SECONDS)); task.cancel();
            assertEquals("cancelled", result.get(3, TimeUnit.SECONDS));
            assertEquals(1, attempts.get()); assertFalse(task.recoveringTransport());
        } finally { executor.shutdownNow(); }
    }

    @Test public void recoveryContextReachesParallelSourceAndAuthFailureStopsRequestRecovery() throws Exception {
        var task = new AmNetwork.Task(52_000); var attempts = new AtomicInteger();
        try {
            AmTransportRetry.run(task, () -> {
                int attempt = attempts.incrementAndGet(); var child = task.fork();
                try {
                    assertEquals(attempt > 1, child.recoveringTransport());
                    if (attempt > 1) child.blockTransportRecovery();
                    throw new AmFailure(Status.RETRY_LATER, "network_io", 30_000);
                } finally { child.release(); }
            }, (retry, delay, reason) -> {}, (current, delay) -> {});
            fail();
        } catch (AmFailure expected) { assertEquals("network_io", expected.reason); }
        assertEquals(2, attempts.get()); assertFalse(task.transportRecoveryAllowed());
        assertFalse(task.recoveringTransport());
    }
}
