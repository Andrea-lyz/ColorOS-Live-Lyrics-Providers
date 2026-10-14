package io.github.andrealtb.artwork.am;

import org.junit.Test;
import static org.junit.Assert.*;

/** What an imported video must be, and the arithmetic that makes one a cover. No codec runs here. */
public class AmLocalPolicyTest {
    private static final int HIGH = 0x08, HIGH_10 = 0x10, HLG = 7;

    private static AmLocalPolicy.Probe avc(int width, int height, int rotation, long durationMs, double fps) {
        return new AmLocalPolicy.Probe("video/avc", width, height, rotation, durationMs, fps, HIGH, 0);
    }

    @Test public void onlyVideosACoverCanBeMadeFromAreAccepted() {
        assertNull(AmLocalPolicy.rejection(avc(1920, 1080, 0, 30_000, 30)));
        assertEquals("local_not_video", AmLocalPolicy.rejection(
                new AmLocalPolicy.Probe("audio/mp4a-latm", 0, 0, 0, 30_000, 0, 0, 0)));
        assertEquals("local_not_video", AmLocalPolicy.rejection(avc(0, 1080, 0, 30_000, 30)));
        assertEquals("local_too_long", AmLocalPolicy.rejection(avc(1080, 1080, 0, 125_000, 30)));
        // A container that rounds just past the limit is trimmed to it, not refused.
        assertNull(AmLocalPolicy.rejection(avc(1080, 1080, 0, 120_800, 30)));
        assertEquals("local_too_short", AmLocalPolicy.rejection(avc(1080, 1080, 0, 400, 30)));
        // A container that does not state its length is limited while encoding instead.
        assertNull(AmLocalPolicy.rejection(avc(1080, 1080, 0, 0, 0)));
        assertEquals("local_too_small", AmLocalPolicy.rejection(avc(300, 300, 0, 30_000, 30)));
        assertNull("the shorter side decides", AmLocalPolicy.rejection(avc(1920, 320, 0, 30_000, 30)));
    }

    @Test public void aVideoThatIsAlreadyACoverKeepsItsOriginalPicture() {
        assertTrue("AM's own shape", AmLocalPolicy.original(avc(1080, 1080, 0, 30_000, 30)));
        assertTrue("within the contract's 2%", AmLocalPolicy.original(avc(1080, 1070, 0, 30_000, 60)));
        assertTrue(AmLocalPolicy.original(avc(720, 720, 0, 8_000, 0)));
        assertFalse("larger than 1080", AmLocalPolicy.original(avc(1280, 1280, 0, 30_000, 30)));
        assertFalse("not square", AmLocalPolicy.original(avc(1920, 1080, 0, 30_000, 30)));
        assertFalse("rotated for display", AmLocalPolicy.original(avc(1080, 1080, 90, 30_000, 30)));
        assertFalse("above the contract's frame rate", AmLocalPolicy.original(avc(1080, 1080, 0, 30_000, 120)));
        // A fragmented MP4 with a zeroed mvhd and no mehd states no length anywhere. Copying it is
        // still right: the copy carries its own duration, and only an acceptable one is kept.
        assertTrue("no stated length", AmLocalPolicy.original(avc(1080, 1080, 0, 0, 30)));
        assertTrue("no stated length or frame rate", AmLocalPolicy.original(avc(1080, 1080, 0, 0, 0)));
        assertTrue("no stated length, slightly over the contract's 2%", AmLocalPolicy.original(avc(1080, 1070, 0, 0, 30)));
        assertFalse("longer than the contract", AmLocalPolicy.original(avc(1080, 1080, 0, 120_500, 30)));
        assertFalse("HEVC is re-encoded to the H.264 every lock screen plays",
                AmLocalPolicy.original(new AmLocalPolicy.Probe("video/hevc", 1080, 1080, 0, 30_000, 30, 1, 0)));
        assertFalse("10-bit AVC", AmLocalPolicy.original(new AmLocalPolicy.Probe("video/avc", 1080, 1080, 0, 30_000, 30, HIGH_10, 0)));
        assertFalse("HDR", AmLocalPolicy.original(new AmLocalPolicy.Probe("video/avc", 1080, 1080, 0, 30_000, 30, HIGH, HLG)));
        assertTrue(AmLocalPolicy.cropped(avc(1920, 1080, 0, 30_000, 30)));
        assertFalse(AmLocalPolicy.cropped(avc(1080, 1080, 90, 30_000, 30)));
    }

    @Test public void theSquareIsNeverLargerThanTheSourceOr1080AndFitsTheEncoder() {
        assertEquals(1080, AmLocalPolicy.side(2160, 2, side -> true));
        assertEquals(1072, AmLocalPolicy.side(1080, 16, side -> true));
        assertEquals(720, AmLocalPolicy.side(720, 2, side -> true));
        assertEquals("even, never scaled up", 718, AmLocalPolicy.side(719, 1, side -> true));
        assertEquals(960, AmLocalPolicy.side(2160, 16, side -> side <= 960));
        assertEquals(0, AmLocalPolicy.side(300, 2, side -> true));
        assertEquals(0, AmLocalPolicy.side(2160, 2, side -> false));
    }

