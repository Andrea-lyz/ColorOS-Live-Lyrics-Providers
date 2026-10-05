package io.github.andrealtb.artwork.am;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.andrealtb.artwork.contract.ArtworkQuery;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

/** Only unencrypted finite single-file byte-range VOD can become a v1 local asset. */
final class AmHls {
    static final int ARTWORK_SIZE = 1080;
    record Variant(URI uri, int width, int height, String codec, double fps, long bitrate, String id) {}
    record FilePlan(URI uri, long bytes, double durationSeconds) {}
    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)");
    static Map<String, String> attributes(String line) {
        Map<String, String> values = new HashMap<>();
        Matcher matcher = ATTRIBUTE.matcher(line);
        while (matcher.find()) {
            String value = matcher.group(2);
            if (value.startsWith("\"")) value = value.substring(1, value.length() - 1);
            values.put(matcher.group(1), value);
        }
        return values;
    }
    static List<Variant> variants(URI master, String text, ArtworkQuery query) throws AmFailure {
        try {
            if (!text.startsWith("#EXTM3U")) throw new IllegalArgumentException();
            List<Variant> variants = new ArrayList<>();
            Map<String, String> pending = null;
            for (String raw : text.split("\\r?\\n")) {
                String line = raw.trim();
                if (line.startsWith("#EXT-X-STREAM-INF:")) pending = attributes(line.substring(line.indexOf(':') + 1));
                else if (!line.isEmpty() && !line.startsWith("#") && pending != null) {
                    String[] size = pending.getOrDefault("RESOLUTION", "").split("x");
                    if (size.length != 2) throw new IllegalArgumentException();
                    int width = Integer.parseInt(size[0]), height = Integer.parseInt(size[1]);
                    String codec = pending.getOrDefault("CODECS", "");
                    double fps = Double.parseDouble(pending.getOrDefault("FRAME-RATE", "0"));
                    long bitrate = Long.parseLong(pending.getOrDefault("AVERAGE-BANDWIDTH", pending.getOrDefault("BANDWIDTH", "0")));
                    // Initial production path chooses native AVC SDR; HEVC profile capability is not in v1.
                    if (codec.matches("avc1\\.[a-zA-Z0-9]+") && "SDR".equals(pending.get("VIDEO-RANGE"))
                            && width == ARTWORK_SIZE && height == ARTWORK_SIZE && width <= query.maxWidth && height <= query.maxHeight
                            && Double.isFinite(fps) && fps > 0 && fps <= 60 && bitrate > 0) {
                        variants.add(new Variant(mediaUri(master.resolve(line)), width, height, codec, fps, bitrate,
                                pending.getOrDefault("STABLE-VARIANT-ID", "")));
                    }
                    pending = null;
                }
            }
            // Both surfaces share native 1080 AVC. Never fetch or upscale a separate small rendition.
            variants.sort(Comparator.comparingLong(Variant::bitrate));
            if (variants.isEmpty()) throw new AmFailure(Status.UNSUPPORTED, "no_1080_avc_variant");
            if (variants.size() > 64) throw new IllegalArgumentException();
            return variants;
        } catch (RuntimeException error) { throw new AmFailure(Status.UNSUPPORTED, "invalid_hls_master"); }
    }

    static FilePlan filePlan(URI child, String text, long limit) throws AmFailure {
        String step = "header";
        int lineNumber = 0;
        long next = 0;
        double duration = 0;
        int count = 0;
        try {
            step = "missing_extm3u";
            if (!text.startsWith("#EXTM3U")) throw new IllegalArgumentException();
            step = "missing_vod_marker";
            if (!text.contains("#EXT-X-PLAYLIST-TYPE:VOD")) throw new IllegalArgumentException();
            step = "missing_endlist";
            if (!text.contains("#EXT-X-ENDLIST")) throw new IllegalArgumentException();
            URI file = null;
            String range = null;
            boolean mapped = false, pendingDuration = false;
            for (String raw : text.split("\\r?\\n")) {
                lineNumber++;
                String line = raw.trim();
                step = "forbidden_tag";
                if (line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:")
                        || line.startsWith("#EXT-X-DISCONTINUITY") || line.startsWith("#EXT-X-GAP")
                        || line.startsWith("#EXT-X-STREAM-INF")) throw new IllegalArgumentException();
                if (line.startsWith("#EXT-X-MAP:")) {
                    step = "duplicate_or_late_map";
                    if (mapped || count != 0) throw new IllegalArgumentException();
                    Map<String, String> map = attributes(line.substring(line.indexOf(':') + 1));
                    step = "map_uri";
                    file = mediaUri(child.resolve(map.get("URI")));
                    step = "map_range";
                    next = rangeEnd(map.get("BYTERANGE"), 0);
                    mapped = true;
                } else if (line.startsWith("#EXTINF:")) {
                    step = "duplicate_duration";
                    if (pendingDuration) throw new IllegalArgumentException();
                    step = "invalid_duration";
                    double seconds = Double.parseDouble(line.substring(8).split(",", 2)[0]);
                    if (!Double.isFinite(seconds) || seconds <= 0) throw new IllegalArgumentException();
                    duration += seconds;
                    pendingDuration = true;
                } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                    step = "duplicate_range";
                    if (range != null) throw new IllegalArgumentException();
                    range = line.substring(17);
                } else if (!line.isEmpty() && !line.startsWith("#")) {
                    step = "segment_missing_map_duration_or_range";
                    if (!mapped || !pendingDuration || range == null) throw new IllegalArgumentException();
                    step = "segment_uri_or_multiple_files";
                    if (!file.equals(mediaUri(child.resolve(line)))) throw new IllegalArgumentException();
                    step = "segment_range_noncontiguous_or_invalid";
                    next = rangeEnd(range, next);
                    step = "file_byte_budget";
                    if (next > limit) throw new IllegalArgumentException();
                    step = "segment_count_budget";
                    if (++count > 1000) throw new IllegalArgumentException();
                    pendingDuration = false;
                    range = null;
                }
            }
            step = "missing_map_or_segments";
            if (!mapped || count == 0) throw new IllegalArgumentException();
            step = "unfinished_segment";
            if (range != null || pendingDuration) throw new IllegalArgumentException();
            step = "duration_budget";
            if (duration > 120) throw new IllegalArgumentException();
            step = "file_byte_budget";
            if (next > limit) throw new IllegalArgumentException();
            return new FilePlan(file, next, duration);
        } catch (RuntimeException error) {
            throw new AmFailure(Status.UNSUPPORTED, step.equals("file_byte_budget") ? "source_file_too_large" : "unsupported_hls_layout", 0,
                    step + " line=" + lineNumber + " segments=" + count + " bytes=" + next
                            + " maxBytes=" + limit + " durationSeconds=" + duration
                            + " exception=" + error.getClass().getSimpleName());
        }
    }
    private static long rangeEnd(String range, long expected) {
        String[] parts = range.split("@", -1);
        if (parts.length > 2) throw new IllegalArgumentException();
        long size = Long.parseLong(parts[0]), start = parts.length == 2 ? Long.parseLong(parts[1]) : expected;
        if (size <= 0 || start != expected || size > Long.MAX_VALUE - start) throw new IllegalArgumentException();
        return start + size;
    }
    static URI mediaUri(URI uri) {
        if (!"https".equals(uri.getScheme()) || !"mvod.itunes.apple.com".equals(uri.getHost())
                || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null) throw new IllegalArgumentException();
        return uri;
    }
}
