package io.github.andrealtb.artwork.am;

import org.junit.Test;
import static org.junit.Assert.*;
import java.net.URI;

public class AmHlsTest {
    @Test public void realPublicMasterSelects1080AndLegacyByteRangesStillParse() throws Exception {
        String master, child;
        try (var stream = getClass().getResourceAsStream("/square-master.m3u8")) {
            master = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        try (var stream = getClass().getResourceAsStream("/child-avc1-408x408.m3u8")) {
            child = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var variant = AmHls.variants(BASE, master, AmIdentityTest.query("Style", "1989", 288)).get(0);
        assertEquals(1080, variant.width()); assertEquals("avc1.640020", variant.codec());
        assertEquals(728484, AmHls.filePlan(BASE, child, 20 * 1024 * 1024).bytes());
    }
    static final URI BASE = URI.create("https://mvod.itunes.apple.com/a/master.m3u8");
    @Test public void diagnosticSeparatesLayoutFailuresWithoutExposingPayload() throws Exception {
        String[][] cases = {
                { CHILD.replace("#EXT-X-ENDLIST", ""), "missing_endlist" },
                { CHILD.replace("#EXT-X-PLAYLIST-TYPE:VOD", ""), "missing_vod_marker" },
                { CHILD.replace("134643@985", "134643@986"), "segment_range_noncontiguous_or_invalid" },
                { CHILD.replace("#EXT-X-BYTERANGE:134643@985\n", ""), "segment_missing_map_duration_or_range" },
                { CHILD.replace("clip.mp4", "https://untrusted.example/private?token=secret"), "map_uri" }
        };
        for (String[] item : cases) {
            try { AmHls.filePlan(BASE, item[0], 1_000_000); fail(); }
            catch (AmFailure failure) {
                assertEquals("unsupported_hls_layout", failure.reason);
                assertTrue(failure.detail, failure.detail.startsWith(item[1] + " "));
                assertFalse(failure.detail.contains("secret"));
                assertFalse(failure.detail.contains("https://"));
                assertFalse(failure.detail.contains("clip.mp4"));
            }
        }
        try { AmHls.filePlan(BASE, CHILD, 461520); fail(); }
        catch (AmFailure failure) { assertEquals("source_file_too_large", failure.reason); assertTrue(failure.detail.startsWith("file_byte_budget ")); }
    }
    static final String CHILD = "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-MAP:URI=\"clip.mp4\",BYTERANGE=\"985@0\"\n"
            + "#EXTINF:5.54721,\n#EXT-X-BYTERANGE:134643@985\nclip.mp4\n"
            + "#EXTINF:5.58892,\n#EXT-X-BYTERANGE:141362@135628\nclip.mp4\n"
            + "#EXTINF:5.58892,\n#EXT-X-BYTERANGE:184531@276990\nclip.mp4\n#EXT-X-ENDLIST\n";
    @Test public void repeatedFileUrisAreOneWholeFileWithContinuousRanges() throws Exception {
        var plan = AmHls.filePlan(BASE, CHILD, 1_000_000);
        assertEquals(461521, plan.bytes()); assertEquals(16.72505, plan.durationSeconds(), 0.000001);
        assertEquals(URI.create("https://mvod.itunes.apple.com/a/clip.mp4"), plan.uri());
    }
    @Test public void rejectMissingRangeEncryptionGapsLiveAndMultipleFiles() throws Exception {
        for (String broken : new String[] { CHILD.replace("#EXT-X-ENDLIST", ""), CHILD.replace("#EXT-X-PLAYLIST-TYPE:VOD", ""),
                CHILD + "#EXT-X-KEY:METHOD=AES-128\n", CHILD.replace("134643@985", "134643@986"),
                CHILD.replace("#EXT-X-BYTERANGE:134643@985\n", ""), CHILD.replace("184531@276990\nclip.mp4", "184531@276990\nother.mp4") }) {
            try { AmHls.filePlan(BASE, broken, 1_000_000); fail(); } catch (AmFailure expected) { assertEquals("unsupported_hls_layout", expected.reason); }
        }
    }
    @Test public void oversizeWholeFileIsRejectedBeforeDownload() throws Exception {
        try { AmHls.filePlan(BASE, CHILD, 461520); fail(); } catch (AmFailure expected) { assertEquals("source_file_too_large", expected.reason); }
    }
    @Test public void implicitOffsetsAreAcceptedOnlyWithSameFile() throws Exception {
        assertEquals(461521, AmHls.filePlan(BASE, CHILD.replace("141362@135628", "141362"), 1_000_000).bytes());
    }
    static String stream(int size, String codec, long bitrate, String file) {
        return "#EXT-X-STREAM-INF:CODECS=\"" + codec + "\",VIDEO-RANGE=SDR,FRAME-RATE=23.976,RESOLUTION=" + size + "x" + size
                + ",BANDWIDTH=" + bitrate + "\n" + file + "\n";
    }
    @Test public void smallAndLargeSurfacesChooseIdenticalNative1080Variants() throws Exception {
        String master = "#EXTM3U\n" + stream(360, "hvc1.2.20000000.L123.B0", 220757, "360.m3u8")
                + stream(408, "avc1.64001f", 348452, "408.m3u8")
                + stream(1080, "avc1.640020", 2000000, "1080-hi.m3u8")
                + stream(1080, "avc1.640020", 1000000, "1080-low.m3u8")
                + stream(2160, "avc1.640020", 4000000, "2160.m3u8");
        var small = AmHls.variants(BASE, master, AmIdentityTest.query("Style", "1989", 288));
        var large = AmHls.variants(BASE, master, AmIdentityTest.query("Style", "1989", 1312));
        assertEquals(small, large);
        assertEquals(2, small.size());
        assertEquals(1000000, small.get(0).bitrate());
        for (var item : small) { assertEquals(1080, item.width()); assertEquals(1080, item.height()); }
    }
    @Test public void missing1080NeverTriggersSmallDownloadAndClientCapsAreRespected() throws Exception {
        String master = "#EXTM3U\n" + stream(408, "avc1.64001f", 348452, "408.m3u8");
        try { AmHls.variants(BASE, master, AmIdentityTest.query("Style", "1989", 288)); fail(); }
        catch (AmFailure expected) { assertEquals("no_1080_avc_variant", expected.reason); }
        var q = new io.github.andrealtb.artwork.contract.ArtworkQuery("Style", "Taylor Swift", "1989", 231000, "",
                288, 288, 768, 768, 20 * 1024 * 1024);
        try { AmHls.variants(BASE, master + stream(1080, "avc1.640020", 1000000, "1080.m3u8"), q); fail(); }
        catch (AmFailure expected) { assertEquals("no_1080_avc_variant", expected.reason); }
    }
    @Test public void crossHostAndCleartextMediaAreRejected() {
        for (String uri : new String[] { "http://mvod.itunes.apple.com/a.mp4", "https://evil.test/a.mp4", "https://mvod.itunes.apple.com:443/a.mp4" }) {
            try { AmHls.mediaUri(URI.create(uri)); fail(); } catch (IllegalArgumentException expected) { /* allowlisted transport */ }
        }
    }
}
