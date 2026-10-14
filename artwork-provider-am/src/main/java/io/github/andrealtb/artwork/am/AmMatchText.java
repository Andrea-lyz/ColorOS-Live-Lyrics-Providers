package io.github.andrealtb.artwork.am;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The final step of every name read, and its output is a canonical identity: what a binding, a
 * snapshot and a decision cache are keyed by.
 *
 * <p>It is deliberately narrower than the tolerant comparisons built on top of it. A key that erases
 * a distinction makes two albums share one binding, one cache entry and one recent record, so this
 * fold removes only what separates words and never a symbol that names a work. Reading a name
 * through the labels its store attaches happens before this step, in the named folds on
 * {@link AmIdentity}; this class decides only how the characters left over compare.
 */
final class AmMatchText {
    private static final Map<Integer, Integer> HANZI = load();
    /** Word separators: everything that is not a letter or digit. */
    private static final Pattern WORD_SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}]+");
    /** Symbols and punctuation, without the space-like and invisible code points. */
    private static final Pattern SYMBOL_ONLY = Pattern.compile("[^\\p{P}\\p{S}]+");
    /** Brackets and quotes that may only wrap a symbol-only name: "(=)" names "=", "( )" does not. */
    private static final String OPENERS = "([{<‘“«《「〈【";
    private static final String CLOSERS = ")]}>’”»》」〉】";
    private static final Pattern LETTER_OR_DIGIT = Pattern.compile("[\\p{L}\\p{N}]");

    /**
     * The canonical identity of a name: NFKC first, then traditional-to-simplified Hanzi, then case
     * folding, then every character that is not a letter or a digit turned into a word break.
     *
     * <p>Two cases need a rule of their own, and both exist because a name that folds away stops
     * being a name: a binding for it could never be validated or found, and its snapshot could never
     * be keyed.
     *
     * <p>A name that carries no letter and no digit anywhere, such as Ed Sheeran's "=", keeps its
     * symbols: "=", "÷", "+", "×", "-" and "?" stay six different names.
     *
     * <p>A name that opens with a symbol-only token keeps that token, which is the one position where
     * a symbol can name the work. This is what keeps "÷ (Deluxe)" and "- (Deluxe)" apart, where both
     * previously became "deluxe". A name that opens with a letter or a digit folds exactly as before,
     * so "1989 (Deluxe)" is still "1989 deluxe" and "A - Deluxe" is still "a deluxe".
     */
    static String normalize(String value) {
        String text = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC);
        StringBuilder folded = new StringBuilder(text.length());
        text.codePoints().forEach(code -> folded.appendCodePoint(HANZI.getOrDefault(code, code)));
        String lower = folded.toString().toLowerCase(Locale.ROOT);
        String words = WORD_SEPARATOR.matcher(lower).replaceAll(" ").trim();
        // Nothing but symbols: a symbol-titled work, not an absent field.
        if (words.isEmpty()) return unwrap(SYMBOL_ONLY.matcher(lower).replaceAll(""));
        String head = leadingSymbols(lower);
        return head.isEmpty() ? words : head + " " + words;
    }

    /**
     * The symbol-only token this name opens with, or "" when it opens with a letter or a digit.
     * Only the first whitespace-delimited token is read, and only whole: an apostrophe or an ellipsis
     * attached to a word is the store's own typography, so "'Round Midnight" opens with a word.
     */
    private static String leadingSymbols(String lower) {
        int end = 0;
        while (end < lower.length() && !Character.isWhitespace(lower.charAt(end))) end++;
        // A single token means the whole name was read above, and a leading bracket only opens a word.
        if (end == 0 || end >= lower.length()) return "";
        String head = lower.substring(0, end);
        int opener = 0;
        while (opener < head.length() && OPENERS.indexOf(head.charAt(opener)) < 0) opener++;
        if (opener > 0) head = head.substring(0, opener);
        return head.isEmpty() || LETTER_OR_DIGIT.matcher(head).find() ? "" : head;
    }

    /** Drops bracket pairs that only enclose a symbol-only name; a paired name such as "()" stays. */
    private static String unwrap(String symbols) {
        String value = symbols;
        while (value.length() > 2
                && OPENERS.indexOf(value.charAt(0)) == CLOSERS.indexOf(value.charAt(value.length() - 1))
                && OPENERS.indexOf(value.charAt(0)) >= 0) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
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
