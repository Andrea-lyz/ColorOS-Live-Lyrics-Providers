package io.github.andrealtb.artwork.am;

import java.util.concurrent.TimeUnit;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

/** Recover a transient resolve failure inside the original request, without resetting its deadline. */
final class AmTransportRetry {
    private static final long[] DELAYS_MS = {1_000, 3_000};
    interface Waiting { void accept(int retry, long delayMs, String reason); }
    interface Pause { void run(AmNetwork.Task task, long delayMs) throws AmFailure; }

    static boolean retryable(Status status, String reason) {
        return status == Status.RETRY_LATER && AmConnectivity.transport(reason) && !reason.equals("network_deadline");
    }
    static <T> T run(AmNetwork.Task task, ArtworkSources.Resolve<T> work, Waiting waiting) throws AmFailure {
        return run(task, work, waiting, AmTransportRetry::pause);
    }
    static <T> T run(AmNetwork.Task task, ArtworkSources.Resolve<T> work, Waiting waiting, Pause pause) throws AmFailure {
        boolean previous = task.recoveringTransport();
        try {
            for (int attempt = 0; ; attempt++) {
                task.check();
                task.recoveringTransport(previous || attempt > 0);
                try { return work.run(); }
                catch (AmFailure failure) {
                    if (!retryable(failure.status, failure.reason) || !task.transportRecoveryAllowed()
                            || attempt >= DELAYS_MS.length || task.remainingMs() <= DELAYS_MS[attempt] + 1_000) throw failure;
                    task.check();
                    waiting.accept(attempt + 1, DELAYS_MS[attempt], failure.reason);
                    pause.run(task, DELAYS_MS[attempt]);
                }
            }
        } finally { task.recoveringTransport(previous); }
    }
    private static void pause(AmNetwork.Task task, long delayMs) throws AmFailure {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs);
        while (true) {
            task.check();
            long remaining = until - System.nanoTime();
            if (remaining <= 0) return;
            try { TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100))); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AmFailure(Status.ERROR, "cancelled");
            }
        }
    }
    private AmTransportRetry() {}
}
