package io.github.andrealtb.artwork.am;

import java.util.Set;
import java.util.function.IntPredicate;
import io.github.andrealtb.artwork.contract.ArtworkContract;

/**
 * What a user's own video must be to become a lock-screen cover, and how it is made so.
 *
 * <p>The output is what AM already delivers: a square H.264 MP4, video only, starting on a key frame
 * at zero, no larger than 1080 on a side. A video that already is one keeps its original picture and
 * only loses its other tracks; anything else is decoded, centre-cropped to a square, scaled down and
 * encoded again. Nothing is ever scaled up.
 */
final class AmLocalPolicy {
    /** The side AM's own motion covers use; the contract allows up to 1280 but 1080 is the look users know. */
    static final int MAX_SIDE = 1080;
    /** Below this a cover is visibly blurred on any lock screen; ask for a better source instead. */
    static final int MIN_SIDE = 320;
    static final long MAX_DURATION_MS = ArtworkContract.MAX_VIDEO_DURATION_MS;
    /** Container durations round; a clip this close to the limit is trimmed to it rather than refused. */
    static final long DURATION_TOLERANCE_MS = 1_000;
    static final long MIN_DURATION_MS = 1_000;
    static final long MAX_BYTES = ArtworkContract.MAX_FILE_BYTES;
    /** Encoders overshoot their target; aim below the hard limit. */
    static final long TARGET_BYTES = 18L * 1024 * 1024;
    /** Frame rate a re-encoded cover keeps at most; an original may keep up to the contract's 60. */
    static final int MAX_FPS = 30;
    static final double MAX_ORIGINAL_FPS = 60;
    /** Baseline, Main, High and their constrained forms: what every Android AVC decoder plays. */
    private static final Set<Integer> PLAIN_AVC_PROFILES = Set.of(0, 0x01, 0x02, 0x08, 0x10000, 0x80000);
    private static final int TRANSFER_ST2084 = 6, TRANSFER_HLG = 7;

    /**
     * What the extractor says about the chosen video track.
     *
     * @param rotation clockwise degrees the stored frame turns for display
     * @param durationMs 0 when the container does not say
     * @param fps 0 when the container does not say
     * @param profile the codec profile constant, 0 when absent
     * @param transfer the colour transfer constant, 0 when absent
     */
    record Probe(String mime, int width, int height, int rotation, long durationMs, double fps,
            int profile, int transfer) {
        int displayWidth() { return rotation % 180 == 0 ? width : height; }
        int displayHeight() { return rotation % 180 == 0 ? height : width; }
        boolean hdr() { return transfer == TRANSFER_ST2084 || transfer == TRANSFER_HLG; }
        /** The contract's own test: sides within 2% of each other. */
        boolean square() {
            return Math.abs((long) width - height) * 100 <= Math.max(width, height) * 2L;
        }
    }

    private AmLocalPolicy() {}

    /** Why this video cannot become a cover at all, or null when it can. */
    static String rejection(Probe probe) {
        if (probe.mime() == null || !probe.mime().startsWith("video/")) return "local_not_video";
        if (probe.width() <= 0 || probe.height() <= 0) return "local_not_video";
        if (probe.rotation() % 90 != 0) return "local_not_video";
        if (probe.durationMs() > MAX_DURATION_MS + DURATION_TOLERANCE_MS) return "local_too_long";
        if (probe.durationMs() > 0 && probe.durationMs() < MIN_DURATION_MS) return "local_too_short";
        if (Math.min(probe.width(), probe.height()) < MIN_SIDE) return "local_too_small";
        return null;
    }

    /**
     * Already a cover: keep the original picture and copy only its video samples. The copy is written
     * as MP4 whatever the source container was, so a MOV or MKV of the same picture qualifies too.
     *
     * <p>A length that is still unknown after measuring is accepted here: the copy carries a real
     * duration of its own, so it is kept only when that duration is one the lock screen accepts.
     */
    static boolean original(Probe probe) {
        return "video/avc".equals(probe.mime()) && !probe.hdr() && probe.rotation() == 0
                && probe.square() && Math.max(probe.width(), probe.height()) <= MAX_SIDE
                && (probe.durationMs() <= 0
                    || probe.durationMs() >= MIN_DURATION_MS && probe.durationMs() <= MAX_DURATION_MS)
                && probe.fps() <= MAX_ORIGINAL_FPS && PLAIN_AVC_PROFILES.contains(probe.profile());
    }