    @Test public void rateAndFramesStayInsideTheBudget() {
        assertEquals(30, AmLocalPolicy.frameRate(0));
        assertEquals(30, AmLocalPolicy.frameRate(59.94));
        assertEquals(30, AmLocalPolicy.frameRate(29.97));
        assertEquals(24, AmLocalPolicy.frameRate(24));
        // About AM's own density for a short loop.
        assertEquals(3_499_200, AmLocalPolicy.bitrate(1080, 30, 15_000));
        for (long millis : new long[] { 0, 1_000, 15_000, 45_000, 90_000, 120_000, 121_000 }) {
            int bitrate = AmLocalPolicy.bitrate(1080, 30, millis);
            long seconds = (millis <= 0 ? AmLocalPolicy.MAX_DURATION_MS : Math.min(millis, AmLocalPolicy.MAX_DURATION_MS)) / 1000;
            assertTrue(millis + " ms fits", (long) bitrate * Math.max(1, seconds) / 8 <= AmLocalPolicy.TARGET_BYTES);
        }
        assertTrue(AmLocalPolicy.TARGET_BYTES < AmLocalPolicy.MAX_BYTES);
    }

    @Test public void frameDroppingHalvesSixtyAndKeepsEveryFrameAtThirtyOrBelow() {
        assertEquals(30, kept(1_000_000 / 60.0));
        assertEquals(30, kept(1_001_000 / 60.0));
        assertEquals(30, kept(1_001_000 / 30.0));
        assertEquals(24, kept(1_000_000 / 24.0));
        assertEquals(30, kept(1_000_000 / 120.0));
    }

    private static int kept(double intervalUs) {
        long last = Long.MIN_VALUE;
        int count = 0;
        for (int frame = 0; frame * intervalUs < 1_000_000 - 1; frame++) {
            long pts = Math.round(frame * intervalUs);
            if (AmLocalPolicy.keep(pts, last)) { last = pts; count++; }
        }
        return count;
    }

    @Test public void theCropIsTheCentreSquareOfThePictureAsDisplayed() {
        // Square, upright: identity.
        assertMaps(AmLocalPolicy.crop(1080, 1080, 0), 0, 0, 0, 0);
        assertMaps(AmLocalPolicy.crop(1080, 1080, 0), 1, 1, 1, 1);
        // Landscape: the middle 1080 columns of 1920.
        assertMaps(AmLocalPolicy.crop(1920, 1080, 0), 0, 0, 0.21875, 0);
        assertMaps(AmLocalPolicy.crop(1920, 1080, 0), 1, 1, 0.78125, 1);
        // A portrait phone recording stores 1920x1080 turned 90 degrees: display up runs along
        // the stored frame's left edge, so the output's top-left is the stored bottom edge.
        float[] portrait = AmLocalPolicy.crop(1920, 1080, 90);
        assertMaps(portrait, 0, 1, 0.21875, 0);
        assertMaps(portrait, 0, 0, 0.78125, 0);
        assertMaps(portrait, 1, 1, 0.21875, 1);
        assertMaps(portrait, 1, 0, 0.78125, 1);
        // Upside down and the other quarter turn.
        assertMaps(AmLocalPolicy.crop(1080, 1080, 180), 0, 0, 1, 1);
        assertMaps(AmLocalPolicy.crop(1080, 1080, 180), 1, 1, 0, 0);
        assertMaps(AmLocalPolicy.crop(1080, 1080, 270), 0, 0, 0, 1);
        assertMaps(AmLocalPolicy.crop(1080, 1080, 270), 1, 0, 0, 0);
    }

    @Test public void everyRotationSamplesASquareOfTheStoredPixels() {
        int width = 1920, height = 1080;
        for (int rotation : new int[] { 0, 90, 180, 270 }) {
            float[] m = AmLocalPolicy.crop(width, height, rotation);
            double minS = 1, maxS = 0, minT = 1, maxT = 0;
            for (int x = 0; x <= 1; x++) for (int y = 0; y <= 1; y++) {
                double s = m[0] * x + m[4] * y + m[12], t = m[1] * x + m[5] * y + m[13];
                assertTrue(rotation + " stays in the frame", s > -1e-6 && s < 1 + 1e-6 && t > -1e-6 && t < 1 + 1e-6);
                minS = Math.min(minS, s); maxS = Math.max(maxS, s); minT = Math.min(minT, t); maxT = Math.max(maxT, t);
            }
            assertEquals(rotation + " width", 1080, (maxS - minS) * width, 0.01);
            assertEquals(rotation + " height", 1080, (maxT - minT) * height, 0.01);
        }
    }

    private static void assertMaps(float[] m, double x, double y, double s, double t) {
        assertEquals("s(" + x + "," + y + ")", s, m[0] * x + m[4] * y + m[12], 1e-5);
        assertEquals("t(" + x + "," + y + ")", t, m[1] * x + m[5] * y + m[13], 1e-5);
    }

    @Test public void namesAndIdsAreSafeToShowAndToStore() {
        assertEquals("evil.mp4", AmLocalPolicy.displayName("‮evil.mp4"));
        assertEquals("ab.mp4", AmLocalPolicy.displayName("a\u0007b\n.mp4"));
        assertEquals("video", AmLocalPolicy.displayName("  "));
        assertEquals("video", AmLocalPolicy.displayName(null));
        assertEquals(120, AmLocalPolicy.displayName("x".repeat(400)).length());
        assertTrue(AmLocalPolicy.videoId("0123456789abcdef".repeat(4)));
        assertFalse(AmLocalPolicy.videoId("0123456789ABCDEF".repeat(4)));
        assertFalse(AmLocalPolicy.videoId("../" + "a".repeat(61)));
        assertFalse(AmLocalPolicy.videoId("a".repeat(63)));
        assertFalse(AmLocalPolicy.videoId(null));
    }
}
