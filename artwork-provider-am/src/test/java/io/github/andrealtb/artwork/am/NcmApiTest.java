package io.github.andrealtb.artwork.am;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

public class NcmApiTest {
    @Test public void titleSearchSelectsTheSampleSongIdAndRequestsItsFullscreenCover() throws Exception {
        String[] titles = {"Something Just Like This", "特别的人 (Special Person)"};
        String[] artists = {"The Chainsmokers/Coldplay", "方大同"};
        String[] ids = {"461347998", "28403111"};
        String[] albums = {"Something Just Like This", "危险世界"};
        long[] durations = {247626, 259064};
        for (int i = 0; i < titles.length; i++) {
            var query = new io.github.andrealtb.artwork.contract.ArtworkQuery(titles[i], artists[i], albums[i],
                    durations[i], "", 288, 288, 1280, 1280, 20 * 1024 * 1024);
            String term = NcmSearch.searchTerm(query);
            var credits = new org.json.JSONArray();
            for (String credit : artists[i].split("/")) credits.put(new org.json.JSONObject().put("name", credit));
            var original = new org.json.JSONObject().put("id", ids[i]).put("name", term).put("ar", credits)
                    .put("al", new org.json.JSONObject().put("id", "9").put("name", albums[i])).put("dt", durations[i]);
            var coverArtist = new org.json.JSONObject(original.toString()).put("id", "99")
                    .put("ar", new org.json.JSONArray().put(new org.json.JSONObject().put("name", "Cover Artist")));
            Fake search = new Fake(new org.json.JSONObject().put("code", 200).put("result", new org.json.JSONObject()
                    .put("songs", new org.json.JSONArray().put(coverArtist).put(original))).toString(), List.of(), 200);
            Fake cover = new Fake("{\"code\":200,\"data\":{\"videoPlayUrl\":\"https://dcover.music.126.net/test.mp4\"}}", List.of(), 200);
            var paths = new java.util.ArrayList<String>();
            NcmApi api = new NcmApi(network(() -> true, uri -> {
                paths.add(uri.getPath());
                if (uri.getPath().equals("/api/cloudsearch/pc")) {
                    assertTrue(uri.getRawQuery().contains("s=" + NcmProtocol.encode(term) + "&")
                            || uri.getRawQuery().endsWith("s=" + NcmProtocol.encode(term)));
                    return search;
                }
                assertEquals("/api/songplay/dynamic-cover", uri.getPath()); return cover;
            }), epoch -> fail());
            var task = new AmNetwork.Task();
            var selected = NcmSearch.choose(api.search(term, task), query, AmIdentity.MatchProfile.STANDARD);
            assertEquals(ids[i], selected.id());
            assertEquals("https://dcover.music.126.net/test.mp4", api.cover(
                    new NcmSession.Session("MUSIC_U=test-session", "123", "Sample", 1, 7), selected.id(), task).toString());
            assertEquals(List.of("/api/cloudsearch/pc", "/api/songplay/dynamic-cover"), paths);
            String form = cover.posted.toString(StandardCharsets.UTF_8);
            assertTrue(form.contains("songId=" + ids[i])); assertTrue(form.contains("scene=fullscreen"));
            assertFalse(search.getRequestProperty("Cookie").contains("MUSIC_U"));
        }
    }
    private static final URI LOGIN = URI.create("https://music.163.com/weapi/login/qrcode/client/login");
    private static final class Fake extends HttpURLConnection {
        final byte[] body;
        final List<String> cookies;
        final int status;
        final ByteArrayOutputStream posted = new ByteArrayOutputStream();
        Fake(String json, List<String> cookies, int status) throws Exception {
            super(LOGIN.toURL()); body = json.getBytes(StandardCharsets.UTF_8); this.cookies = cookies; this.status = status;
        }
        @Override public void connect() {}
        @Override public void disconnect() {}
        @Override public boolean usingProxy() { return false; }
        @Override public int getResponseCode() { return status; }
        @Override public long getContentLengthLong() { return body.length; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        @Override public OutputStream getOutputStream() { return posted; }
        @Override public Map<String, List<String>> getHeaderFields() { return Map.of("Set-Cookie", cookies); }
        @Override public String getHeaderField(String name) { return name.equals("Location") ? "https://evil.example/collect" : null; }
    }
    private static AmNetwork network(java.util.function.BooleanSupplier allowed, AmNetwork.Connections connections) {
        return new AmNetwork(allowed, reason -> {}, connections, stage -> 1000, NcmProtocol::validateApi);
    }
    @Test public void qrWaitingConfirmationExpiryAndSuccessAreDistinctAnd803CollectsSetCookie() throws Exception {
        var responses = new ArrayDeque<Fake>();
        for (int code : new int[] {801, 802, 800, 803}) responses.add(new Fake("{\"code\":" + code + "}",
                code == 803 ? List.of("MUSIC_U=test-session; Path=/; HttpOnly", "__csrf=test-csrf; Path=/") : List.of(), 200));
        NcmApi api = new NcmApi(network(() -> true, uri -> responses.remove()), epoch -> fail("QR status must not expire an account"));
        for (int code : new int[] {801, 802, 800, 803}) {
            var reply = api.check(new NcmApi.Qr("test-qr-key", "unused"), new AmNetwork.Task());
            assertEquals(code, reply.code());
            assertEquals(code == 803, NcmProtocol.loggedIn(reply.cookie()));
        }
    }
    @Test public void qrKeyAndAccountComeFromOfficialEndpointsAndThePostUsesWeapi() throws Exception {
        Fake key = new Fake("{\"code\":200,\"unikey\":\"sample-key-1234\"}", List.of(), 200);
        Fake account = new Fake("{\"code\":200,\"profile\":{\"userId\":123,\"nickname\":\"Sample\"}}", List.of(), 200);
        NcmApi api = new NcmApi(network(() -> true, uri -> {
            if (uri.getPath().equals("/weapi/login/qrcode/unikey")) return key;
            assertEquals("/api/nuser/account/get", uri.getPath()); return account;
        }), epoch -> fail());
        var qr = api.qr(new AmNetwork.Task());
        assertEquals("https://music.163.com/login?codekey=sample-key-1234", qr.url());
        assertEquals("POST", key.getRequestMethod());
        assertTrue(key.posted.toString(StandardCharsets.UTF_8).contains("encSecKey="));
        var profile = api.account(new NcmSession.Session("MUSIC_U=test-session", "", "", 1, 7), new AmNetwork.Task());
        assertEquals("123", profile.uid()); assertEquals("Sample", profile.nickname());
        assertEquals("MUSIC_U=test-session; os=pc; appver=9.5.70", account.getRequestProperty("Cookie"));
    }
    @Test public void emptyCoverIsNoMotionWhile301ExpiresOnlyTheExpectedSession() throws Exception {
        var replies = new ArrayDeque<Fake>();
        replies.add(new Fake("{\"code\":200,\"data\":{}}", List.of(), 200));
        replies.add(new Fake("{\"code\":301,\"message\":\"系统错误\"}", List.of(), 200));
        AtomicLong expired = new AtomicLong(-1);
        NcmApi api = new NcmApi(network(() -> true, uri -> replies.remove()), expired::set);
        var session = new NcmSession.Session("MUSIC_U=test-session", "123", "Sample", 1, 7);
        try { api.cover(session, "1", new AmNetwork.Task()); fail(); }
        catch (AmFailure expected) { assertEquals(Status.NO_MOTION, expected.status); }
        assertEquals(-1, expired.get());
        try { api.cover(session, "1", new AmNetwork.Task()); fail(); }
        catch (AmFailure expected) { assertEquals(Status.NETWORK_BLOCKED, expected.status); assertEquals("netease_login_required", expected.reason); }
        assertEquals(7, expired.get());
    }
    @Test public void authenticatedRedirectCannotForwardCredentialsAndDisabledPolicyMakesNoConnection() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        Fake redirect = new Fake("", List.of(), 302);
        AmNetwork network = network(() -> true, uri -> { opened.incrementAndGet(); return redirect; });
        try { network.response(LOGIN, "a=b".getBytes(StandardCharsets.UTF_8), "MUSIC_U=test-session", new AmNetwork.Task()); fail(); }
        catch (AmFailure expected) { assertEquals("netease_redirect_rejected", expected.reason); }
        assertEquals(1, opened.get());
        AmNetwork disabled = network(() -> false, uri -> { fail("disabled source opened connection"); return redirect; });
        try { disabled.response(LOGIN, null, "MUSIC_U=test-session", new AmNetwork.Task()); fail(); }
        catch (AmFailure expected) { assertEquals(Status.NETWORK_BLOCKED, expected.status); }
    }
    @Test public void refreshRotatesCsrfWithoutDiscardingLoginCookie() throws Exception {
        Fake response = new Fake("{\"code\":200}", List.of("__csrf=new; Path=/"), 200);
        NcmApi api = new NcmApi(network(() -> true, uri -> response), epoch -> fail());
        var refreshed = api.refresh(new NcmSession.Session("MUSIC_U=test-session; __csrf=old", "123", "Sample", 1, 7), new AmNetwork.Task());
        assertEquals("MUSIC_U=test-session; __csrf=new", refreshed.cookie());
        assertTrue(refreshed.refreshedAt() > 1); assertEquals(7, refreshed.epoch());
    }
}
