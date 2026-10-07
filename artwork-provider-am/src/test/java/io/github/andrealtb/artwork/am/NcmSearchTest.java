package io.github.andrealtb.artwork.am;

import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import io.github.andrealtb.artwork.contract.ArtworkQuery;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

public class NcmSearchTest {
    private static ArtworkQuery query(String artist, String album, long duration) {
        return new ArtworkQuery("Something Just Like This", artist, album, duration, "", 288, 288, 1080, 1080, 20 * 1024 * 1024);
    }
    private static NcmSearch.Song song(String id, String artist, String album, String albumId, long duration) {
        return new NcmSearch.Song(id, "Something Just Like This", artist, album, albumId, duration);
    }
    @Test public void pastedShareTextExtractsSongAndCollaborationWithoutVisitingShortUrl() throws Exception {
        var input = NcmSearch.input("分享The Chainsmokers/Coldplay的单曲《Something Just Like This》: https://163cn.tv/bh430Lg7 (来自@网易云音乐)");
        assertEquals("Something Just Like This The Chainsmokers/Coldplay", input.terms());
        assertTrue(input.netease()); assertEquals("", input.songId());
        assertEquals("1465111971", NcmSearch.input("https://music.163.com/#/song?id=1465111971").songId());
        assertEquals("1465111971", NcmSearch.input("https://music.163.com/song?id=1465111971&userid=irrelevant").songId());
        assertFalse(NcmSearch.input("https://music.apple.com/us/album/test/123").netease());
        try { NcmSearch.input("https://163cn.tv/bh430Lg7"); fail(); }
        catch (AmFailure expected) { assertEquals("netease_share_text_required", expected.reason); }
    }
    @Test public void parseBothSearchAndSongDetailShapesAndRejectSchemaChanges() throws Exception {
        var search = new JSONObject("{\"code\":200,\"result\":{\"songs\":[{\"id\":1,\"name\":\"Something Just Like This\",\"ar\":[{\"name\":\"The Chainsmokers\"},{\"name\":\"Coldplay\"}],\"al\":{\"id\":9,\"name\":\"Memories\"},\"dt\":247000}]}}");
        var songs = NcmSearch.songs(search);
        assertEquals("The Chainsmokers/Coldplay", songs.get(0).artist());
        assertEquals("9", songs.get(0).albumId());
        assertEquals("", songs.get(0).artwork());
        search.getJSONObject("result").getJSONArray("songs").getJSONObject(0).getJSONObject("al")
                .put("picUrl", "http://p1.music.126.net/sample/1.jpg");
        assertEquals("https://p1.music.126.net/sample/1.jpg?param=300y300", NcmSearch.songs(search).get(0).artwork());
        var detail = new JSONObject("{\"code\":200,\"songs\":[{\"id\":1,\"name\":\"Something Just Like This\",\"artists\":[{\"name\":\"Coldplay\"}],\"album\":{\"id\":9,\"name\":\"Memories\"},\"duration\":247000}]}");
        assertEquals(247000, NcmSearch.songs(detail).get(0).durationMs());
        detail.getJSONArray("songs").getJSONObject(0).getJSONObject("album")
                .put("picUrl", "https://p2.music.126.net/sample/2.jpg");
        assertEquals("https://p2.music.126.net/sample/2.jpg?param=300y300", NcmSearch.songs(detail).get(0).artwork());
        assertTrue(NcmSearch.songs(new JSONObject("{\"code\":200,\"result\":{\"songCount\":0}}")).isEmpty());
        try { NcmSearch.songs(new JSONObject("{\"code\":200}")); fail(); }
        catch (AmFailure expected) { assertEquals("netease_search_schema", expected.reason); }
    }
    @Test public void missingOrInvalidPictureNeverDropsTheSongOrChangesIdentityStorage() throws Exception {
        var response = new JSONObject("{\"code\":200,\"songs\":[{\"id\":1,\"name\":\"Something Just Like This\","
                + "\"ar\":[{\"name\":\"Coldplay\"}],\"al\":{\"id\":9,\"name\":\"Memories\",\"picUrl\":\"https://evil.example/cover.jpg\"},\"dt\":247000}]}");
        var songs = NcmSearch.songs(response);
        assertEquals(1, songs.size()); assertEquals("", songs.get(0).artwork());
        assertEquals("1", NcmSearch.choose(songs, query("Coldplay", "Memories", 247000), AmIdentity.MatchProfile.STANDARD).id());
        var illustrated = new NcmSearch.Song("1", songs.get(0).title(), "Coldplay", "Memories", "9", 247000,
                "https://p1.music.126.net/sample/1.jpg?param=300y300");
        assertEquals(songs.get(0).json().toString(), illustrated.json().toString());
    }
    @Test public void automaticSearchRejectsCoversAndDifferentRecordingsRatherThanTakingFirstResult() throws Exception {
        var cover = song("1", "Piano Tribute", "Memories", "9", 247000);
        var correct = song("2", "The Chainsmokers/Coldplay", "Memories", "9", 247000);
        var live = song("3", "The Chainsmokers/Coldplay", "Memories", "9", 320000);
        assertEquals(correct, NcmSearch.choose(List.of(cover, live, correct), query("The Chainsmokers/Coldplay", "Memories", 247000), AmIdentity.MatchProfile.STANDARD));
        // Device decision 2026-10-07: without any strict match, the same exact-named album is the album
        // version's cover even though the player's duration metadata disagrees; a foreign artist never is.
        assertEquals(live, NcmSearch.choose(List.of(cover, live), query("The Chainsmokers/Coldplay", "Memories", 247000), AmIdentity.MatchProfile.STANDARD));
        try { NcmSearch.choose(List.of(cover), query("The Chainsmokers/Coldplay", "Memories", 247000), AmIdentity.MatchProfile.STANDARD); fail(); }
        catch (AmFailure expected) { assertEquals(Status.RETRY_LATER, expected.status); }
        try { NcmSearch.choose(List.of(correct), query("", "Memories", 247000), AmIdentity.MatchProfile.STANDARD); fail(); }
        catch (AmFailure expected) { assertEquals(Status.AMBIGUOUS, expected.status); }
    }

