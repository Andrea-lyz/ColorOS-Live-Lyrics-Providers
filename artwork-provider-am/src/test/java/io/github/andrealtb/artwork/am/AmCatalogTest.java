package io.github.andrealtb.artwork.am;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.andrealtb.artwork.contract.ArtworkQuery;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

public class AmCatalogTest {
    @Test public void cnStorefrontSearchesTheUsCatalogForGlobalAdamIds() throws Exception {
        var first = new AtomicBoolean(true);
        var resolver = new AmCatalog((uri, limit) -> {
            if (first.getAndSet(false)) {
                assertTrue("cn song search must use the us catalog: " + uri, uri.getQuery().contains("country=us"));
                return "{\"results\":[]}";
            }
            if (uri.getHost().equals("music.apple.com")) return AmPageTest.page("null");
            if (uri.getQuery().contains("entity=album")) return "{\"results\":[]}";
            return "{\"results\":[]}";
        }, (stage, tracks) -> {});
        try { resolver.resolve(AmIdentityTest.query("Style", "1989", 288), null, "cn", null); fail(); }
        catch (AmFailure failure) { assertEquals(Status.RETRY_LATER, failure.status); }
    }
    @Test public void emptySongSearchFallsBackToExactAlbumAndStillVerifiesSongTable() throws Exception {
        var calls = new AtomicInteger();
        var resolver = new AmCatalog((uri, limit) -> {
            calls.incrementAndGet();
            if (uri.getHost().equals("music.apple.com")) return AmPageTest.page("null");
            if (uri.getQuery().contains("entity=album")) return "{\"results\":[{\"wrapperType\":\"collection\",\"collectionType\":\"Album\",\"collectionId\":10,\"collectionName\":\"1989\",\"artistName\":\"Taylor Swift\"}]}";
            return "{\"results\":[]}";
        }, (stage, tracks) -> {});
        assertEquals("10", resolver.resolve(AmIdentityTest.query("Style", "1989", 288), null, "us", null).id());
        // Broadening song-search rounds run first, then the exact-album fallback and its page verification.
        assertTrue("expected at least the search, album and page calls, was " + calls.get(), calls.get() >= 3);
    }
    @Test public void confirmedAlbumTableMatchesNewSongWithoutAnyNetworkSearch() throws Exception {
        var album = AmPage.album(AmPageTest.page("null"), "10");
        var resolver = new AmCatalog((uri, limit) -> { fail("cached album should not fetch"); return ""; }, (stage, tracks) -> {});
        assertSame(album, resolver.resolve(AmIdentityTest.query("Style", "1989", 288), null, "us", album));
    }
    @Test public void albumFallbackCannotAcceptOriginalForRerecordOrUnrelatedSong() throws Exception {
        var resolver = new AmCatalog((uri, limit) -> {
            if (uri.getHost().equals("music.apple.com")) return AmPageTest.page("null");
            if (uri.getQuery().contains("entity=album")) return "{\"results\":[{\"wrapperType\":\"collection\",\"collectionType\":\"Album\",\"collectionId\":10,\"collectionName\":\"1989\",\"artistName\":\"Taylor Swift\"}]}";
            return "{\"results\":[]}";
        }, (stage, tracks) -> {});
        try { resolver.resolve(AmIdentityTest.query("Style (Taylor's Version)", "1989", 288), null, "us", null); fail(); }
        catch (AmFailure failure) { assertEquals(Status.RETRY_LATER, failure.status); }
    }
    @Test public void sameAlbumNameMultipleIdsRemainsAmbiguous() throws Exception {
        String row = "{\"wrapperType\":\"collection\",\"collectionType\":\"Album\",\"collectionId\":%s,\"collectionName\":\"1989\",\"artistName\":\"Taylor Swift\"}";
        var resolver = new AmCatalog((uri, limit) -> uri.getQuery().contains("entity=album")
                ? "{\"results\":[" + String.format(row, "10") + "," + String.format(row, "20") + "]}" : "{\"results\":[]}", (stage, tracks) -> {});
        try { resolver.resolve(AmIdentityTest.query("Style", "1989", 288), null, "us", null); fail(); }
        catch (AmFailure failure) { assertEquals(Status.AMBIGUOUS, failure.status); }
    }
    @Test public void noAlbumSongStillFallsBackToAlbumSearchAndVerifiesItsTrackTable() throws Exception {
        var resolver = new AmCatalog((uri, limit) -> {
            if (uri.getHost().equals("music.apple.com")) return AmPageTest.page("null");
            if (uri.getQuery().contains("entity=album")) return "{\"results\":[{\"wrapperType\":\"collection\",\"collectionType\":\"Album\",\"collectionId\":10,\"collectionName\":\"1989\",\"artistName\":\"Taylor Swift\"}]}";
            return "{\"results\":[]}";
        }, (stage, tracks) -> {});
        var query = new ArtworkQuery("Style", "Taylor Swift", "", 231000, "", 288, 288, 1080, 1080, 20 * 1024 * 1024);
        assertEquals("10", resolver.resolve(query, null, "us", null).id());
    }
    @Test public void noAlbumSongWithSeveralAlbumCandidatesStaysAmbiguous() throws Exception {
        String row = "{\"wrapperType\":\"collection\",\"collectionType\":\"Album\",\"collectionId\":%s,\"collectionName\":\"1989\",\"artistName\":\"Taylor Swift\"}";
        var resolver = new AmCatalog((uri, limit) -> uri.getQuery().contains("entity=album")
                ? "{\"results\":[" + String.format(row, "10") + "," + String.format(row, "20") + "]}" : "{\"results\":[]}", (stage, tracks) -> {});
        var query = new ArtworkQuery("Style", "Taylor Swift", "", 231000, "", 288, 288, 1080, 1080, 20 * 1024 * 1024);
        try { resolver.resolve(query, null, "us", null); fail(); }
        catch (AmFailure failure) { assertEquals(Status.AMBIGUOUS, failure.status); }
    }
    @Test public void searchTermsIncludeTheNormalizedCleanForm() throws Exception {
        var sawClean = new AtomicBoolean(false);
        var resolver = new AmCatalog((uri, limit) -> {
            if (uri.getHost().equals("music.apple.com")) return AmPageTest.page("null");
            if (uri.getQuery().contains("entity=album")) return "{\"results\":[]}";
            if (uri.getRawQuery().contains("bazzi+infinite+dream")) sawClean.set(true);
            return "{\"results\":[]}";
        }, (stage, tracks) -> {});
        var query = new ArtworkQuery("Infinite Dream (Explicit)", "Bazzi", "", 183000, "", 288, 288, 1080, 1080, 20 * 1024 * 1024);
        try { resolver.resolve(query, null, "us", null); fail(); }
        catch (AmFailure failure) { assertEquals(Status.RETRY_LATER, failure.status); }
        assertTrue("the normalized clean search term was never issued", sawClean.get());
    }
    @Test public void matchDiagnosticsRevealFieldsOnlyNotPrivateTextOrIdentity() {
        String value = AmIdentity.diagnostics(List.of(new AmIdentity.Track("private-id", "private-album", "Style", "Taylor Swift", "1989", 231000)),
                AmIdentityTest.query("Style", "1989", 288));
        assertTrue(value.contains("completeMatches=1")); assertFalse(value.contains("Taylor")); assertFalse(value.contains("private"));
    }
}
