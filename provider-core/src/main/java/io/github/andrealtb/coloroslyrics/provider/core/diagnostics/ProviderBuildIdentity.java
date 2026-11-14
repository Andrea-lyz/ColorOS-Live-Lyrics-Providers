package io.github.andrealtb.coloroslyrics.provider.core.diagnostics;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Properties;

/** Reads the identity resource from the module's own class loader, not the player's PackageInfo. */
public final class ProviderBuildIdentity {
    private static volatile String cached;
    private ProviderBuildIdentity() { }

    public static String describe(Class<?> moduleAnchor) {
        String value = cached;
        if (value != null) return value;
        synchronized (ProviderBuildIdentity.class) {
            if (cached != null) return cached;
            try (InputStream input = moduleAnchor.getClassLoader()
                    .getResourceAsStream("META-INF/cll/build-identity.properties")) {
                if (input == null) return "unknown";
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    if (bytes.size() + n > 8192) return "unknown";
                    bytes.write(buffer, 0, n);
                }
                cached = parse(bytes.toByteArray());
                return cached;
            } catch (Exception ignored) { return "unknown"; }
        }
    }

    static String parse(byte[] bytes) {
        try {
            if (bytes.length > 8192) return "unknown";
            Properties properties = new Properties();
            properties.load(new ByteArrayInputStream(bytes));
            if (!"1".equals(properties.getProperty("schema"))) return "unknown";
            String digest = properties.getProperty("sourceDigest", "");
            String id = properties.getProperty("buildId", "");
            String state = properties.getProperty("sourceState", "");
            if (!digest.matches("[0-9a-f]{64}") || id.length() > 1024 || id.isEmpty()
                    || id.indexOf('\n') >= 0 || id.indexOf('\r') >= 0
                    || !(state.equals("clean") || state.equals("dirty") || state.equals("unknown"))) return "unknown";
            return id;
        } catch (Exception ignored) { return "unknown"; }
    }
}
