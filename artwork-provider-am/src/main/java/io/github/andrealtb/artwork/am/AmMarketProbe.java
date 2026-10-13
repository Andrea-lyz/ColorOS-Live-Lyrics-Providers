package io.github.andrealtb.artwork.am;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * Reachability probe for a storefront home page: green latency when the market answers, red
 * timeout otherwise. Each sample reads one body byte and never touches the provider cache.
 */
final class AmMarketProbe {
    /** Connection seam: unit tests replace the platform connection with a fake. */
    interface Opener { HttpURLConnection open(URI uri) throws IOException; }
    record Result(boolean reachable, long latencyMs) {}
    static final int CONNECT_TIMEOUT_MS = 4_000;
    static final int READ_TIMEOUT_MS = 4_000;
    /** Two samples: the cold first exchange (DNS, connection, radio wake-up) is not connectivity. */
    static final int SAMPLES = 2;
    private static final String USER_AGENT = "Mozilla/5.0 (Android) AppleWebKit/537.36 Chrome/122.0 Safari/537.36";
    private static final ExecutorService WORKER = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "artwork-am-market");
        thread.setDaemon(true);
        return thread;
    });

    private AmMarketProbe() {}

    /** Shared background lanes: the home-page probe and the dialog sequence must not block each other. */
    static ExecutorService worker() { return WORKER; }

    /** Storefront home of a validated two-letter market code; the request follows Apple's redirects. */
    static URI storefront(String country) { return URI.create("https://music.apple.com/" + country + "/"); }

    static Result measure(String country) { return measure(country, AmMarketProbe::open); }

    /**
     * The first exchange of an idle process pays DNS, a fresh connection and radio wake-up, so a
     * single sample reports peaks that say nothing about the market. Two samples are taken and the
     * faster one is reported; a failed first sample is the timeout, without a second request.
     */
    static Result measure(String country, Opener opener) {
        Result best = null;
        for (int attempt = 0; attempt < SAMPLES; attempt++) {
            Result sample = sample(country, opener);
            if (!sample.reachable()) return best == null ? sample : best;
            if (best == null || sample.latencyMs() < best.latencyMs()) best = sample;
        }
        return best;
    }

    private static Result sample(String country, Opener opener) {
        long started = System.nanoTime();
        HttpURLConnection connection = null;
        try {
            connection = opener.open(storefront(country));
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            if (connection.getResponseCode() != 200) return new Result(false, elapsedMs(started));
            try (InputStream body = connection.getInputStream()) { body.read(); }
            return new Result(true, elapsedMs(started));
        } catch (Exception error) {
            return new Result(false, elapsedMs(started));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Runs one market at a time in the shown order; a pending dialog stops the sequence on dismiss. */
    static void sequence(List<String> markets, BooleanSupplier stopped, BiConsumer<Integer, Result> report) {
        sequence(markets, AmMarketProbe::open, stopped, report);
    }

    static void sequence(List<String> markets, Opener opener, BooleanSupplier stopped, BiConsumer<Integer, Result> report) {
        for (int index = 0; index < markets.size(); index++) {
            if (stopped.getAsBoolean()) return;
            Result result = measure(markets.get(index), opener);
            if (stopped.getAsBoolean()) return;
            report.accept(index, result);
        }
    }

    static long elapsedMs(long startedNanos) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos); }

    private static HttpURLConnection open(URI uri) throws IOException { return (HttpURLConnection) uri.toURL().openConnection(); }
}
