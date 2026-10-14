package io.github.andrealtb.artwork.am;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/**
 * The properties the identity fold must hold for the names the stores actually use, rather than one
 * assertion per album.
 *
 * <p>The first two are the ones a missing rule broke. Two albums by one artist must never share a
 * key, because a shared key makes one binding, one snapshot and one recent record for both. The
 * spellings a store may legitimately use for one album must always share one key, because otherwise
 * a query cannot find what it already cached. The third keeps "was this field filled in"
 * independent of what the fold makes of the text, which is what let an album named "=" read as an
 * unfilled field and refused to bind.
 */
public class AmNameIdentityTest {
    /** Genuinely different works. A distinct key is what keeps them apart in bindings and caches. */
    private static final List<String> DIFFERENT = List.of(
            "MAYHEM", "MAYHEM (Deluxe)",
            "The Life of a Showgirl", "The Life of a Showgirl: The Encore",
            "1989", "1989 (Deluxe Edition)", "1989 (Taylor's Version)", "reputation",
            "\u6674\u5929", "\u6674\u5929 (Live)", "\u8449\u60e0\u7f8e",
            "=", "-", "+", "\u00f7", "\u00d7", "?", "\u2605",
            "= (Tour Edition)", "\u00f7 (Deluxe)", "- (Deluxe)", "+ (Deluxe)", "\u00d7 (Tour Edition)");

    /** Spellings one store may use for one album. A shared key is what lets a query find it. */
    private static final List<List<String>> VARIANTS = List.of(
            List.of("MAYHEM", "Mayhem", "MAYHEM ", "mayhem"),
            List.of("1989 [Deluxe]", "1989 (deluxe)", "1989-Deluxe", "1989 (DELUXE)", "1989 deluxe"),
            List.of("\u8449\u60e0\u7f8e", "\u53f6\u60e0\u7f8e"),
            List.of("\u5468\u6770\u502b\u300a\u4e03\u91cc\u9999\u300b", "\u5468\u6770\u4f26 \u4e03\u91cc\u9999"),
            List.of("A - Deluxe", "A (Deluxe)", "A [Deluxe]"),
            List.of("= (Tour Edition)", "=  (Tour Edition)", "= (Tour Edition) "),
            List.of("\u00f7 (Deluxe)", "\u00f7  (Deluxe)"));

    @Test public void twoAlbumsNeverShareAKey() {
        Map<String, String> seen = new LinkedHashMap<>();
        for (String name : DIFFERENT) {
            String key = AmIdentity.normalize(name);
            assertFalse(name + " folds to nothing", key.isEmpty());
            String previous = seen.put(key, name);
            assertNull(previous + " and " + name + " both fold to " + key, previous);
        }
    }

    @Test public void oneAlbumIsOneKey() {
        for (List<String> group : VARIANTS) {
            String key = AmIdentity.normalize(group.get(0));
            assertFalse(group.toString() + " folds to nothing", key.isEmpty());
            for (String spelling : group) assertEquals(spelling, key, AmIdentity.normalize(spelling));
        }
    }

    @Test public void presenceIsDecidedByTheRawTextNeverByAFold() {
        for (String filled : List.of("=", "-", "\u00f7 (Deluxe)", "MAYHEM", "  =  ")) {
            assertTrue(filled, AmIdentity.present(filled));
        }
        for (String empty : List.of("", "   ")) assertFalse("'" + empty + "'", AmIdentity.present(empty));
        assertFalse(AmIdentity.present(null));
        // The field the user filled in stays filled whatever the fold makes of the text.
        assertEquals("=", AmIdentity.normalize("="));
        assertTrue(AmIdentity.present("="));
    }

