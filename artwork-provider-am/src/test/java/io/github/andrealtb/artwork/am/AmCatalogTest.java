package io.github.andrealtb.artwork.am;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.andrealtb.artwork.contract.ArtworkQuery;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

public class AmCatalogTest {
    static String emptySearchPage(String country) {
        return AmSearchTest.page("").replace("\"storefront\":\"cn\"", "\"storefront\":\"" + country + "\"");
    }
    static String albumPage(String id,String title) {
        return AmPageTest.page("null").replace("track-list - 10","track-list - " + id)
                .replace("album-detail-header-section - 10","album-detail-header-section - " + id)
                .replace("\"storeAdamID\":\"10\"","\"storeAdamID\":\"" + id + "\"")
                .replace("/10?i=1","/" + id + "?i=1").replace("\"title\":\"Style\"","\"title\":\"" + title + "\"");
    }
    private static String twinAlbums() {
        String row="{\"wrapperType\":\"collection\",\"collectionType\":\"Album\",\"collectionId\":%s,\"collectionName\":\"1989\",\"artistName\":\"Taylor Swift\"}";
        return "{\"results\":[" + String.format(row,"10") + "," + String.format(row,"20") + "]}";
    }
    @Test public void cnStorefrontSearchesTheUsCatalogForGlobalAdamIds() throws Exception {
        var first = new AtomicBoolean(true);
        var resolver = new AmCatalog((uri, limit) -> {
            if (first.getAndSet(false)) {
                assertTrue("cn album search must use the us catalog: " + uri, uri.getQuery().contains("country=us"));
                assertTrue(uri.getQuery().contains("entity=album"));
                return "{\"results\":[]}";
            }
            if (uri.getHost().equals("music.apple.com")) return AmPageTest.page("null");
            if (uri.getQuery().contains("entity=album")) return "{\"results\":[]}";
            return "{\"results\":[]}";
        }, (stage, tracks) -> {});
        try { resolver.resolve(AmIdentityTest.query("Style", "1989", 288), null, "cn", null); fail(); }
        catch (AmFailure failure) { assertEquals(Status.RETRY_LATER, failure.status); }
    }
    @Test public void namedAlbumSearchDoesNotSpendAnySongSearchRound() throws Exception {
        var calls = new AtomicInteger();
        var resolver = new AmCatalog((uri, limit) -> {
            calls.incrementAndGet();
            if (uri.getHost().equals("music.apple.com")) return AmPageTest.page("null");
            assertTrue(uri.getQuery().contains("entity=album"));
            assertTrue(uri.getQuery().contains("term=1989&"));
            return "{\"results\":[{\"wrapperType\":\"collection\",\"collectionType\":\"Album\",\"collectionId\":10,\"collectionName\":\"1989\",\"artistName\":\"Taylor Swift\"}]}";
        }, (stage, tracks) -> {});
        assertEquals("10", resolver.resolve(AmIdentityTest.query("Style", "1989", 288), null, "us", null).id());
        assertEquals("one album search and its owned page", 2, calls.get());
    }
    @Test public void confirmedAlbumTableMatchesNewSongWithoutAnyNetworkSearch() throws Exception {
        var album = AmPage.album(AmPageTest.page("null"), "10");
        var resolver = new AmCatalog((uri, limit) -> { fail("cached album should not fetch"); return ""; }, (stage, tracks) -> {});
        assertSame(album, resolver.resolve(AmIdentityTest.query("Style", "1989", 288), null, "us", album));
    }
    @Test public void albumWithoutMotionGoesToNeteaseWithoutSupplementalSongSearch() throws Exception {
        var calls = new java.util.ArrayList<java.net.URI>();
        var catalog = new AmCatalog((uri, limit) -> {
            calls.add(uri);
            if (uri.getHost().equals("music.apple.com")) return AmPageTest.page("null");
            assertTrue(uri.getQuery().contains("entity=album"));
            return "{\"results\":[{\"wrapperType\":\"collection\",\"collectionType\":\"Album\",\"collectionId\":10,\"collectionName\":\"1989\",\"artistName\":\"Taylor Swift\"}]}";
        }, (stage, tracks) -> {});
        assertEquals("netease", ArtworkSources.automatic(true, () -> {
            var album = catalog.resolve(AmIdentityTest.query("Style", "1989", 288), null, "us", null);
            assertNull(album.master());
            throw new AmFailure(Status.NO_MOTION, "confirmed_album_no_motion");
        }, () -> "netease"));
        assertEquals(2, calls.size());
    }
    @Test public void automaticAlbumTransportFailureDoesNotStartSongSearch() throws Exception {
        var calls = new AtomicInteger();
        var catalog = new AmCatalog((uri, limit) -> {
            calls.incrementAndGet();
            assertTrue(uri.getQuery().contains("entity=album"));
            throw new AmFailure(Status.RETRY_LATER, "network_io");
        }, (stage, tracks) -> {});
        assertEquals("netease", ArtworkSources.automatic(true, () -> {
            catalog.resolve(AmIdentityTest.query("Style", "1989", 288), null, "us", null);
            fail(); return "apple";
        }, () -> "netease"));
        assertEquals(1, calls.get());
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
        var resolver = new AmCatalog((uri, limit) -> uri.getHost().equals("music.apple.com") ? albumPage(uri.getPath().endsWith("/20") ? "20" : "10","Style")
                : uri.getQuery().contains("entity=album") ? "{\"results\":[" + String.format(row, "10") + "," + String.format(row, "20") + "]}" : "{\"results\":[]}", (stage, tracks) -> {});
        try { resolver.resolve(AmIdentityTest.query("Style", "1989", 288), null, "us", null); fail(); }
        catch (AmFailure failure) { assertEquals(Status.AMBIGUOUS, failure.status); }
    }
    @Test public void missingAlbumUsesOneSongSearchAndVerifiesItsOwnedPage() throws Exception {
        var searches = new AtomicInteger();
        var resolver = new AmCatalog((uri, limit) -> {
            if (uri.getHost().equals("music.apple.com")) return AmPageTest.page("null");
            searches.incrementAndGet();
            assertTrue(uri.getQuery().contains("entity=musicTrack"));
            return "{\"results\":[{\"wrapperType\":\"track\",\"kind\":\"song\",\"trackId\":1,\"trackName\":\"Style\",\"artistName\":\"Taylor Swift\","
                    + "\"collectionId\":10,\"collectionName\":\"1989\",\"trackTimeMillis\":231000}]}";
        }, (stage, tracks) -> {});
        var query = new ArtworkQuery("Style", "Taylor Swift", "", 231000, "", 288, 288, 1080, 1080, 20 * 1024 * 1024);
        assertEquals("10", resolver.resolve(query, null, "us", null).id());
        assertEquals(1, searches.get());
    }
    @Test public void noAlbumSongWithSeveralAlbumCandidatesStaysAmbiguous() throws Exception {
        String row = "{\"wrapperType\":\"track\",\"kind\":\"song\",\"trackId\":%s,\"trackName\":\"Style\",\"artistName\":\"Taylor Swift\","
                + "\"collectionId\":%s,\"collectionName\":\"1989\",\"trackTimeMillis\":231000}";
        var resolver = new AmCatalog((uri, limit) -> {
            assertTrue(uri.getQuery().contains("entity=musicTrack"));
            return "{\"results\":[" + String.format(row, "1", "10") + "," + String.format(row, "2", "20") + "]}";
        }, (stage, tracks) -> {});
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
    @Test public void cnPageMissingFallsBackToUsCatalogPageAndStillVerifies() throws Exception {
        var resolver = new AmCatalog((uri, limit) -> {
            if (uri.getHost().equals("music.apple.com")) {
                if (uri.getPath().endsWith("/search")) return emptySearchPage("cn");
                // The CN storefront cannot serve the album at all; the US page can.
                return uri.getPath().startsWith("/cn/album/") ? "<html>unavailable</html>" : AmPageTest.page("null");
            }
            if (uri.getQuery().contains("entity=album")) return "{\"results\":[]}";
            return "{\"results\":[{\"wrapperType\":\"track\",\"kind\":\"song\",\"trackId\":1,\"trackName\":\"Style\",\"artistName\":\"Taylor Swift\","
                    + "\"collectionId\":10,\"collectionName\":\"1989\",\"trackTimeMillis\":231000}]}";
        }, (stage, tracks) -> {});
        assertEquals("10", resolver.resolve(AmIdentityTest.query("Style", "1989", 288), null, "cn", null).id());
    }
    @Test public void sameMarketPageFailureIsNotMaskedByFallback() throws Exception {
        var resolver = new AmCatalog((uri, limit) -> {
            if (uri.getHost().equals("music.apple.com")) return "<html>unavailable</html>";
            if (uri.getQuery().contains("entity=album")) return "{\"results\":[]}";
            return "{\"results\":[{\"wrapperType\":\"track\",\"kind\":\"song\",\"trackId\":1,\"trackName\":\"Style\",\"artistName\":\"Taylor Swift\","
                    + "\"collectionId\":10,\"collectionName\":\"1989\",\"trackTimeMillis\":231000}]}";
        }, (stage, tracks) -> {});
        try { resolver.resolve(AmIdentityTest.query("Style", "1989", 288), null, "us", null); fail(); }
        catch (AmFailure failure) { assertEquals(Status.RETRY_LATER, failure.status); }
    }
    @Test public void matchDiagnosticsRevealFieldsOnlyNotPrivateTextOrIdentity() {
        String value = AmIdentity.diagnostics(List.of(new AmIdentity.Track("private-id", "private-album", "Style", "Taylor Swift", "1989", 231000)),
                AmIdentityTest.query("Style", "1989", 288));
        assertTrue(value.contains("completeMatches=1")); assertFalse(value.contains("Taylor")); assertFalse(value.contains("private"));
    }
    @Test public void multipleAlbumCandidatesVerifyTablesBeforeChoosingTheOnlyMatchingOne() throws Exception {
        var pages=new AtomicInteger();
        var resolver=new AmCatalog((uri,limit)-> {
            if(uri.getHost().equals("music.apple.com")) { pages.incrementAndGet(); return uri.getPath().endsWith("/20") ? albumPage("20","Other Song") : albumPage("10","Style"); }
            return uri.getQuery().contains("entity=album") ? twinAlbums() : "{\"results\":[]}";
        },(stage,tracks)->{});
        assertEquals("10",resolver.resolve(AmIdentityTest.query("Style","1989",288),null,"us",null).id());
        assertEquals(2,pages.get());
    }
    @Test public void failedPeerCannotMakeAnotherAlbumUniquelyConfirmed() throws Exception {
        var resolver=new AmCatalog((uri,limit)-> {
            if(uri.getHost().equals("music.apple.com")) {
                if(uri.getPath().endsWith("/20")) throw new AmFailure(Status.RETRY_LATER,"upstream_rate_limit",60000);
                return albumPage("10","Style");
            }
            return uri.getQuery().contains("entity=album") ? twinAlbums() : "{\"results\":[]}";
        },(stage,tracks)->{});
        try { resolver.resolve(AmIdentityTest.query("Style","1989",288),null,"us",null); fail(); }
        catch(AmFailure failure) { assertEquals("upstream_rate_limit",failure.reason); }
    }
    @Test public void incompletePeerTableIsUnresolvedRatherThanAnExcludedAlbum() throws Exception {
        String page=albumPage("20","Other Song");
        var data=new org.json.JSONObject(page.substring(page.indexOf('{'),page.lastIndexOf('}')+1));
        data.getJSONArray("data").getJSONObject(0).getJSONObject("data").getJSONArray("sections").getJSONObject(1)
                .getJSONArray("items").put(new org.json.JSONObject().put("title","Broken row")
                        .put("contentDescriptor",new org.json.JSONObject().put("kind","song")));
        String incomplete="<script id=\"serialized-server-data\">" + data + "</script>";
        var resolver=new AmCatalog((uri,limit)-> {
            if(uri.getHost().equals("music.apple.com")) return uri.getPath().endsWith("/20") ? incomplete : albumPage("10","Style");
            return uri.getQuery().contains("entity=album") ? twinAlbums() : "{\"results\":[]}";
        },(stage,tracks)->{});
        try { resolver.resolve(AmIdentityTest.query("Style","1989",288),null,"us",null); fail(); }
        catch(AmFailure failure) { assertEquals("album_tracks_incomplete",failure.reason); }
    }
    @Test public void oversizedAlbumCandidateSetKeepsAmbiguityWithinRequestBudget() throws Exception {
        var rows=new org.json.JSONArray();
        for(int id=10;id<18;id++) rows.put(new org.json.JSONObject().put("wrapperType","collection").put("collectionType","Album")
                .put("collectionId",id).put("collectionName","1989").put("artistName","Taylor Swift"));
        String response=new org.json.JSONObject().put("results",rows).toString();
        var resolver=new AmCatalog((uri,limit)-> {
            if(uri.getHost().equals("music.apple.com")) { fail("large candidate set should not fetch pages"); }
            return uri.getQuery().contains("entity=album") ? response : "{\"results\":[]}";
        },(stage,tracks)->{});
        try { resolver.resolve(AmIdentityTest.query("Style","1989",288),null,"us",null); fail(); }
        catch(AmFailure failure) { assertEquals(Status.AMBIGUOUS,failure.status); }
    }
    @Test public void transportRateLimitAndCancellationNeverTriggerStorefrontFallback() throws Exception {
        var link=AmIdentity.link("https://music.apple.com/cn/album/a/10");
        for(String reason:List.of("upstream_rate_limit","upstream_http_error","upstream_access_denied","network_dns","network_io","network_deadline","cancelled")) {
            var calls=new AtomicInteger();
            var resolver=new AmCatalog((uri,limit)-> { calls.incrementAndGet(); throw new AmFailure(reason.equals("cancelled") ? Status.ERROR : Status.RETRY_LATER,reason); },(stage,tracks)->{});
            try { resolver.resolve(AmIdentityTest.query("Style","1989",288),link,"cn",null); fail(); }
            catch(AmFailure failure) { assertEquals(reason,failure.reason); }
            assertEquals(reason,1,calls.get());
        }
    }
    @Test public void notFoundPageMayFallbackButTrackMismatchCannot() throws Exception {
        var link=AmIdentity.link("https://music.apple.com/cn/album/a/10");
        var resolver=new AmCatalog((uri,limit)-> {
            if(uri.getPath().startsWith("/cn/")) throw new AmFailure(Status.RETRY_LATER,"upstream_not_found",60000);
            return albumPage("10","Style");
        },(stage,tracks)->{});
        assertEquals("10",resolver.resolve(AmIdentityTest.query("Style","1989",288),link,"cn",null).id());
        var calls=new AtomicInteger();
        resolver=new AmCatalog((uri,limit)-> { calls.incrementAndGet(); return albumPage("10","Other Song"); },(stage,tracks)->{});
        try { resolver.resolve(AmIdentityTest.query("Style","1989",288),link,"cn",null); fail(); }
        catch(AmFailure failure) { assertEquals("catalog_match_unconfirmed",failure.reason); }
        assertEquals(1,calls.get());
    }
    @Test public void discoveryLogOmitsFullSearchTermsAndQueryFields() throws Exception {
        var messages=new java.util.ArrayList<String>();
        var resolver=new AmCatalog((uri,limit)->uri.getHost().equals("music.apple.com") ? emptySearchPage("us") : "{\"results\":[]}",
                (stage,tracks)->{},messages::add);
        var q=AmMatchingBoundaryTest.query("private-title-secret","private-artist-secret","private-album-secret",231000);
        try { resolver.resolve(q,null,"us",null); fail(); }
        catch(AmFailure failure) { assertEquals(Status.RETRY_LATER,failure.status); }
        String log=String.join("\n",messages);
        assertTrue(log.contains("round=1"));
        assertFalse(log.contains(q.title)); assertFalse(log.contains(q.artist)); assertFalse(log.contains(q.album));
        assertFalse(log.contains("term="));
    }
    @Test public void albumFallbackUsesCurrentPrefixProfileBeforePageVerification() throws Exception {
        var query=AmIdentityTest.query("Style","abcdefghij…",288);
        AmCatalog.Fetch fetch=(uri,limit)-> {
            if(uri.getHost().equals("music.apple.com")) return uri.getPath().contains("/album/")
                    ? AmPageTest.page("null").replace("\"title\":\"1989\"","\"title\":\"abcdefghijklmn\"") : "";
            return uri.getQuery().contains("entity=album")
                    ? "{\"results\":[{\"wrapperType\":\"collection\",\"collectionType\":\"Album\",\"collectionId\":10,\"collectionName\":\"abcdefghijklmn\",\"artistName\":\"Taylor Swift\"}]}"
                    : "{\"results\":[]}";
        };
        assertEquals("10",new AmCatalog(fetch,(stage,tracks)->{},s->{},AmIdentity.MatchProfile.LOOSE).resolve(query,null,"us",null).id());
        try { new AmCatalog(fetch,(stage,tracks)->{},s->{},AmIdentity.MatchProfile.STRICT).resolve(query,null,"us",null); fail(); }
        catch(AmFailure failure) { assertEquals(Status.RETRY_LATER,failure.status); }
    }
    @Test public void verifiedAlbumPageCarriesTheSameCatalogIdsRatingEvidence() throws Exception {
        String response="{\"results\":[{\"wrapperType\":\"track\",\"kind\":\"song\",\"trackId\":1,\"trackName\":\"Style\","
                + "\"artistName\":\"Taylor Swift\",\"collectionId\":10,\"collectionName\":\"1989\",\"trackTimeMillis\":231000,"
                + "\"collectionExplicitness\":\"explicit\",\"releaseDate\":\"2014-10-27\",\"trackCount\":13}]}";
        var resolver=new AmCatalog((uri,limit)->uri.getHost().equals("music.apple.com")
                ? uri.getPath().endsWith("/search") ? emptySearchPage("us") : AmPageTest.page("null") : response,(stage,tracks)->{});
        var album=resolver.resolve(AmIdentityTest.query("Style (Explicit)","1989",288),null,"us",null);
        assertEquals("10",album.id()); assertEquals("explicit",album.tracks().get(0).edition().rating());
        assertEquals("explicit",AmPage.snapshot(AmPage.snapshot(album)).tracks().get(0).edition().rating());
    }
}