    /** True when the centre crop removes part of the picture, so the user can be told. */
    static boolean cropped(Probe probe) { return !probe.square(); }

    /**
     * The square side to encode: the source's shorter side, never above {@link #MAX_SIDE}, rounded down
     * to the encoder's alignment and stepped down until the encoder accepts it. Zero when none fits.
     */
    static int side(int sourceSide, int alignment, IntPredicate supported) {
        int step = Math.max(2, alignment + alignment % 2);
        int side = Math.min(MAX_SIDE, sourceSide);
        side -= side % step;
        while (side >= MIN_SIDE && !supported.test(side)) side -= step;
        return side >= MIN_SIDE ? side : 0;
    }

    /** The frame rate the encoder is told; a rate the source does not state is taken as 30. */
    static int frameRate(double sourceFps) {
        if (!(sourceFps > 0)) return MAX_FPS;
        return (int) Math.max(1, Math.min(MAX_FPS, Math.round(sourceFps)));
    }

    /**
     * About 0.1 bit per pixel, the density of AM's own covers, unless the clip is long enough that the
     * size budget allows less; an unknown duration is treated as the longest allowed.
     */
    static int bitrate(int side, int fps, long durationMs) {
        long quality = Math.round((double) side * side * fps * 0.1);
        long millis = durationMs <= 0 ? MAX_DURATION_MS : Math.min(durationMs, MAX_DURATION_MS);
        double seconds = Math.max(1, millis / 1000.0);
        long budget = Math.round(TARGET_BYTES * 8 * 0.92 / seconds);
        return (int) Math.max(300_000, Math.min(quality, budget));
    }

    /**
     * Drops frames to stay at {@link #MAX_FPS}: a frame is kept when it is at least most of one output
     * interval after the last kept one, so 29.97 keeps every frame and 60 keeps every other.
     */
    static boolean keep(long ptsUs, long lastKeptUs) {
        if (lastKeptUs == Long.MIN_VALUE) return true;
        long interval = 1_000_000L / MAX_FPS;
        return ptsUs - lastKeptUs >= interval - interval / 8;
    }

    /**
     * Column-major 4x4 texture transform from the output square to the stored frame, both in [0,1]
     * with y up: centre-crop the displayed picture to a square, then undo the display rotation so the
     * crop lands on the right pixels of the frame as the decoder stores it.
     */
    static float[] crop(int width, int height, int rotation) {
        int displayWidth = rotation % 180 == 0 ? width : height, displayHeight = rotation % 180 == 0 ? height : width;
        double side = Math.min(displayWidth, displayHeight);
        double cw = side / displayWidth, ch = side / displayHeight;
        double x0 = (1 - cw) / 2, y0 = (1 - ch) / 2;
        // s = a*x + b*y + e, t = c*x + d*y + f, with (x, y) the output position.
        double a, b, c, d, e, f;
        switch (((rotation % 360) + 360) % 360) {
            case 90 -> { a = 0; b = -ch; e = 1 - y0; c = cw; d = 0; f = x0; }
            case 180 -> { a = -cw; b = 0; e = 1 - x0; c = 0; d = -ch; f = 1 - y0; }
            case 270 -> { a = 0; b = ch; e = y0; c = -cw; d = 0; f = 1 - x0; }
            default -> { a = cw; b = 0; e = x0; c = 0; d = ch; f = y0; }
        }
        return new float[] {
                (float) a, (float) c, 0, 0,
                (float) b, (float) d, 0, 0,
                0, 0, 1, 0,
                (float) e, (float) f, 0, 1 };
    }

    /** An imported cover is named by the SHA-256 of its file; nothing else may reach a file path. */
    static boolean videoId(String id) { return id != null && id.matches("[a-f0-9]{64}"); }

    /** A picked file's name as the bindings list shows it: printable, bounded, never empty. */
    static String displayName(String name) {
        String text = name == null ? "" : name.replaceAll("[\\p{Cc}\\p{Cf}]", "").trim();
        if (text.length() > 120) text = text.substring(0, 120);
        return text.isEmpty() ? "video" : text;
    }
}
