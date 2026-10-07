package io.github.andrealtb.artwork.am;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Display-only provenance for immutable video files; never used to authorize or select artwork. */
final class AmVideoSources {
    enum Source {
        UNKNOWN(0, ""), AM(1, "am"), NETEASE(2, "netease"), BOTH(3, "am,netease");
        final int mask;
        final String stored;
        Source(int mask, String stored) { this.mask = mask; this.stored = stored; }
        Source merge(Source other) { return values()[mask | other.mask]; }
        static Source read(String text) {
            for (Source value : values()) if (value.stored.equals(text)) return value;
            return UNKNOWN;
        }
    }
    private static boolean videoName(String name) { return name.matches("[a-f0-9]{64}\\.mp4"); }
    private static File marker(File root, String name) { return new File(root, name + ".source"); }
    static boolean markerName(String name) { return name.matches("[a-f0-9]{64}\\.mp4\\.source"); }
    static String videoForMarker(String name) { return name.substring(0, name.length() - ".source".length()); }
    private static Source read(File root, String name) {
        File marker = marker(root, name);
        try {
            return marker.isFile() && marker.length() <= 32
                    ? Source.read(new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8)) : Source.UNKNOWN;
        } catch (Exception ignored) { return Source.UNKNOWN; }
    }
    static synchronized void remember(File root, File video, Source source) {
        if (source == Source.UNKNOWN || !videoName(video.getName()) || !video.equals(new File(root, video.getName())) || !video.isFile()) return;
        Source old = read(root, video.getName()), next = old.merge(source);
        if (old == next) return;
        File temp = null;
        try {
            temp = File.createTempFile("source-", ".part", root);
            Files.write(temp.toPath(), next.stored.getBytes(StandardCharsets.UTF_8));
            Files.move(temp.toPath(), marker(root, video.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) { /* A display label cannot invalidate a playable asset. */ }
        finally { if (temp != null) temp.delete(); }
    }
    static void forget(File root, File video) {
        if (videoName(video.getName())) marker(root, video.getName()).delete();
    }
    /** Recover old labels from AM's inventory or known NetEase album-index keys, without network I/O. */
    static Map<String, Source> snapshot(File root, List<File> videos, Set<String> neteaseKeys) {
        Map<String, Source> found = new HashMap<>();
        for (File video : videos) found.put(video.getName(), read(root, video.getName()));
        File[] indexes = root.listFiles((folder, name) -> name.endsWith(".json"));
        if (indexes != null) for (File index : indexes) {
            if (!index.isFile() || index.length() > 16 * 1024) continue;
            try {
                JSONObject value = new JSONObject(new String(Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8));
                JSONArray inventory = value.optJSONArray("videos");
                if (inventory != null && inventory.length() <= 8) for (int i = 0; i < inventory.length(); i++) {
                    JSONObject item = inventory.optJSONObject(i);
                    if (item != null) merge(found, item.optString("file"), Source.AM);
                }
                String key = index.getName().substring(0, index.getName().length() - 5);
                if (neteaseKeys.contains(key)) merge(found, value.optString("file"), Source.NETEASE);
            } catch (Exception ignored) { /* Incomplete/expired indexes are not grounds to guess a source. */ }
        }
        for (File video : videos) remember(root, video, found.get(video.getName()));
        return Map.copyOf(found);
    }
    private static void merge(Map<String, Source> found, String name, Source source) {
        if (found.containsKey(name)) found.put(name, found.get(name).merge(source));
    }
    private AmVideoSources() {}
}
