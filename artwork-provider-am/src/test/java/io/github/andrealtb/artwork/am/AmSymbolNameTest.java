package io.github.andrealtb.artwork.am;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import io.github.andrealtb.artwork.contract.ArtworkQuery;

/**
 * Names that carry no letter or digit: Ed Sheeran's "=" (1581087024) and "= (Tour Edition)"
 * (1625197538) in the US storefront, 2026-10-14. Only the plain edition has a motion cover.
 */
public class AmSymbolNameTest {
    @Test public void symbolOnlyNamesKeepTheirSymbolsInsteadOfBecomingEmpty() {
        assertEquals("=", AmIdentity.normalize("="));
        assertEquals("=", AmIdentity.normalize(" ＝ "));
        assertEquals("÷", AmIdentity.normalize("÷"));
        assertEquals("+", AmIdentity.normalize("+"));
        assertEquals("×", AmIdentity.normalize("×"));
        assertEquals("?", AmIdentity.normalize("?"));
        assertEquals("!!!", AmIdentity.normalizeArtist("!!!"));
        // Whitespace between symbols never separates two names.
        assertEquals("()", AmIdentity.normalize("( )"));
        assertEquals("=", AmIdentity.normalize("（＝）"));
        // Symbol separators stay inside a symbol-only name; only whole-name brackets are dropped.
        assertEquals("..!!..", AmIdentity.normalize("..!!.."));
        assertEquals("★", AmIdentity.normalize("★"));
        assertEquals(AmIdentity.normalize("❤"), AmIdentity.normalize("❤️"));
        // Different symbols are different names, so "=" never stands in for "÷".
        assertNotEquals(AmIdentity.normalize("="), AmIdentity.normalize("÷"));
        assertNotEquals(AmIdentity.normalize("÷"), AmIdentity.normalize("-"));
        // A name that carries words is untouched, symbol or not: only the empty case gains a fallback.
        assertEquals("1989", AmIdentity.normalize("1989"));
        assertEquals("= tour edition", AmIdentity.normalize("= (Tour Edition)"));
        assertEquals("equals", AmIdentity.normalizeArtist("Equals"));
    }
    @Test public void bindingAcceptsAndFindsASymbolOnlyLocalAlbum() throws Exception {
        var binding = new AmBindings.Binding("=", "", "us", "1581087024", "=", "Ed Sheeran");
        assertTrue(AmBindings.valid(binding));
        var restored = AmBindings.decode(AmBindings.encode(List.of(binding)));
        assertEquals(1, restored.size()); assertEquals("=", restored.get(0).localAlbum());
        var query = new ArtworkQuery("Shivers", "Ed Sheeran", "=", 207853, "", 288, 288, 1080, 1080, 20 * 1024 * 1024);
        assertEquals("1581087024", AmBindings.find(restored, query).albumId());
        // The other symbol albums are not the same local album.
        assertNull(AmBindings.find(restored, new ArtworkQuery("Shivers", "Ed Sheeran", "÷", 207853, "", 288, 288, 1080, 1080, 20 * 1024 * 1024)));
        assertNull(AmBindings.find(restored, new ArtworkQuery("Shivers", "Ed Sheeran", "= (Tour Edition)", 207853, "", 288, 288, 1080, 1080, 20 * 1024 * 1024)));
    }
    @Test public void recentRecordsAndAlbumSnapshotsKeepASymbolOnlyAlbum() throws Exception {
        var entries = AmRecentAlbums.note(List.of(),
                new AmRecentAlbums.Entry("=", "Ed Sheeran", AmRecentAlbums.Outcome.EDITIONS, "Shivers"));
        assertEquals(1, entries.size());
        assertEquals(entries, AmRecentAlbums.decode(AmRecentAlbums.encode(entries)));
        var root = java.nio.file.Files.createTempDirectory("am-symbol").toFile();
        var cache = new AmCache(root);
        var query = new ArtworkQuery("Shivers", "Ed Sheeran", "=", 207853, "", 288, 288, 1080, 1080, 20 * 1024 * 1024);
        cache.rememberAlbum(query, "us", AmPage.album(resource("/equals-album-public.html"), "1581087024"));
        assertEquals("1581087024", cache.album(query, "us").id());
        assertNull(cache.album(new ArtworkQuery("Shivers", "Ed Sheeran", "÷", 207853, "", 288, 288, 1080, 1080, 20 * 1024 * 1024), "us"));
    }
    /** The local album "=" now anchors the match, so the tie against the Tour Edition falls away. */
    @Test public void automaticMatchUsesTheSymbolAlbumAsItsOwnAlbum() throws Exception {
        var requested = new ArrayList<java.net.URI>();
        var resolver = new AmCatalog((uri, limit) -> {
            requested.add(uri);
            if (uri.getHost().equals("itunes.apple.com")) return resource("/itunes-us-equals-album-public.json");
            // The album search does read the storefront shelf; no other album page may be verified.
            if (!uri.getPath().endsWith("/1581087024")) throw new AssertionError("unexpected page " + uri);
            return resource("/equals-album-public.html");
        }, (stage, tracks) -> {});
        var album = resolver.resolve(new ArtworkQuery("Shivers", "Ed Sheeran", "=", 207853, "", 288, 288, 1080, 1080, 20 * 1024 * 1024),
                null, "us", null);
        assertEquals("requested " + requested, "1581087024", album.id());
        assertEquals(14, album.tracks().size());
        assertNotNull("the plain edition page carries the square motion asset", album.master());
        assertTrue(album.master().getPath().endsWith("P629502723_default.m3u8"));
        assertEquals("only the plain edition page is fetched, so the Tour Edition is never verified: " + requested,
                List.of("1581087024"), requested.stream().filter(uri -> uri.getHost().equals("music.apple.com"))
                        .map(uri -> uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1)).toList());
    }
    private static String resource(String name) {
        try (var stream = AmSymbolNameTest.class.getResourceAsStream(name)) {
            assertNotNull(name, stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }
}
