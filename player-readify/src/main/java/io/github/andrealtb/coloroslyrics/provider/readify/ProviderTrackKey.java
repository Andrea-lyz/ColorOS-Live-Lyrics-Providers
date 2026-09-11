package io.github.andrealtb.coloroslyrics.provider.readify;

import java.util.Locale;

/** Wire identity used by official provider-core and Bridge's strict track matcher. */
public final class ProviderTrackKey {
    private ProviderTrackKey() {}

    public static String build(String id, String title, String artist, long durationMs) {
        String cleanId = id == null ? "" : id.trim();
        if (cleanId.isEmpty()) cleanId = "noid";
        return cleanId + "|" + normalize(title) + "|" + normalize(artist)
                + "|" + (durationMs > 0 ? durationMs / 1000 : 0);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