    /**
     * The two consumers that were actually damaged, not just the fold: a binding looked up by local
     * album name, and the album snapshot the resolver keys by that name. Ed Sheeran's "\u00f7 (Deluxe)"
     * and "- (Deluxe)" are two albums, so binding one must never answer for the other.
     */
    @Test public void symbolAlbumsDoNotAnswerForEachOther() throws Exception {
        var division = new AmBindings.Binding("\u00f7 (Deluxe)", "Ed Sheeran", "us", "11", "\u00f7 (Deluxe)", "Ed Sheeran");
        var minus = new AmBindings.Binding("- (Deluxe)", "Ed Sheeran", "us", "12", "- (Deluxe)", "Ed Sheeran");
        var bindings = List.of(division, minus);
        assertEquals("11", AmBindings.find(bindings, symbolQuery("\u00f7 (Deluxe)")).albumId());
        assertEquals("12", AmBindings.find(bindings, symbolQuery("- (Deluxe)")).albumId());
        assertNull("an unrelated symbol album must not inherit a binding", AmBindings.find(bindings, symbolQuery("+ (Deluxe)")));
        assertNull(AmBindings.find(bindings, symbolQuery("=")));
        // Rebinding one symbol album replaces only that one; the other keeps its own entry.
        var rebound = AmBindings.put(bindings, new AmBindings.Binding("\u00f7 (Deluxe)", "Ed Sheeran", "us", "14", "\u00f7 (Deluxe)", "Ed Sheeran"));
        assertEquals(List.of("14", "12"), rebound.stream().map(AmBindings.Binding::albumId).toList());
        // A third symbol album is a third binding, not a replacement of either.
        assertEquals(3, AmBindings.put(bindings, new AmBindings.Binding("\u00d7 (Deluxe)", "", "us", "13", "\u00d7 (Deluxe)", "Ed Sheeran")).size());
        // Releasing one removes only that one.
        assertEquals(List.of("12"), AmBindings.remove(bindings, division).stream().map(AmBindings.Binding::albumId).toList());
    }

    /** The album name never merges two symbol albums, and the release-type fold still applies. */
    @Test public void symbolAlbumNamesCompareAsThemselves() {
        assertFalse(AmIdentity.albumAgrees("\u00f7 (Deluxe)", "- (Deluxe)", AmEdition.Info.UNKNOWN, AmIdentity.MatchProfile.STANDARD));
        assertFalse(AmIdentity.albumAgrees("\u00f7 (Deluxe)", "- (Deluxe)", AmEdition.Info.UNKNOWN, AmIdentity.MatchProfile.LOOSE));
        assertFalse(AmIdentity.albumAgrees("=", "+", AmEdition.Info.UNKNOWN, AmIdentity.MatchProfile.LOOSE));
        assertTrue(AmIdentity.albumAgrees("\u00f7 (Deluxe)", "\u00f7  (Deluxe)", AmEdition.Info.UNKNOWN, AmIdentity.MatchProfile.STRICT));
        // An ordinary album keeps the tolerant fold it always had.
        assertTrue(AmIdentity.albumAgrees("1989 (Deluxe)", "1989 [Deluxe]", AmEdition.Info.UNKNOWN, AmIdentity.MatchProfile.STANDARD));
    }

    private static io.github.andrealtb.artwork.contract.ArtworkQuery symbolQuery(String album) {
        return new io.github.andrealtb.artwork.contract.ArtworkQuery("Shivers", "Ed Sheeran", album, 207853, "", 288, 288, 1080, 1080, 20 * 1024 * 1024);
    }

    /** Every album name in the recorded platform fixtures still folds to a key the caches can hold. */
    @Test public void recordedCorpusNeverFoldsToAnEmptyKey() throws Exception {
        String json;
        try (var stream = AmNameIdentityTest.class.getResourceAsStream("/matching-platform-public.json")) {
            assertNotNull(stream);
            json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var cases = new JSONObject(json).getJSONArray("cases");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < cases.length(); i++) {
            var item = cases.getJSONObject(i);
            String album = item.getJSONObject("query").optString("album");
            if (!album.isEmpty()) names.add(album);
            var candidates = item.getJSONArray("candidates");
            for (int c = 0; c < candidates.length(); c++) {
                String name = candidates.getJSONObject(c).optString("collectionName");
                if (!name.isEmpty()) names.add(name);
            }
        }
        assertFalse(names.isEmpty());
        for (String name : names) assertFalse(name, AmIdentity.normalize(name).isEmpty());
    }
}
