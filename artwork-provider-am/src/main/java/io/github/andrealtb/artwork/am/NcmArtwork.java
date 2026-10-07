package io.github.andrealtb.artwork.am;

import java.net.URI;
import java.util.Locale;

/** Public album pictures for provider UI only; unrelated to authenticated motion-cover requests. */
final class NcmArtwork {
    static String thumbnailUrl(String value, int px) {
        if (value == null || value.isBlank() || value.length() > 2048) return "";
        try {
            URI uri = URI.create(value.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || !imageAddress(uri)) return "";
            int size = Math.max(64, Math.min(512, px));
            return "https://" + uri.getHost().toLowerCase(Locale.ROOT) + uri.getRawPath() + "?param=" + size + "y" + size;
        } catch (IllegalArgumentException invalid) { return ""; }
    }
    static boolean imageHost(URI uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && imageAddress(uri);
    }
    private static boolean imageAddress(URI uri) {
        String host = uri.getHost(), path = uri.getRawPath();
        return host != null && host.toLowerCase(Locale.ROOT).matches("p[0-9]+\\.music\\.126\\.net")
                && uri.getUserInfo() == null && uri.getPort() == -1 && uri.getFragment() == null
                && path != null && path.startsWith("/") && path.length() > 1;
    }
    private NcmArtwork() {}
}
