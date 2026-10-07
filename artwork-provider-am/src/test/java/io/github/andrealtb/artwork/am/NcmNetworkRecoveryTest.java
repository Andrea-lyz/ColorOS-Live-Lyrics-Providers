package io.github.andrealtb.artwork.am;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

public class NcmNetworkRecoveryTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    private static final URI API = URI.create("https://music.163.com/api/cloudsearch/pc?s=sample");
    private static final URI VIDEO = URI.create("https://dcover.music.126.net/sample.mp4?wsSecret=private-signature");
    private static final class Fake extends HttpURLConnection {
        final byte[] body;
        final long declared;
        final boolean failHeaders;
        final int status;
        Fake(URI uri, byte[] body, long declared, boolean failHeaders, int status) throws Exception {
            super(uri.toURL()); this.body = body; this.declared = declared; this.failHeaders = failHeaders; this.status = status;
        }
        @Override public void connect() {}
        @Override public void disconnect() {}
        @Override public boolean usingProxy() { return false; }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public int getResponseCode() throws IOException {
            if (failHeaders) throw new EOFException("https://secret.example/?cookie=MUSIC_U=private-cookie");
            return status;
        }
        @Override public Map<String, List<String>> getHeaderFields() { return Map.of(); }
        @Override public String getHeaderField(String name) { return null; }
        @Override public long getContentLengthLong() { return declared; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
    }
    @Test public void readOnlyApiRecoversFromHeaderResetWithoutLeakingMessagesOrCredentials() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        List<String> traces = new CopyOnWriteArrayList<>();
        byte[] body = "{\"code\":200}".getBytes(StandardCharsets.UTF_8);
        Fake first = new Fake(API, body, body.length, true, 200), second = new Fake(API, body, body.length, false, 200);
        AmNetwork network = new AmNetwork(() -> true, traces::add, uri -> opened.getAndIncrement() == 0 ? first : second,
                stage -> 1000, NcmProtocol::validateApi);
        var result = network.response(API, null, "MUSIC_U=private-cookie", new AmNetwork.Task(), true);
        assertEquals("{\"code\":200}", result.text()); assertEquals(2, opened.get());
        assertEquals("close", second.getRequestProperty("Connection"));
        String trace = String.join("\n", traces);
        assertTrue(trace, trace.contains("failed_in_headers"));
        assertTrue(trace, trace.contains("java.io.EOFException"));
        assertFalse(trace.contains("private-cookie")); assertFalse(trace.contains("secret.example")); assertFalse(trace.contains("https://"));
    }
    @Test public void truncatedVideoRetriesFromZeroAndUsesIdentityEncodingWithoutCookies() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        List<String> traces = new CopyOnWriteArrayList<>();
        Fake first = new Fake(VIDEO, new byte[] {9}, 4, false, 200);
        Fake second = new Fake(VIDEO, new byte[] {1, 2, 3, 4}, 4, false, 200);
        AmNetwork network = new AmNetwork(() -> true, traces::add, uri -> opened.getAndIncrement() == 0 ? first : second,
                stage -> 1000, NcmProtocol::validateVideo);
        var output = files.newFile();
        network.file(VIDEO, output, 10, new AmNetwork.Task());
        assertArrayEquals(new byte[] {1, 2, 3, 4}, java.nio.file.Files.readAllBytes(output.toPath()));
        assertEquals(2, opened.get()); assertEquals("identity", second.getRequestProperty("Accept-Encoding"));
        assertEquals("https://music.163.com/", second.getRequestProperty("Referer"));
        assertNull(second.getRequestProperty("Cookie"));
        assertTrue(traces.stream().anyMatch(trace -> trace.contains("failed_in_body bytes=1 declared=4")));
        assertFalse(String.join("\n", traces).contains("private-signature"));
    }
    @Test public void cancellationDuringBackoffCannotStartAnotherAttempt() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        Fake failure = new Fake(API, new byte[0], 0, true, 200);
        AmNetwork.Task task = new AmNetwork.Task();
        AmNetwork network = new AmNetwork(() -> true, trace -> { if (trace.startsWith("retry_after_")) task.cancel(); },
                uri -> { opened.incrementAndGet(); return failure; }, stage -> 1000, NcmProtocol::validateApi);
        try { network.response(API, null, "", task, true); fail(); }
        catch (AmFailure expected) { assertEquals("cancelled", expected.reason); }
        assertEquals(1, opened.get());
    }
    @Test public void qrAuthenticationAndRateLimitsAreNeverRetried() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        Fake failure = new Fake(API, new byte[0], 0, true, 200);
        AmNetwork network = new AmNetwork(() -> true, trace -> {}, uri -> { opened.incrementAndGet(); return failure; },
                stage -> 1000, NcmProtocol::validateApi);
        try { network.response(API, new byte[] {1}, "", new AmNetwork.Task()); fail(); }
        catch (AmFailure expected) { assertEquals("network_io", expected.reason); }
        assertEquals(1, opened.get());
        Fake limited = new Fake(API, new byte[0], 0, false, 429);
        AmNetwork rateLimited = new AmNetwork(() -> true, trace -> {}, uri -> { opened.incrementAndGet(); return limited; },
                stage -> 1000, NcmProtocol::validateApi);
        try { rateLimited.response(API, null, "", new AmNetwork.Task(), true); fail(); }
        catch (AmFailure expected) { assertEquals(Status.RETRY_LATER, expected.status); assertEquals("upstream_rate_limit", expected.reason); }
        assertEquals(2, opened.get());
    }
    @Test public void freshResolveRoundRecoversAfterAllThreeImmediateAttemptsFail() throws Exception {
        var cache = new AmCache(files.newFolder()); String key = AmCache.hash("recover"), epoch = "network-a";
        var task = new AmNetwork.Task(52_000); var opened = new AtomicInteger(); var rounds = new AtomicInteger();
        byte[] body = "{\"code\":200}".getBytes(StandardCharsets.UTF_8);
        Fake failed = new Fake(API, body, body.length, true, 200), healthy = new Fake(API, body, body.length, false, 200);
        AmNetwork network = new AmNetwork(() -> true, trace -> {}, uri -> opened.incrementAndGet() <= 3 ? failed : healthy,
                stage -> 1000, NcmProtocol::validateApi);
        assertEquals("{\"code\":200}", AmTransportRetry.run(task, () -> {
            rounds.incrementAndGet();
            var hit = cache.lookup(key, epoch, task.recoveringTransport());
            if (hit != null && hit.failure() != null)
                throw new AmFailure(hit.failure().status, hit.failure().reason, hit.failure().retryAfterMs);
            try { return network.response(API, null, "", task, true).text(); }
            catch (AmFailure failure) { cache.remember(key, null, failure.result(), epoch); throw failure; }
        }, (retry, delay, reason) -> assertEquals("network_io", reason), (current, delay) -> current.check()));
        assertEquals(2, rounds.get()); assertEquals(4, opened.get());
    }
    @Test public void tokenRefreshFailureIsNotReplayedByResolveRecovery() throws Exception {
        var parent = new AmNetwork.Task(52_000); var child = parent.fork(); var opened = new AtomicInteger();
        Fake failed = new Fake(API, new byte[0], 0, true, 200);
        NcmApi api = new NcmApi(new AmNetwork(() -> true, trace -> {}, uri -> { opened.incrementAndGet(); return failed; },
                stage -> 1000, NcmProtocol::validateApi), epoch -> fail());
        try {
            AmTransportRetry.run(parent, () -> api.refresh(new NcmSession.Session("MUSIC_U=sample", "123", "Sample", 1, 7), child),
                    (retry, delay, reason) -> fail("refresh must stay single-attempt"), (current, delay) -> fail());
            fail();
        } catch (AmFailure expected) { assertEquals("network_io", expected.reason); }
        finally { child.release(); }
        assertEquals(1, opened.get());
    }
}
