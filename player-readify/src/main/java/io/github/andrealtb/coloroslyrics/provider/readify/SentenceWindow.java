package io.github.andrealtb.coloroslyrics.provider.readify;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Immutable, bounded snapshot. LRC slots are ordering tokens, never audio timestamps. */
public final class SentenceWindow {
    public final List<String> lines;
    public final int current;

    private SentenceWindow(List<String> lines, int current) {
        this.lines = java.util.Collections.unmodifiableList(new ArrayList<>(lines));
        this.current = current;
    }

    public static SentenceWindow select(List<String> ids, List<String> texts, String id, String text) {
        int center = ids.indexOf(id);
        ArrayList<String> result = new ArrayList<>();
        int active = 0;
        if (center >= 0 && ids.size() == texts.size()) {
            for (int i = Math.max(0, center - 2); i <= Math.min(texts.size() - 1, center + 2); i++) {
                String clean = SentenceSnapshot.clean(i == center ? text : texts.get(i));
                if (i == center) active = result.size();
                if (!clean.isEmpty()) result.add(clean);
            }
        }
        if (result.isEmpty() || SentenceSnapshot.clean(text).isEmpty()) {
            result.clear();
            result.add(SentenceSnapshot.clean(text));
            active = 0;
        }
        return new SentenceWindow(result, active);
    }

    public String lrc() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            out.append(String.format(Locale.ROOT, "[00:%02d.000]", i)).append(lines.get(i)).append('\n');
        }
        return out.toString();
    }
}
