package io.github.andrealtb.artwork.am;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/** Character folding only; version labels and unknown/ambiguous script mappings survive. */
final class AmMatchText {
    private static final Map<Integer, Integer> HANZI = load();

    static String normalize(String value) {
        String text = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC);
        StringBuilder folded = new StringBuilder(text.length());
        text.codePoints().forEach(code -> folded.appendCodePoint(HANZI.getOrDefault(code, code)));
        return folded.toString().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static Map<Integer, Integer> load() {
        Map<Integer, Integer> values = new HashMap<>();
        try (var stream = AmMatchText.class.getResourceAsStream("hanzi-t2s.tsv")) {
            if (stream == null) return Map.of();
            try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("#") || line.isEmpty()) continue;
                    String[] pair = line.split("\t", -1);
                    if (pair.length != 2 || pair[0].codePointCount(0, pair[0].length()) != 1
                            || pair[1].codePointCount(0, pair[1].length()) != 1) return Map.of();
                    values.put(pair[0].codePointAt(0), pair[1].codePointAt(0));
                }
            }
            return Collections.unmodifiableMap(values);
        } catch (java.io.IOException error) {
            // Missing conversion data narrows matching; it must never turn names into empty strings.
            return Map.of();
        }
    }
    private AmMatchText() {}
}
