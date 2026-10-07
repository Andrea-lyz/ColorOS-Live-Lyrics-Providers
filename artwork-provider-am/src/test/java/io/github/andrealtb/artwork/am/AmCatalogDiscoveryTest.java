package io.github.andrealtb.artwork.am;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONObject;
import io.github.andrealtb.artwork.contract.ArtworkQuery;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

public class AmCatalogDiscoveryTest {
    private String resource(String name) throws Exception {
        try(var stream=getClass().getResourceAsStream("/"+name)) {
            assertNotNull(name,stream); return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    private ArtworkQuery query() {
        return AmMatchingBoundaryTest.query("betty (Explicit)","Taylor Swift","folklore (deluxe version)",294521);
    }
    @Test public void explicitAlbumHiddenFromBothSearchesIsRecoveredFromOtherSongsAndVerified() throws Exception {
        var sample=new JSONObject(resource("folklore-search-gap-public.json"));
        String songs=sample.getJSONObject("songs").toString(), albums=sample.getJSONObject("albums").toString();
        String page=resource("folklore-explicit-betty-public.html");
        var resolver=new AmCatalog((uri,limit)-> {
            if(uri.getHost().equals("itunes.apple.com")) return uri.getQuery().contains("entity=album") ? albums : songs;
            assertTrue("must not choose the cleaned album",uri.getPath().endsWith("/1528112358")); return page;
        },(stage,tracks)->{});
        var matched=resolver.resolve(query(),null,"cn",null);
        assertEquals("1528112358",matched.id()); assertEquals("1528112557",matched.tracks().get(0).songId());
        assertEquals("explicit",matched.tracks().get(0).edition().rating());
    }
    @Test public void anotherSongIsOnlyAnAlbumHintAndCannotStandInForTheCurrentSong() throws Exception {
        var sample=new JSONObject(resource("folklore-search-gap-public.json"));
        String songs=sample.getJSONObject("songs").toString(), albums=sample.getJSONObject("albums").toString();
        String page=resource("folklore-explicit-betty-public.html").replace("\"title\":\"betty\"","\"title\":\"Other Song\"");
        var resolver=new AmCatalog((uri,limit)->uri.getHost().equals("itunes.apple.com")
                ? uri.getQuery().contains("entity=album") ? albums : songs : page,(stage,tracks)->{});
        try { resolver.resolve(query(),null,"cn",null); fail(); }
        catch(AmFailure failure) { assertEquals("catalog_match_unconfirmed",failure.reason); }
    }
    @Test public void otherSongReleaseDaysDoNotCreateAnAlbumEquivalencePair() throws Exception {
        String track="{\"wrapperType\":\"track\",\"kind\":\"song\",\"trackId\":%s,\"collectionId\":%s,\"trackName\":\"Other\","
                + "\"artistName\":\"Taylor Swift\",\"collectionName\":\"1989\",\"trackTimeMillis\":200000,"
                + "\"collectionExplicitness\":\"%s\",\"releaseDate\":\"2014-10-27\",\"trackCount\":13}";
        String songs="{\"results\":["+String.format(track,"11","10","explicit")+","+String.format(track,"21","20","cleaned")+"]}";
        String row="{\"wrapperType\":\"collection\",\"collectionType\":\"Album\",\"collectionId\":%s,\"collectionName\":\"1989\",\"artistName\":\"Taylor Swift\"}";
        String albums="{\"results\":["+String.format(row,"10")+","+String.format(row,"20")+"]}";
        var resolver=new AmCatalog((uri,limit)->uri.getHost().equals("itunes.apple.com")
                ? uri.getQuery().contains("entity=album") ? albums : songs
                : AmCatalogTest.albumPage(uri.getPath().endsWith("/20") ? "20" : "10","Style"),(stage,tracks)->{});
        try { resolver.resolve(AmIdentityTest.query("Style","1989",288),null,"us",null); fail(); }
        catch(AmFailure failure) { assertEquals(Status.AMBIGUOUS,failure.status); }
    }
    @Test public void conflictingAlbumRatingEvidenceStaysRetryable() throws Exception {
        String row="{\"wrapperType\":\"track\",\"kind\":\"song\",\"trackId\":%s,\"collectionId\":10,\"trackName\":\"Other\","
                + "\"artistName\":\"Taylor Swift\",\"collectionName\":\"1989\",\"trackTimeMillis\":200000,\"collectionExplicitness\":\"%s\"}";
        String response="{\"results\":["+String.format(row,"11","explicit")+","+String.format(row,"12","cleaned")+"]}";
        var resolver=new AmCatalog((uri,limit)->response,(stage,tracks)->{});
        try { resolver.resolve(AmIdentityTest.query("Style","1989",288),null,"us",null); fail(); }
        catch(AmFailure failure) { assertEquals("catalog_album_metadata_unconfirmed",failure.reason); assertEquals(Status.RETRY_LATER,failure.status); }
    }
    @Test public void diagnosticsDistinguishRatingRejectionFromTitleBodyMismatchWithoutText() throws Exception {
        var sample=new JSONObject(resource("folklore-search-gap-public.json"));
        var tracks=AmPage.itunes(sample.getJSONObject("clean").toString());
        String log=AmIdentity.diagnostics(tracks,query());
        assertTrue(log.contains("titleMatches=0")); assertTrue(log.contains("titleBodyMatches=1"));
        assertTrue(log.contains("requestedRating=explicit")); assertTrue(log.contains("ratingRejected="+tracks.size()));
        assertFalse(log.contains("betty")); assertFalse(log.contains("Taylor")); assertFalse(log.contains("folklore"));
    }
    @Test public void previewDurationIsNotSilentlyPromotedToTheFullRecording() {
        var track=new AmIdentity.Track("1","10","最偉大的作品","周杰倫","最偉大的作品",244000);
        var preview=AmMatchingBoundaryTest.query("最伟大的作品","周杰伦","最伟大的作品",60000);
        for(var profile:List.of(AmIdentity.MatchProfile.STRICT,AmIdentity.MatchProfile.STANDARD,AmIdentity.MatchProfile.LOOSE))
            assertFalse(AmIdentity.agrees(track,preview,profile));
    }
}
