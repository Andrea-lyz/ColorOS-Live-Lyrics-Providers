package io.github.andrealtb.coloroslyrics.provider.readify;

/** A live sentence snapshot, not an estimated audiobook timeline. */
public final class SentenceSnapshot {
    public static final int MAX_CODE_POINTS = 1600;
    private SentenceSnapshot() {}

    public static String clean(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder();
        boolean space = false;
        int count = 0;
        for (int i = 0; i < text.length() && count < MAX_CODE_POINTS;) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                space = out.length() > 0;
                continue;
            }
            if (Character.isISOControl(cp)) continue;
            if (space) out.append(' ');
            space = false;
            // Prevent book text from being interpreted as extra LRC timing tags.
            if (cp == '[') cp = '［';
            if (cp == ']') cp = '］';
            if (cp == '<') cp = '〈';
            if (cp == '>') cp = '〉';
            out.appendCodePoint(cp);
            count++;
        }
        return out.toString();
    }

    public static String lrc(String cleanText) {
        return cleanText.isEmpty() ? "" : "[00:00.000]" + cleanText + "\n";
    }
}
