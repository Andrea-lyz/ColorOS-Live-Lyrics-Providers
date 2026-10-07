package io.github.andrealtb.artwork.am;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

public class AmSearchTest {
    @Test public void jayCnSearchReturnsExpectedAlbumDespiteUncreditedCompilation() throws Exception {
        String html;
        try (var stream = getClass().getResourceAsStream("/cn-jay-search-public.html")) {
            assertNotNull(stream);
            html = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var hits = AmCatalog.searchAlbums((uri, limit) -> uri.getHost().equals("itunes.apple.com")
                ? "{\"results\":[]}" : html, "周杰伦 最伟大的作品", "cn");
        assertEquals(21, hits.size());
        assertEquals("1633408719", hits.get(0).id());
        assertEquals("最伟大的作品", hits.get(0).title());
        assertEquals("周杰伦", hits.get(0).artist());
        assertEquals(12, hits.get(0).trackCount());
        assertEquals("", hits.stream().filter(hit -> hit.id().equals("1091473494")).findFirst().orElseThrow().artist());
    }
    static String snapshot() throws Exception {
        try (var stream = AmSearchTest.class.getResourceAsStream("/cn-album-search-public.html")) {
            assertNotNull(stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    static String page(String items) {
        return "<script id=\"serialized-server-data\">{\"data\":[{\"intent\":{\"$kind\":\"SearchResultsPageIntent\","
                + "\"storefront\":\"cn\"},\"data\":{\"sections\":[{\"id\":\"square-section - album\",\"items\":["
                + items + "]}]}}]}</script>";
    }
    static String item(String id, String title, String artist) {
        return "{\"titleLinks\":[{\"title\":\"" + title + "\"}],\"subtitleLinks\":[{\"title\":\"" + artist
                + "\"}],\"contentDescriptor\":{\"kind\":\"album\",\"identifiers\":{\"storeAdamID\":\"" + id
                + "\"},\"url\":\"https://music.apple.com/cn/album/-/" + id + "\"}}";
    }
    @Test public void publicCnAlbumShelfParsesNamesExplicitTracksAndThumbnails() throws Exception {
        var hits = AmPage.searchAlbums(snapshot(), "cn");
        assertEquals(21, hits.size());
        var first = hits.get(0);
        assertEquals("6814997249", first.id());
        assertEquals("The Life of a Showgirl: The Encore", first.title());
        assertEquals("Taylor Swift", first.artist());
        assertEquals(16, first.trackCount());
        assertTrue(first.explicit());
        assertTrue(first.artwork().endsWith("/300x300bb.jpg"));
        assertEquals("", first.releaseDay());
    }
    @Test public void cnManualSearchUsesSelectedWebStorefrontAndEncodesTerms() throws Exception {
        String html = snapshot();
        List<java.net.URI> calls = new ArrayList<>();
        var hits = AmCatalog.searchAlbums((uri, limit) -> {
            calls.add(uri);
            assertTrue(limit <= 3 * 1024 * 1024);
            return uri.getHost().equals("itunes.apple.com") ? "{\"results\":[]}" : html;
        }, "Taylor Swift & 1989", "cn");
        assertEquals(21, hits.size());
        assertEquals(1, calls.size());
        assertEquals("music.apple.com",calls.get(0).getHost());
        assertEquals("/cn/search", calls.get(0).getPath());
        assertTrue(calls.get(0).getRawQuery().contains("%26"));
    }
    @Test public void existingItunesResultsAvoidExtraWebRequest() throws Exception {
        var hits = AmCatalog.searchAlbums((uri, limit) -> {
            assertEquals("itunes.apple.com", uri.getHost());
            return "{\"results\":[{\"wrapperType\":\"collection\",\"collectionType\":\"Album\",\"collectionId\":10,"
                    + "\"collectionName\":\"1989\",\"artistName\":\"Taylor Swift\"}]}";
        }, "1989", "us");
        assertEquals("10", hits.get(0).id());
    }
    @Test public void genuineEmptyAndDuplicateAlbumsAreHandled() throws Exception {
        assertTrue(AmPage.searchAlbums(page(""), "cn").isEmpty());
        String album = item("10", "1989", "Taylor Swift");
        assertEquals(1, AmPage.searchAlbums(page(album + "," + album), "cn").size());
    }
    @Test public void wrongMarketForeignIdsAndUnreadablePagesAreErrorsNotEmptySearches() throws Exception {
        String valid = page(item("10", "1989", "Taylor Swift"));
        for (String html : List.of("<html>unavailable</html>", valid.replace("\"cn\"", "\"us\""),
                valid.replace("/cn/album/", "/us/album/"), valid.replace("/-/10", "/-/20"),
                valid.replace("titleLinks", "renamedLinks"))) {
            try { AmPage.searchAlbums(html, "cn"); fail(); }
            catch (AmFailure failure) { assertEquals("web_schema_changed", failure.reason); }
        }
    }
    @Test public void transportFailuresAreNotHiddenAsEmptyResults() throws Exception {
        try {
            AmCatalog.searchAlbums((uri, limit) -> { throw new AmFailure(Status.RETRY_LATER, "network_deadline"); }, "1989", "cn");
            fail();
        } catch (AmFailure failure) { assertEquals("network_deadline", failure.reason); }
    }
    @Test public void unrelatedUsItunesAlbumsCannotHideJayChousCnAlbum() throws Exception {
        String html;
        try(var stream=getClass().getResourceAsStream("/cn-jay-search-public.html")) {
            assertNotNull(stream); html=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
        String unrelated="{\"results\":[{\"wrapperType\":\"collection\",\"collectionType\":\"Album\",\"collectionId\":99,"
                + "\"collectionName\":\"The Very Best of Janet Baker\",\"artistName\":\"Dame Janet Baker\"}]}";
        var calls=new ArrayList<java.net.URI>();
        var hits=AmCatalog.searchAlbums((uri,limit)-> { calls.add(uri); return uri.getHost().equals("itunes.apple.com") ? unrelated : html; },
                "周杰伦 最伟大的作品","cn");
        assertEquals("1633408719",hits.get(0).id()); assertEquals("最伟大的作品",hits.get(0).title());
        assertTrue(calls.stream().allMatch(uri->uri.getHost().equals("music.apple.com") && uri.getPath().equals("/cn/search")));
    }
    @Test public void emptyOtherStorefrontItunesFallsBackToThatSameStorefront() throws Exception {
        String html=page(item("10","1989","Taylor Swift")).replace("\"cn\"","\"us\"").replace("/cn/","/us/");
        var calls=new ArrayList<java.net.URI>();
        var hits=AmCatalog.searchAlbums((uri,limit)-> { calls.add(uri); return uri.getHost().equals("itunes.apple.com") ? "{\"results\":[]}" : html; },
                "1989","us");
        assertEquals("10",hits.get(0).id()); assertEquals(2,calls.size());
        assertTrue(calls.get(0).getQuery().contains("country=us")); assertEquals("/us/search",calls.get(1).getPath());
    }
    private AmCatalog catalog(String items) {
        return new AmCatalog((uri, limit) -> {
            if (uri.getHost().equals("itunes.apple.com")) return "{\"results\":[]}";
            if (uri.getPath().equals("/cn/search")) return page(items);
            assertTrue(uri.getPath(),uri.getPath().equals("/cn/album/-/10") || uri.getPath().equals("/cn/album/-/20"));
            return AmCatalogTest.albumPage(uri.getPath().endsWith("/20") ? "20" : "10","Style").replace("/us/", "/cn/");
        }, (stage, tracks) -> {});
    }
    @Test public void automaticCnResolutionStillVerifiesActualSongTable() throws Exception {
        var resolver = catalog(item("10", "1989", "Taylor Swift"));
        assertEquals("10", resolver.resolve(AmIdentityTest.query("Style", "1989", 288), null, "cn", null).id());
        try { resolver.resolve(AmIdentityTest.query("Unrelated", "1989", 288), null, "cn", null); fail(); }
        catch (AmFailure failure) { assertEquals(Status.RETRY_LATER, failure.status); }
    }
    @Test public void automaticCnResolutionKeepsEditionAmbiguityAndRejectsWrongArtists() throws Exception {
        var resolver = catalog(item("10", "1989", "Taylor Swift") + "," + item("20", "1989", "Taylor Swift"));
        try { resolver.resolve(AmIdentityTest.query("Style", "1989", 288), null, "cn", null); fail(); }
        catch (AmFailure failure) { assertEquals(Status.AMBIGUOUS, failure.status); }
        resolver = catalog(item("10", "1989", "Someone Else"));
        try { resolver.resolve(AmIdentityTest.query("Style", "1989", 288), null, "cn", null); fail(); }
        catch (AmFailure failure) { assertEquals("catalog_match_unconfirmed", failure.reason); }
    }
}
