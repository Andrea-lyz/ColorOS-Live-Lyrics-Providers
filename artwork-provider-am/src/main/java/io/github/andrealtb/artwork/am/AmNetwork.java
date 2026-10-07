package io.github.andrealtb.artwork.am;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.ToLongFunction;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

final class AmNetwork {
    /**
     * Each stage runs here while the resolver waits with its own deadline. disconnect() cannot end
     * every blocking phase (name lookup above all), so a stalled exchange is abandoned, not awaited.
     * Bounded so threads stuck in such a phase cannot accumulate.
     */
    private static final ThreadPoolExecutor EXCHANGES = daemonPool(6, "artwork-am-http");
    /**
     * disconnect() itself can block behind a stalled exchange (device logs show the waiting side
     * never returning from it), so it never runs on the resolver, a binder thread or the reaper.
     */
    private static final ThreadPoolExecutor DISCONNECTS = daemonPool(2, "artwork-am-abandon");
    private static ThreadPoolExecutor daemonPool(int threads, String name) {
        return new ThreadPoolExecutor(0, threads, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
    }
    interface Connections {
        HttpURLConnection open(URI uri) throws IOException;
        /** A separate lookup names the phase a stall happened in; the connection then reuses the answer. */
        default void resolve(String host) throws IOException {}
    }
    static final class Task {
        final AtomicBoolean cancelled = new AtomicBoolean();
        private final Task parent;
        private final Set<Task> children = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final AtomicBoolean transportRecoveryAllowed;
        private volatile boolean recoveringTransport;
        private volatile long deadline;
        Task() { this(38_000); }
        Task(long budgetMs) {
            parent = null; transportRecoveryAllowed = new AtomicBoolean(true);
            deadline = System.nanoTime() + Math.min(52_000, budgetMs) * 1_000_000L;
        }
        private Task(Task parent) {
            this.parent = parent; deadline = parent.deadline;
            recoveringTransport = parent.recoveringTransport;
            transportRecoveryAllowed = parent.transportRecoveryAllowed;
        }
        boolean recoveringTransport() { return recoveringTransport; }
        void recoveringTransport(boolean recovering) { recoveringTransport = recovering; }
        boolean transportRecoveryAllowed() { return transportRecoveryAllowed.get(); }
        void blockTransportRecovery() { transportRecoveryAllowed.set(false); }
        long remainingMs() { return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())); }
        /** Concurrent sources own separate exchanges/deadlines but share request cancellation. */
        Task fork() {
            Task child = new Task(this);
            children.add(child);
            if (cancelled.get()) child.cancel();
            return child;
        }
        void release() {
            cancel();
            if (parent != null) parent.children.remove(this);
        }
        long limit(long budgetMs) {
            long previous = deadline;
            deadline = Math.min(deadline, System.nanoTime() + budgetMs * 1_000_000L);
            return previous;
        }
        void restore(long previous) { deadline = previous; }
        private final AtomicReference<Exchange> exchange = new AtomicReference<>();
        void check() throws AmFailure {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new AmFailure(Status.ERROR, "cancelled");
            if (System.nanoTime() >= deadline) throw new AmFailure(Status.RETRY_LATER, "network_deadline", 30_000);
        }
        void cancel() {
            cancelled.set(true);
            for (Task child : children) child.cancel();
            Exchange active = exchange.get(); if (active != null) active.abandon();
        }
    }
    private final BooleanSupplier networkAllowed;
    private final java.util.function.Consumer<String> trace;
    private final Connections connections;
    private final ToLongFunction<String> budgets;
    private final java.util.function.Consumer<URI> validateUri;
    record Response(String text, java.util.List<String> cookies) {}
    private record Request(byte[] form, String cookie) {}
    AmNetwork(BooleanSupplier networkAllowed) { this(networkAllowed, reason -> {}); }
    AmNetwork(BooleanSupplier networkAllowed, java.util.function.Consumer<String> trace) {
        this(networkAllowed, trace, new Connections() {
            @Override public HttpURLConnection open(URI uri) throws IOException { return (HttpURLConnection) uri.toURL().openConnection(); }
            @Override public void resolve(String host) throws IOException { java.net.InetAddress.getAllByName(host); }
        }, AmNetwork::stageBudgetMs);
    }
    AmNetwork(BooleanSupplier networkAllowed, java.util.function.Consumer<String> trace, Connections connections,
            ToLongFunction<String> budgets) {
        this(networkAllowed, trace, connections, budgets, AmNetwork::validate);
    }
    AmNetwork(BooleanSupplier networkAllowed, java.util.function.Consumer<String> trace,
            java.util.function.Consumer<URI> validateUri) {
        this(networkAllowed, trace, new Connections() {
            @Override public HttpURLConnection open(URI uri) throws IOException { return (HttpURLConnection) uri.toURL().openConnection(); }
            @Override public void resolve(String host) throws IOException { java.net.InetAddress.getAllByName(host); }
        }, AmNetwork::stageBudgetMs, validateUri);
    }
    AmNetwork(BooleanSupplier networkAllowed, java.util.function.Consumer<String> trace, Connections connections,
            ToLongFunction<String> budgets, java.util.function.Consumer<URI> validateUri) {
        this.networkAllowed = networkAllowed; this.trace = trace; this.connections = connections;
        this.budgets = budgets; this.validateUri = validateUri;
    }
    static void validate(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getPort() != -1 || uri.getUserInfo() != null
                || uri.getFragment() != null || !Set.of("music.apple.com", "itunes.apple.com", "mvod.itunes.apple.com").contains(uri.getHost())) {
            throw new IllegalArgumentException("untrusted_network_uri");
        }
    }
    /** A single transient reset must not discard an already matched album and its playlist work. */
    private static final int MAX_ATTEMPTS = 3;

    /** Auth exchanges are never retried or redirected; returned cookies stay with this call. */
    Response response(URI uri, byte[] form, String cookie, Task task) throws AmFailure {
        return response(uri, form, cookie, task, false);
    }
    /** Read-only official APIs can recover from a reset; QR login/renewal keep their single-attempt semantics. */
    Response response(URI uri, byte[] form, String cookie, Task task, boolean retryable) throws AmFailure {
        TransportException last = null;
        int attempts = retryable ? MAX_ATTEMPTS : 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try {
                java.util.List<String> cookies = transfer(uri, 2 * 1024 * 1024, -1, bytes, task, new Request(form, cookie));
                return new Response(new String(bytes.toByteArray(), StandardCharsets.UTF_8), cookies);
            } catch (TransportException error) {
                last = error;
                if (attempt < attempts) backoff(task, error, attempt);
            }
        }
        throw failure(last);
    }

    /** A complete MP4 with an unknown size until the response headers, still capped by the contract. */
    void file(URI uri, File target, long limit, Task task) throws AmFailure {
        TransportException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            // A retry starts from byte zero, with a new connection and a truncated output file.
            try (java.io.FileOutputStream stream = new java.io.FileOutputStream(target)) {
                transfer(uri, limit, -1, stream, task);
                stream.getFD().sync();
                return;
            } catch (TransportException error) { last = error; backoff(task, error, attempt); }
            catch (IOException error) { throw new AmFailure(Status.ERROR, "cache_write_failed"); }
        }
        throw failure(last);
    }

    String text(URI uri, int limit, Task task) throws AmFailure {
        TransportException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try {
                transfer(uri, limit, -1, bytes, task);
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            } catch (TransportException error) { last = error; backoff(task, error, attempt); }
        }
        throw failure(last);
    }
    void file(AmHls.FilePlan plan, File target, long limit, Task task) throws AmFailure {
        TransportException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            // A fresh stream per attempt: a partial body must never be appended to.
            try (java.io.FileOutputStream stream = new java.io.FileOutputStream(target)) {
                transfer(plan.uri(), limit, plan.bytes(), stream, task);
                stream.getFD().sync();
                return;
            } catch (TransportException error) {
                last = error;
                // The resolver can try another rendition. Repeating a stalled URL can consume the
                // entire request budget before any alternative gets a turn.
                break;
            }
            catch (IOException error) { throw failure(null); }
        }
        throw failure(last);
    }
    private void transfer(URI uri, long limit, long expected, OutputStream target, Task task) throws AmFailure, TransportException {
        transfer(uri, limit, expected, target, task, null);
    }
    private java.util.List<String> transfer(URI uri, long limit, long expected, OutputStream target, Task task, Request request)
            throws AmFailure, TransportException {
        String stage = expected > 0 || "dcover.music.126.net".equals(uri.getHost()) ? "video_file"
                : "music.apple.com".equals(uri.getHost()) ? "web_album"
                : "itunes.apple.com".equals(uri.getHost()) ? "catalog" : request != null ? "netease_api" : "playlist";
        trace.accept(stage + "_started");
        long budgetMs = budgets.applyAsLong(stage);
        Exchange exchange = new Exchange(budgetMs);
        task.exchange.set(exchange);
        try {
            Future<?> running;
            try { running = EXCHANGES.submit(() -> { exchange(uri, limit, expected, target, task, exchange, request); return null; }); }
            catch (RejectedExecutionException busy) {
                // Every worker is still stuck in an earlier stall: same outcome, without a new reason code.
                trace.accept(stage + "_workers_busy");
                throw transport(stage, "network_stage_timeout");
            }
            exchange.start(running);
            try {
                // A recovery near the end of a request must not spend a fresh full stage budget.
                running.get(Math.min(exchange.remainingMs(), Math.max(1, task.remainingMs() + 1)), TimeUnit.MILLISECONDS);
            } catch (TimeoutException stalled) {
                // Described before abandoning, while the worker still sits where it stalled.
                String where = exchange.describeStall(budgetMs);
                exchange.abandon();
                trace.accept(stage + "_stalled_in_" + where);
                task.check();
                throw transport(stage, "network_stage_timeout");
            } catch (CancellationException abandoned) {
                task.check();
                throw transport(stage, "network_stage_timeout");
            } catch (InterruptedException interrupted) {
                exchange.abandon();
                Thread.currentThread().interrupt();
                throw new AmFailure(Status.ERROR, "cancelled");
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof AmFailure failure) throw failure;
                if (cause instanceof IllegalArgumentException) throw new AmFailure(Status.UNSUPPORTED, "untrusted_network_uri");
                if (cause instanceof IOException error) {
                    trace.accept(stage + "_failed_in_" + exchange.phase + " bytes=" + exchange.bytesRead
                            + " declared=" + exchange.declaredBytes + " " + AmExceptionDiagnostic.describe(stage, error));
                    task.check(); throw transport(stage, transportReason(exchange.phase, error));
                }
                if (cause instanceof RuntimeException error) throw error;
                if (cause instanceof Error error) throw error;
                throw transport(stage, "network_io");
            }
            task.check();
            trace.accept(stage + "_complete");
            return exchange.cookies;
        } finally { task.exchange.compareAndSet(exchange, null); }
    }
    private TransportException transport(String stage, String reason) {
        trace.accept(stage + "_" + reason);
        return new TransportException(reason);
    }
    /** Runs on an exchange worker; once abandoned, its connection is closed and its outcome ignored. */
    private void exchange(URI uri, long limit, long expected, OutputStream target, Task task, Exchange exchange, Request request)
            throws AmFailure, IOException {
        exchange.worker = Thread.currentThread();
        // A page that trickles for half a minute is worse than a quick failure: the retry is usually fast.
        boolean neteaseVideo = "dcover.music.126.net".equals(uri.getHost());
        boolean netease = neteaseVideo || request != null;
        boolean compressible = expected <= 0 && !neteaseVideo;
        for (int redirects = 0; redirects <= 3; redirects++) {
            task.check();
            exchange.check();
            if (!networkAllowed.getAsBoolean()) throw new AmFailure(Status.NETWORK_BLOCKED, "network_policy");
            validateUri.accept(uri);
            exchange.phase = "dns";
            connections.resolve(uri.getHost());
            exchange.check();
            exchange.phase = "connect";
            HttpURLConnection connection = connections.open(uri);
            try {
                exchange.attach(connection);
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AppleWebKit/537.36 Chrome/122.0 Safari/537.36");
                connection.setRequestProperty("Accept-Encoding", compressible ? "gzip" : "identity");
                if (netease) {
                    connection.setUseCaches(false);
                    connection.setRequestProperty("Connection", "close");
                    connection.setRequestProperty("Referer", "https://music.163.com/");
                }
                if (request != null) {
                    connection.setUseCaches(false);
                    connection.setRequestProperty("Referer", "https://music.163.com/");
                    if (!request.cookie().isEmpty()) connection.setRequestProperty("Cookie", request.cookie());
                    if (request.form() != null) {
                        connection.setRequestMethod("POST");
                        connection.setDoOutput(true);
                        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                        connection.setFixedLengthStreamingMode(request.form().length);
                        try (OutputStream out = connection.getOutputStream()) { out.write(request.form()); }
                    }
                }
                connection.connect();
                exchange.check();
                exchange.phase = "headers";
                int status = connection.getResponseCode();
                exchange.check();
                if (request != null) {
                    java.util.List<String> cookies = new java.util.ArrayList<>();
                    connection.getHeaderFields().forEach((name, values) -> {
                        if ("Set-Cookie".equalsIgnoreCase(name) && values != null) cookies.addAll(values);
                    });
                    exchange.cookies = java.util.List.copyOf(cookies);
                }
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    if (request != null) throw new AmFailure(Status.RETRY_LATER, "netease_redirect_rejected", 60_000);
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException();
                    uri = uri.resolve(location);
                    continue;
                }
                if (status == 429) throw new AmFailure(Status.RETRY_LATER, "upstream_rate_limit", retryAfter(connection.getHeaderField("Retry-After")));
                if (status == 401 || status == 403) throw new AmFailure(Status.RETRY_LATER, "upstream_access_denied", 300_000);
                if (status != 200) throw new AmFailure(Status.RETRY_LATER, status == 404 ? "upstream_not_found" : "upstream_http_error", 60_000);
                long declared = connection.getContentLengthLong();
                exchange.declaredBytes = declared;
                if (declared > limit || (expected > 0 && declared > 0 && declared != expected)) throw new AmFailure(Status.UNSUPPORTED, "download_size_mismatch");
                boolean gzipped = compressible && "gzip".equalsIgnoreCase(connection.getHeaderField("Content-Encoding"));
                long read = 0;
                exchange.phase = "body";
                try (InputStream raw = connection.getInputStream();
                        InputStream stream = gzipped ? new java.util.zip.GZIPInputStream(raw) : raw) {
                    byte[] buffer = new byte[32 * 1024];
                    for (int count; (count = stream.read(buffer)) != -1;) {
                        task.check();
                        exchange.check();
                        if (!networkAllowed.getAsBoolean()) throw new AmFailure(Status.NETWORK_BLOCKED, "network_policy");
                        read += count;
                        exchange.bytesRead = read;
                        if (read > limit || (expected > 0 && read > expected)) throw new AmFailure(Status.UNSUPPORTED, "download_budget");
                        target.write(buffer, 0, count);
                    }
                }
                exchange.check();
                if (read == 0 || (expected > 0 && read != expected) || (!gzipped && declared > 0 && read != declared)) throw new java.io.EOFException();
                return;
            } catch (IOException error) {
                // disconnect() may surface as EOF/reset rather than SocketTimeoutException.
                exchange.check();
                throw error;
            } finally { exchange.detach(connection); connection.disconnect(); }
        }
        throw new AmFailure(Status.RETRY_LATER, "redirect_budget", 60_000);
    }
    /** Deadline and abandonment of one stage, covering lookup, connect, headers, redirects and body. */
    private static final class Exchange {
        private static final int STALL_FRAMES = 12;
        private final long startedAt = System.nanoTime();
        private final long expiresAt;
        volatile String phase = "dns";
        volatile Thread worker;
        volatile long bytesRead;
        volatile long declaredBytes = -1;
        volatile java.util.List<String> cookies = java.util.List.of();
        private boolean abandoned;
        private HttpURLConnection active;
        private Future<?> running;
        Exchange(long budgetMs) { expiresAt = startedAt + budgetMs * 1_000_000L; }
        /**
         * Phase, real elapsed time and the worker's top frames (class and method names only). An
         * elapsed time far beyond the budget means the waiting side itself was not running.
         */
        String describeStall(long budgetMs) {
            StringBuilder text = new StringBuilder(phase)
                    .append(" elapsedMs=").append(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
                    .append(" budgetMs=").append(budgetMs).append(" frames=");
            Thread stuck = worker;
            StackTraceElement[] frames = stuck == null ? new StackTraceElement[0] : stuck.getStackTrace();
            if (frames.length == 0) return text.append("none").toString();
            for (int index = 0; index < Math.min(STALL_FRAMES, frames.length); index++) {
                String type = frames[index].getClassName();
                if (index > 0) text.append("<-");
                text.append(type.substring(type.lastIndexOf('.') + 1)).append('.').append(frames[index].getMethodName());
            }
            return text.toString();
        }
        long remainingMs() { return Math.max(0, TimeUnit.NANOSECONDS.toMillis(expiresAt - System.nanoTime())); }
        void start(Future<?> future) {
            boolean cancel;
            synchronized (this) { running = future; cancel = abandoned; }
            if (cancel) future.cancel(true);
        }
        void abandon() {
            HttpURLConnection connection;
            Future<?> future;
            synchronized (this) { abandoned = true; connection = active; future = running; }
            if (future != null) future.cancel(true);
            if (connection != null) {
                try { DISCONNECTS.execute(connection::disconnect); }
                catch (RejectedExecutionException busy) { /* earlier disconnects still stuck; worker is abandoned anyway */ }
            }
        }
        synchronized void attach(HttpURLConnection connection) throws TransportException {
            check();
            active = connection;
        }
        synchronized void detach(HttpURLConnection connection) { if (active == connection) active = null; }
        synchronized void check() throws TransportException {
            if (abandoned || System.nanoTime() >= expiresAt) throw new TransportException("network_stage_timeout");
        }
    }
    static String transportReason(String phase, IOException error) {
        if (error instanceof TransportException carried) return carried.reason;
        if (!(error instanceof java.net.SocketTimeoutException)) return "network_io";
        return phase.equals("connect") ? "network_connect_timeout" : phase.equals("headers") ? "network_headers_timeout" : "network_read_timeout";
    }
    static long stageBudgetMs(String stage) {
        return "video_file".equals(stage) ? 15_000L : "web_album".equals(stage) ? 8_000L : 6_000L;
    }
    private void backoff(Task task, TransportException error, int attempt) throws AmFailure {
        if (attempt >= MAX_ATTEMPTS) return;
        trace.accept("retry_after_" + error.reason + "_attempt" + attempt);
        try { Thread.sleep(300L * attempt); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AmFailure(Status.ERROR, "cancelled"); }
        task.check();
    }
    private AmFailure failure(TransportException last) {
        String reason = last == null ? "network_io" : last.reason;
        trace.accept("exhausted_" + reason);
        return new AmFailure(Status.RETRY_LATER, reason, 30_000);
    }
    private static final class TransportException extends IOException {
        final String reason;
        TransportException(String reason) { super(reason); this.reason = reason; }
    }
    static long retryAfter(String value) {
        try { return Math.max(1000, Math.min(86_400_000, Math.multiplyExact(Long.parseLong(value), 1000))); }
        catch (RuntimeException ignored) { return 60_000; }
    }
}