    @Test public void songIdentitySurvivesPlayerAlbumAndDurationDrift() throws Exception {
        // Device 2026-10-07: the player reported 259,064 ms for 危险世界 while NetEase lists 374,256 ms.
        var albumVersion = new NcmSearch.Song("28254913", "危险世界", "方大同", "危险世界", "2759704", 374256);
        var request = new ArtworkQuery("危险世界", "方大同", "危险世界", 259064, "", 288, 288, 1280, 1280, 20 * 1024 * 1024);
        assertEquals(albumVersion, NcmSearch.choose(List.of(albumVersion), request, AmIdentity.MatchProfile.STANDARD));
        assertTrue(NcmSearch.accepts(albumVersion, request, AmIdentity.MatchProfile.STANDARD));
        assertTrue(NcmSearch.accepts(albumVersion, request, AmIdentity.MatchProfile.STRICT));
        var otherAlbum = new NcmSearch.Song("1", "危险世界", "方大同", "Other Album", "9", 374256);
        assertEquals(otherAlbum, NcmSearch.choose(List.of(otherAlbum), request, AmIdentity.MatchProfile.STANDARD));
        var secondAlbum = new NcmSearch.Song("2", "危险世界", "方大同", "危险世界", "10", 300000);
        try { NcmSearch.choose(List.of(albumVersion, secondAlbum), request, AmIdentity.MatchProfile.STANDARD); fail(); }
        catch (AmFailure expected) { assertEquals(Status.AMBIGUOUS, expected.status); }
        var renamed = new NcmSearch.Song("3", "危险世界 (Live)", "方大同", "危险世界", "9", 300000);
        try { NcmSearch.choose(List.of(renamed), request, AmIdentity.MatchProfile.STANDARD); fail(); }
        catch (AmFailure expected) { assertEquals("catalog_match_unconfirmed", expected.reason); }
    }

    @Test public void titleOnlySearchStripsTranslationButKeepsRecordingLabels() {
        assertEquals("Something Just Like This", NcmSearch.searchTerm(query("The Chainsmokers/Coldplay", "Memories", 247000)));
        var request = new ArtworkQuery("特别的人 (Special Person)", "方大同", "危险世界", 259064, "", 288, 288, 1280, 1280, 20 * 1024 * 1024);
        assertEquals("特别的人", NcmSearch.searchTerm(request));
        var live = new ArtworkQuery("特别的人 (Live)", "方大同", "现场专辑", 0, "", 288, 288, 1280, 1280, 20 * 1024 * 1024);
        assertEquals("特别的人 (Live)", NcmSearch.searchTerm(live));
    }

    @Test public void missingDurationAndDifferentAlbumDoNotBlockTheOnlyMatchingSong() throws Exception {
        var original = new NcmSearch.Song("28403111", "特别的人", "方大同", "危险世界", "2759704", 259064);
        var request = new ArtworkQuery("特别的人 (Special Person)", "方大同", "本地专辑", 0, "", 288, 288, 1280, 1280, 20 * 1024 * 1024);
        assertEquals(original, NcmSearch.choose(List.of(original), request, AmIdentity.MatchProfile.STRICT));
        assertTrue(NcmSearch.accepts(original, request, AmIdentity.MatchProfile.STRICT));
    }

