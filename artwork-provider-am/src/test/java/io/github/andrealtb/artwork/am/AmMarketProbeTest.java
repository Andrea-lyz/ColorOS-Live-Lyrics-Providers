package io.github.andrealtb.artwork.am;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AmMarketProbeTest {
    @Test public void storefrontAddressesTheSelectedMarket() {
        assertEquals("https://music.apple.com/cn/", AmMarketProbe.storefront("cn").toString());
        assertEquals("https://music.apple.com/mx/", AmMarketProbe.storefront("mx").toString());
    }

    @Test public void reachableStorefrontReportsLatencyWithBoundedTimeouts() {
        Fake connection = new Fake(200, new byte[] { 1 });
        AmMarketProbe.Result result = AmMarketProbe.measure("jp", uri -> connection);
        assertTrue(result.reachable());
        assertTrue(result.latencyMs() >= 0);
        assertEquals(AmMarketProbe.CONNECT_TIMEOUT_MS, connection.connectTimeout);
        assertEquals(AmMarketProbe.READ_TIMEOUT_MS, connection.readTimeout);
        assertTrue("the probe stays the provider's own Android user agent",
                connection.headers.contains("User-Agent=Mozilla/5.0 (Android) AppleWebKit/537.36 Chrome/122.0 Safari/537.36"));
    }

    @Test public void httpErrorsAndTransportFailuresBothCountAsTimeout() {
        assertFalse(AmMarketProbe.measure("us", uri -> new Fake(503, new byte[0])).reachable());
        assertFalse(AmMarketProbe.measure("us", uri -> { throw new IOException("unreachable"); }).reachable());
    }

    @Test public void coldFirstSampleIsReplacedByTheWarmSecond() {
        AtomicInteger opened = new AtomicInteger();
        AmMarketProbe.Result result = AmMarketProbe.measure("us", uri ->
                new Fake(200, new byte[] { 1 }, opened.getAndIncrement() == 0 ? 250 : 0));
        assertTrue(result.reachable());
        assertTrue("the warm sample wins: " + result.latencyMs(), result.latencyMs() < 200);
        assertEquals(2, opened.get());
    }

    @Test public void failedFirstSampleReportsTimeoutWithoutASecondAttempt() {
        AtomicInteger opened = new AtomicInteger();
        AmMarketProbe.Result result = AmMarketProbe.measure("us", uri -> {
            opened.incrementAndGet();
            return new Fake(503, new byte[0]);
        });
        assertFalse(result.reachable());
        assertEquals(1, opened.get());
    }

    @Test public void sequenceChecksMarketsOneByOneInOrder() {
        List<String> markets = List.of("cn", "jp", "gb");
        List<String> results = new ArrayList<>();
        AmMarketProbe.sequence(markets, uri -> new Fake(uri.getPath().startsWith("/jp") ? 500 : 200, new byte[] { 1 }),
                () -> false, (index, result) -> results.add(index + "=" + result.reachable()));
        assertEquals(List.of("0=true", "1=false", "2=true"), results);
    }

    @Test public void dismissingTheDialogStopsRemainingChecks() {
        List<String> markets = List.of("cn", "jp", "gb");
        AtomicInteger opened = new AtomicInteger();
        List<String> results = new ArrayList<>();
        boolean[] stopped = { false };
        AmMarketProbe.sequence(markets, uri -> { opened.incrementAndGet(); return new Fake(200, new byte[] { 1 }); },
                () -> stopped[0], (index, result) -> { results.add(String.valueOf(index)); stopped[0] = true; });
        assertEquals(List.of("0"), results);
        assertEquals("the first market samples twice before the stop", 2, opened.get());
    }

    /** A canned storefront response; only what the probe touches is implemented. */
    private static final class Fake extends HttpURLConnection {
        private final int status;
        private final byte[] body;
        private final long sleepMs;
        final List<String> headers = new ArrayList<>();
        int connectTimeout;
        int readTimeout;
        Fake(int status, byte[] body) { this(status, body, 0); }
        Fake(int status, byte[] body, long sleepMs) { super(url()); this.status = status; this.body = body; this.sleepMs = sleepMs; }
        @Override public void connect() {}
        @Override public boolean usingProxy() { return false; }
        @Override public void disconnect() {}
        @Override public int getResponseCode() throws IOException {
            if (sleepMs > 0) {
                try { Thread.sleep(sleepMs); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
            }
            return status;
        }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        @Override public void setRequestProperty(String key, String value) { headers.add(key + "=" + value); }
        @Override public void setConnectTimeout(int timeout) { connectTimeout = timeout; }
        @Override public void setReadTimeout(int timeout) { readTimeout = timeout; }
        private static URL url() {
            try { return new URL("https://music.apple.com/"); }
            catch (java.net.MalformedURLException impossible) { throw new AssertionError(impossible); }
        }
    }
}
