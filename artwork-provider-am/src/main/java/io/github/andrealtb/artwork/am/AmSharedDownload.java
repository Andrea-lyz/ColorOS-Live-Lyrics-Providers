package io.github.andrealtb.artwork.am;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

/** Per confirmed album gate. Each caller reopens/pins the completed cache file with its own lease. */
final class AmSharedDownload {
    interface Work<T> { T run() throws AmFailure; }
    private static final class Entry {
        final ReentrantLock lock = new ReentrantLock();
        int users;
        AmFailure failure;
    }
    private final Map<String, Entry> entries = new HashMap<>();

    <T> T run(String key, AmNetwork.Task task, Runnable waiting, Work<T> work) throws AmFailure {
        Entry entry;
        synchronized (entries) {
            entry = entries.computeIfAbsent(key, ignored -> new Entry());
            entry.users++;
        }
        boolean locked = false;
        try {
            task.check();
            locked = entry.lock.tryLock();
            if (!locked) {
                waiting.run();
                do { task.check(); locked = entry.lock.tryLock(100, TimeUnit.MILLISECONDS); } while (!locked);
            }
            task.check();
            if (entry.failure != null) {
                AmFailure failure = entry.failure;
                throw new AmFailure(failure.status, failure.reason, failure.retryMs, failure.detail);
            }
            try { return work.run(); }
            catch (AmFailure failure) {
                // A cancelled/expired owner does not prevent a live waiter from doing its own work.
                if (failure.status != Status.ERROR && !failure.reason.equals("network_deadline")) entry.failure = failure;
                throw failure;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AmFailure(Status.ERROR, "cancelled");
        } finally {
            if (locked) entry.lock.unlock();
            synchronized (entries) { if (--entry.users == 0) entries.remove(key, entry); }
        }
    }
}
