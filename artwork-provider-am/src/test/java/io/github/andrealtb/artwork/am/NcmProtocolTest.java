package io.github.andrealtb.artwork.am;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.Test;
import static org.junit.Assert.*;

public class NcmProtocolTest {
    @Test public void weapiMatchesTheIndependentlyGeneratedNodeCryptoVector() throws Exception {
        String form = new String(NcmProtocol.weapi("{\"type\":1}", "abcdefghijklmnop".getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        Map<String, String> values = java.util.Arrays.stream(form.split("&")).map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> pair[0], pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
        assertEquals("0f2+q4z7Gkb6i+Ic3AtL4n9NgjVpemF15SZny99LVtw=", values.get("params"));
        assertEquals("d15a1683c992095d0c234c19966605c5c5964911268bbeda8cb8d08d834913e59d53b32358903a121b5fca784c1f5ae44951fd02524df58ecc98e52cc7cf8689b42c2e93ddf05b0592512d87f5960467e2f086c018849d76014d323500e30f13ef4cafbb0cf5a66731a3f1776c75ca35d0062dac70a3e33245afabcf47938487", values.get("encSecKey"));
        assertNotEquals(form, new String(NcmProtocol.weapi("{\"type\":1}"), StandardCharsets.UTF_8));
    }
    @Test public void setCookieAttributesAreNotSentBackAndRenewalPreservesMusicU() {
        String saved = NcmProtocol.cookies("", List.of("MUSIC_U=test-session; Path=/; HttpOnly; Secure", "__csrf=first; Path=/"));
        assertTrue(NcmProtocol.loggedIn(saved));
        String renewed = NcmProtocol.cookies(saved, List.of("__csrf=next; Path=/; SameSite=Lax", "irrelevant=tracking; Path=/"));
        assertEquals("MUSIC_U=test-session; __csrf=next", renewed);
        assertEquals("next", NcmProtocol.csrf(renewed));
        assertFalse(NcmProtocol.loggedIn(NcmProtocol.cookies(renewed, List.of("MUSIC_U=deleted; Max-Age=0; Path=/"))));
        assertEquals(renewed, NcmProtocol.cookies(renewed, List.of("MUSIC_U=unsafe\r\nAuthorization: malicious")));
    }
    @Test public void signedVideoUsesHttpsAndCredentialsCannotBeSentToCdnOrForeignHosts() {
        assertEquals("https://dcover.music.126.net/a/test.mp4?wsSecret=sample&wsTime=1",
                NcmProtocol.videoUri("http://dcover.music.126.net/a/test.mp4?wsSecret=sample&wsTime=1").toString());
        NcmProtocol.validateApi(URI.create("https://music.163.com/weapi/login/qrcode/unikey"));
        for (String uri : List.of("http://music.163.com/api/test", "https://music.163.com.evil.example/api/test",
                "https://dcover.music.126.net/api/test", "https://music.163.com:443/api/test", "https://user@music.163.com/api/test")) {
            try { NcmProtocol.validateApi(URI.create(uri)); fail(uri); } catch (IllegalArgumentException expected) {}
        }
        for (String uri : List.of("https://evil.example/a.mp4", "https://music.163.com/a.mp4", "https://dcover.music.126.net/a.m3u8")) {
            try { NcmProtocol.videoUri(uri); fail(uri); } catch (IllegalArgumentException expected) {}
        }
    }
}
