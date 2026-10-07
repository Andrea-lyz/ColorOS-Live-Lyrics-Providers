package io.github.andrealtb.artwork.am;

import java.net.URI;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class NcmArtworkTest {
    @Test public void officialPictureIsUpgradedToHttpsAndBoundedToThumbnailSize() {
        String path = "p1.music.126.net/fdh0myRe6FD87QNJtvGe_A==/109951163054654501.jpg";
        assertEquals("https://" + path + "?param=300y300", NcmArtwork.thumbnailUrl("http://" + path, 300));
        assertEquals("https://" + path + "?param=512y512", NcmArtwork.thumbnailUrl("https://" + path + "?param=4000y4000&unused=value", 4000));
        assertEquals("https://" + path + "?param=64y64", NcmArtwork.thumbnailUrl("https://" + path, 1));
    }

    @Test public void invalidPicturesDoNotBecomeNetworkRequests() {
        for (String url : List.of("", "not a url", "//p1.music.126.net/a/1.jpg",
                "https://evil.example/1.jpg", "https://p1.music.126.net.evil.example/1.jpg",
                "https://user@p1.music.126.net/a/1.jpg", "https://p1.music.126.net:443/a/1.jpg",
                "file://p1.music.126.net/a/1.jpg", "https://p1.music.126.net/a/1.jpg#fragment",
                "https://p1.music.126.net/", "https://music.163.com/api/nuser/account/get")) {
            assertEquals(url, "", NcmArtwork.thumbnailUrl(url, 300));
        }
        assertEquals("", NcmArtwork.thumbnailUrl(null, 300));
        assertEquals("", NcmArtwork.thumbnailUrl("https://p1.music.126.net/" + "a".repeat(2048), 300));
        assertFalse(NcmArtwork.imageHost(URI.create("http://p1.music.126.net/a/1.jpg")));
        assertTrue(NcmArtwork.imageHost(URI.create("https://p2.music.126.net/a/1.jpg?param=300y300")));
    }

    @Test public void pictureHostsDoNotBroadenAuthenticatedApiOrVideoPolicies() {
        URI picture = URI.create("https://p1.music.126.net/a/1.jpg?param=300y300");
        assertTrue(NcmArtwork.imageHost(picture));
        assertFalse(AmPage.artworkHost(picture));
        try { NcmProtocol.validateApi(picture); fail("picture CDN must not receive API credentials"); }
        catch (IllegalArgumentException expected) {}
        try { NcmProtocol.validateVideo(picture); fail("picture is not a motion-video source"); }
        catch (IllegalArgumentException expected) {}
    }
}