    @Test public void nativeLanguageAndRerecordingLabelsNeverBecomeTranslatedAliases() {
        var original = new NcmSearch.Song("1", "Song", "Artist", "Album", "9", 200000);
        var request = new ArtworkQuery("Song", "Artist", "Album", 200000, "", 288, 288, 1280, 1280, 20 * 1024 * 1024);
        for (String label : List.of("中文版", "粵語版", "日语版", "重錄", "現場")) {
            var version = new NcmSearch.Song("2", "Song (" + label + ")", "Artist", "Album", "9", 200000);
            var versionQuery = new ArtworkQuery(version.title(), "Artist", "Album", 200000, "", 288, 288, 1280, 1280, 20 * 1024 * 1024);
            for (var profile : List.of(AmIdentity.MatchProfile.STRICT, AmIdentity.MatchProfile.STANDARD, AmIdentity.MatchProfile.LOOSE)) {
                assertFalse(label, NcmSearch.accepts(version, request, profile));
                assertFalse(label, NcmSearch.accepts(original, versionQuery, profile));
                assertTrue(label, NcmSearch.accepts(version, versionQuery, profile));
            }
            assertNotEquals(label, NcmSearch.queryKey(request, "standard"), NcmSearch.queryKey(versionQuery, "standard"));
        }
    }

    @Test public void collaborationCreditOrderAndDuplicateRowsCannotHideTheSongId() throws Exception {
        var original = song("1", "The Chainsmokers/Coldplay", "Memories", "9", 247000);
        var cover = song("2", "Piano Tribute", "Memories", "9", 247000);
        assertEquals(original, NcmSearch.choose(List.of(cover, original, original),
                query("Coldplay/The Chainsmokers", "Different Store Album", 247000), AmIdentity.MatchProfile.STANDARD));
        assertEquals(original, NcmSearch.choose(List.of(original, cover),
                query("The Chainsmokers", "Different Store Album", 247000), AmIdentity.MatchProfile.STANDARD));
    }

    @Test public void englishAliasTitleStillMatchesTheAlbumVersion() throws Exception {
        // Device 2026-10-07: the player reported "特别的人 (Special Person)" for catalog "特别的人".
        var original = new NcmSearch.Song("28403111", "特别的人", "方大同", "危险世界", "2759704", 259064);
        var live = new NcmSearch.Song("30039341", "特别的人 (live)", "方大同", "2015浙江卫视跨年演唱会", "12", 241371);
        var request = new ArtworkQuery("特别的人 (Special Person)", "方大同", "危险世界", 259064, "", 288, 288, 1280, 1280, 20 * 1024 * 1024);
        assertEquals(original, NcmSearch.choose(List.of(live, original), request, AmIdentity.MatchProfile.STANDARD));
        assertTrue(NcmSearch.accepts(original, request, AmIdentity.MatchProfile.STANDARD));
        // A version label never disappears, even on the same album and duration.
        var liveSameAlbum = new NcmSearch.Song("9", "特别的人 (Live)", "方大同", "危险世界", "2759704", 259064);
        try { NcmSearch.choose(List.of(liveSameAlbum), request, AmIdentity.MatchProfile.STANDARD); fail(); }
        catch (AmFailure expected) { assertEquals("catalog_match_unconfirmed", expected.reason); }
    }
    @Test public void ambiguousAlbumVersionsStayAmbiguousAndTheirCachesStaySeparate() throws Exception {
        var original = song("1", "The Chainsmokers/Coldplay", "Memories", "9", 247000);
        var deluxe = song("2", "The Chainsmokers/Coldplay", "Memories (Deluxe)", "10", 247000);
        try { NcmSearch.choose(List.of(original, deluxe), query("The Chainsmokers/Coldplay", "", 247000), AmIdentity.MatchProfile.STANDARD); fail(); }
        catch (AmFailure expected) { assertEquals(Status.AMBIGUOUS, expected.status); }
        var request = query("The Chainsmokers/Coldplay", "Memories", 247000);
        assertNotEquals(NcmSearch.albumKey(original, request), NcmSearch.albumKey(deluxe, request));
        assertNotEquals(NcmSearch.albumKey(original, request), NcmSearch.albumKey(song("3", original.artist(), original.album(), "11", 247000), request));
        assertEquals(NcmSearch.albumKey(original, request), NcmSearch.albumKey(song("4", original.artist(), original.album(), "9", 200000), request));
        assertNotEquals(NcmSearch.queryKey(request, "standard"), NcmSearch.queryKey(request, "strict"));
    }
    @Test public void oldAmBindingsMigrateAndNeteaseBindingStoresRepresentativeSongAndSource() {
        var old = AmBindings.decode("[{\"localAlbum\":\"Local\",\"country\":\"us\",\"albumId\":\"9\",\"title\":\"Memories\",\"artist\":\"Coldplay\"}]").get(0);
        assertFalse(old.netease()); assertEquals("am", old.source());
        var ncm = new AmBindings.Binding("Local", "", "cn", "9", "Memories", "Coldplay", "netease", "2");
        assertEquals(List.of(ncm), AmBindings.decode(AmBindings.encode(List.of(ncm))));
        var all = AmBindings.put(List.of(old), ncm);
        assertEquals(1, all.size()); assertTrue(all.get(0).netease());
        assertFalse(AmBindings.valid(new AmBindings.Binding("Local", "", "cn", "9", "Memories", "Coldplay", "netease", "")));
    }
}
