package io.github.andrealtb.artwork.am;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import io.github.andrealtb.artwork.contract.ArtworkQuery;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

/**
 * Lady Gaga's MAYHEM, US, 2026-10-13: a 14-song release (1792666546) and a 17-song one (1837310866)
 * share the title, the artist and every original track; each has its own motion cover.
 */
public class AmLooseEditionTest {
    private static final AmIdentity.MatchProfile[] REPORTING = { AmIdentity.MatchProfile.STRICT, AmIdentity.MatchProfile.STANDARD };

    @Test public void looseChoicePrefersExplicitThenMostSongsThenEarliestCatalogId() {
        var standard = new AmEdition.Candidate("1792666546", "Lady Gaga", "MAYHEM", new AmEdition.Info("explicit", "", 14));
        var expanded = new AmEdition.Candidate("1837310866", "Lady Gaga", "MAYHEM", new AmEdition.Info("explicit", "", 17));
        assertEquals("1837310866", looseChoice(List.of(standard, expanded)));
        assertEquals("1837310866", looseChoice(List.of(expanded, standard)));
        // Explicit outranks a fuller edition that is not, as it does for explicit/clean pairs.
        var clean = new AmEdition.Candidate("1837310867", "Lady Gaga", "MAYHEM", new AmEdition.Info("cleaned", "", 17));
        assertEquals("1792666546", looseChoice(List.of(clean, standard)));
        // Equal editions fall back to the earliest catalog ID, compared as a number.
        var later = new AmEdition.Candidate("1000", "Artist", "Album", new AmEdition.Info("", "", 10));
        var earlier = new AmEdition.Candidate("999", "Artist", "Album", new AmEdition.Info("", "", 10));
        assertEquals("999", looseChoice(List.of(later, earlier)));
        // A rating label does not make another title.
        var labelled = new AmEdition.Candidate("5", "Artist", "Album (Explicit)", new AmEdition.Info("explicit", "", 10));
        assertEquals("5", looseChoice(List.of(earlier, labelled)));
    }
    @Test public void looseChoiceNeverJoinsDifferentTitlesOrArtists() {
        var mayhem = new AmEdition.Candidate("1", "Lady Gaga", "MAYHEM", new AmEdition.Info("explicit", "", 14));
        assertNull(looseChoice(List.of(mayhem,
                new AmEdition.Candidate("2", "Lady Gaga", "MAYHEM (Deluxe)", new AmEdition.Info("explicit", "", 17)))));
        assertNull(looseChoice(List.of(mayhem,
                new AmEdition.Candidate("3", "Don Trip", "MAYHEM", new AmEdition.Info("explicit", "", 8)))));
        assertNull(looseChoice(List.of(mayhem, mayhem)));
    }
    @Test public void strictAndStandardReportTheTieWhileLooseTakesTheFullerEdition() throws Exception {
        for (AmIdentity.MatchProfile profile : REPORTING) {
            var catalog = catalog(profile, null);
            try { catalog.resolve(query("Abracadabra", 223398), null, "us", null); fail(); }
            catch (AmFailure failure) {
                assertEquals(Status.AMBIGUOUS, failure.status); assertEquals("multiple_album_matches", failure.reason);
            }
            assertFalse(catalog.settledEdition());
        }
        var stages = new ArrayList<String>();
        var loose = catalog(AmIdentity.MatchProfile.LOOSE, stages);
        var album = loose.resolve(query("Abracadabra", 223398), null, "us", null);
        assertEquals("1837310866", album.id()); assertEquals(17, album.tracks().size());
        assertTrue(album.master().getPath().endsWith("/P1176822679_default.m3u8"));
        assertTrue(loose.settledEdition());
        assertTrue(stages.contains("stage=album_tie confirmed=2 settled=true"));
    }
    /** The rule prefers the edition that also holds the bonus tracks, so the album keeps one cover. */
    @Test public void aBonusTrackIsUniqueOnEveryLevelAndAgreesWithTheLooseChoice() throws Exception {
        for (AmIdentity.MatchProfile profile : List.of(AmIdentity.MatchProfile.STRICT, AmIdentity.MatchProfile.STANDARD,
                AmIdentity.MatchProfile.LOOSE)) {
            var catalog = catalog(profile, null);
            assertEquals("1837310866", catalog.resolve(query("Kill For Love", 246600), null, "us", null).id());
            assertFalse(catalog.settledEdition());
        }
    }
    @Test public void looseNeedsEveryCandidateTableBeforeChoosing() throws Exception {
        String shelf = AmSearchTest.page(AmSearchTest.item("1792666546", "MAYHEM", "Lady Gaga") + ","
                + AmSearchTest.item("1837310866", "MAYHEM", "Lady Gaga") + "," + AmSearchTest.item("1999999999", "MAYHEM", "Lady Gaga"))
                .replace("\"cn\"", "\"us\"").replace("/cn/", "/us/");
        AmFailure stalled = new AmFailure(Status.RETRY_LATER, "network_stage_timeout", 30_000);
        for (AmIdentity.MatchProfile profile : List.of(AmIdentity.MatchProfile.STANDARD, AmIdentity.MatchProfile.LOOSE)) {
            var catalog = new AmCatalog((uri, limit) -> {
                if (uri.getHost().equals("itunes.apple.com")) return "{\"results\":[]}";
                if (uri.getPath().equals("/us/search")) return shelf;
                if (uri.getPath().endsWith("/1999999999")) throw stalled;
                return page(uri);
            }, (stage, tracks) -> {}, detail -> {}, profile);
            try { catalog.resolve(query("Abracadabra", 223398), null, "us", null); fail(); }
            catch (AmFailure failure) {
                if (profile.settlesEditions) assertSame(stalled, failure);
                else assertEquals("multiple_album_matches", failure.reason);
            }
        }
    }
    /** When only the song search finds the editions, loose verifies both pages and settles the same way. */
    @Test public void songFallbackTieIsSettledOnlyByLoose() throws Exception {
        String row = "{\"wrapperType\":\"track\",\"kind\":\"song\",\"trackId\":%s,\"trackName\":\"Abracadabra\",\"artistName\":\"Lady Gaga\","
                + "\"collectionId\":%s,\"collectionName\":\"MAYHEM\",\"trackTimeMillis\":223398,\"collectionExplicitness\":\"explicit\",\"trackCount\":%s}";
        String songs = "{\"results\":[" + String.format(row, "1792667005", "1792666546", "17") + ","
                + String.format(row, "1837310871", "1837310866", "21") + "]}";
        for (AmIdentity.MatchProfile profile : List.of(AmIdentity.MatchProfile.STRICT, AmIdentity.MatchProfile.STANDARD,
                AmIdentity.MatchProfile.LOOSE)) {
            var catalog = new AmCatalog((uri, limit) -> {
                if (uri.getHost().equals("itunes.apple.com")) return uri.getQuery().contains("entity=musicTrack") ? songs : "{\"results\":[]}";
                if (uri.getPath().equals("/us/search")) return AmCatalogTest.emptySearchPage("us");
                return page(uri);
            }, (stage, tracks) -> {}, detail -> {}, profile);
            if (profile.settlesEditions) {
                assertEquals("1837310866", catalog.resolve(query("Abracadabra", 223398), null, "us", null).id());
                assertTrue(catalog.settledEdition());
                continue;
            }
            try { catalog.resolve(query("Abracadabra", 223398), null, "us", null); fail(); }
            catch (AmFailure failure) { assertEquals("multiple_catalog_matches", failure.reason); }
        }
    }

