package io.github.andrealtb.artwork.am;

/** Automatic priority only. An explicit user binding is dispatched before this policy. */
final class ArtworkSources {
    interface Resolve<T> { T run() throws AmFailure; }
    interface Lookup<P> { P run(AmNetwork.Task task) throws AmFailure; }
    interface Download<P, T> { T run(P prepared, AmNetwork.Task task) throws AmFailure; }
    /** At most one speculative metadata lookup per resolve lane; videos never run on this pool. */
    private static final java.util.concurrent.ThreadPoolExecutor LOOKUPS = new java.util.concurrent.ThreadPoolExecutor(
            0, 2, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>(), work -> {
                Thread thread = new Thread(work, "artwork-source-lookup"); thread.setDaemon(true); return thread;
            });
    /** Publish the final chain outcome only; an Apple miss is not the result of a NetEase fallback. */
    static <T> T resolve(boolean bound, Resolve<T> operation,
            java.util.function.Consumer<AmRecentAlbums.Outcome> completed) throws AmFailure {
        try {
            T result = operation.run();
            completed.accept(bound ? AmRecentAlbums.Outcome.BOUND : AmRecentAlbums.Outcome.MATCHED);
            return result;
        } catch (AmFailure failure) {
            AmRecentAlbums.Outcome outcome = AmRecentAlbums.outcome(failure);
            if (outcome != null) completed.accept(outcome);
            throw failure;
        }
    }
    static <T> T automatic(boolean netease, Resolve<T> apple, Resolve<T> fallback) throws AmFailure {
        try { return apple.run(); }
        catch (AmFailure failure) {
            if (!netease || failure.reason.equals("cancelled") || failure.reason.equals("provider_disabled")) throw failure;
            return fallback.run();
        }
    }
    /** A verified fallback file already has a resolved identity; do not rediscover AM on every wake. */
    static <T> T automaticCached(boolean netease, Resolve<T> appleCache, Resolve<T> fallbackCache,
            Resolve<T> apple, Resolve<T> fallback) throws AmFailure {
        return netease ? cachedFirst(appleCache, fallbackCache, () -> automatic(true, apple, fallback)) : apple.run();
    }
    static <T> T cachedFirst(Resolve<T> appleCache, Resolve<T> fallbackCache, Resolve<T> cold) throws AmFailure {
        T cached = appleCache.run();
        if (cached != null) return cached;
        cached = fallbackCache.run();
        return cached == null ? cold.run() : cached;
    }
    /** Start fallback metadata immediately, but only the selected source may fetch video bytes. */
    static <P, T> T parallel(AmNetwork.Task task, Resolve<T> apple, Lookup<P> lookup, Download<P, T> download) throws AmFailure {
        return parallel(LOOKUPS, task, apple, lookup, download);
    }
    static <P, T> T parallel(java.util.concurrent.ExecutorService workers, AmNetwork.Task task,
            Resolve<T> apple, Lookup<P> lookup, Download<P, T> download) throws AmFailure {
        task.check();
        AmNetwork.Task child = task.fork();
        java.util.concurrent.Future<P> pending = null;
        try {
            try { pending = workers.submit(() -> { child.check(); return lookup.run(child); }); }
            catch (java.util.concurrent.RejectedExecutionException busy) { /* bounded pool: retain sequential fallback */ }
            java.util.concurrent.Future<P> discovery = pending;
            return automatic(true, apple, () -> {
                task.check(); child.check();
                P prepared = discovery == null ? lookup.run(child) : await(discovery, task);
                task.check(); child.check();
                return download.run(prepared, child);
            });
        } finally {
            child.release();
            if (pending != null) pending.cancel(true);
        }
    }
    private static <P> P await(java.util.concurrent.Future<P> pending, AmNetwork.Task task) throws AmFailure {
        while (true) {
            task.check();
            try { return pending.get(100, java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (java.util.concurrent.TimeoutException waiting) { /* bounded cancellation/deadline check */ }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AmFailure(io.github.andrealtb.artwork.contract.ArtworkResult.Status.ERROR, "cancelled");
            } catch (java.util.concurrent.CancellationException cancelled) {
                throw new AmFailure(io.github.andrealtb.artwork.contract.ArtworkResult.Status.ERROR, "cancelled");
            } catch (java.util.concurrent.ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof AmFailure failure) throw failure;
                if (cause instanceof RuntimeException failure) throw failure;
                if (cause instanceof Error failure) throw failure;
                throw new AmFailure(io.github.andrealtb.artwork.contract.ArtworkResult.Status.ERROR, "netease_lookup_failed");
            }
        }
    }
    private ArtworkSources() {}
}