    /** The strictness these cases exercise: loose, which is the level allowed to settle the tie. */
    private static String looseChoice(java.util.List<AmEdition.Candidate> candidates) {
        return AmEdition.choose(candidates, true);
    }
    private static ArtworkQuery query(String title, long durationMs) {
        return new ArtworkQuery(title, "Lady Gaga", "MAYHEM", durationMs, "", 288, 288, 1080, 1080, 20 * 1024 * 1024);
    }
    private static AmCatalog catalog(AmIdentity.MatchProfile profile, List<String> stages) {
        java.util.function.Consumer<String> discovery = stages == null ? detail -> {} : stages::add;
        return new AmCatalog((uri, limit) -> {
            if (uri.getHost().equals("itunes.apple.com")) return "{\"results\":[]}";
            if (uri.getPath().equals("/us/search")) return AmSearchTest.mayhemShelf();
            return page(uri);
        }, (stage, tracks) -> {}, discovery, profile);
    }
    private static String page(java.net.URI uri) {
        String name = uri.getPath().endsWith("/1792666546") ? "/mayhem-standard-public.html"
                : uri.getPath().endsWith("/1837310866") ? "/mayhem-expanded-public.html" : null;
        assertNotNull(uri.toString(), name);
        try (var stream = AmLooseEditionTest.class.getResourceAsStream(name)) {
            assertNotNull(name, stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }
}
