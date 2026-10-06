package io.github.andrealtb.artwork.am;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;
import io.github.andrealtb.artwork.contract.ArtworkQuery;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

public class AmIdentityTest {
    static ArtworkQuery query(String title, String album, int pixels) {
        return new ArtworkQuery(title, "Taylor Swift", album, 231000, "", pixels, pixels, 1080, 1080, 20 * 1024 * 1024);
    }
    @Test public void matchProfileMovesOnlyDurationAndPrefixThresholds() {
        var track = new AmIdentity.Track("1", "10", "Style", "Taylor Swift", "1989", 231000);
        // 15s drift with the exact title + album: rejected on standard, accepted on loose.
        var drift = new ArtworkQuery("Style", "Taylor Swift", "1989", 231000 + 15000, "", 288, 288, 1080, 1080, 20 * 1024 * 1024);
        assertFalse(AmIdentity.agrees(track, drift, AmIdentity.MatchProfile.STANDARD));
        assertTrue(AmIdentity.agrees(track, drift, AmIdentity.MatchProfile.LOOSE));
        // A non-exact title ("Radio Edit") with 5s drift: only loose lets it through.
        var edit = new ArtworkQuery("Style (Radio Edit)", "Taylor Swift", "1989", 231000 + 5000, "", 288, 288, 1080, 1080, 20 * 1024 * 1024);
        assertFalse(AmIdentity.agrees(track, edit, AmIdentity.MatchProfile.STRICT));
        assertFalse(AmIdentity.agrees(track, edit, AmIdentity.MatchProfile.STANDARD));
        assertTrue(AmIdentity.agrees(track, edit, AmIdentity.MatchProfile.LOOSE));
        // A wrong artist is rejected on every level, loose included.
        var wrong = new AmIdentity.Track("2", "10", "Style", "Someone Else", "1989", 231000);
        assertFalse(AmIdentity.agrees(wrong, new ArtworkQuery("Style", "Taylor Swift", "1989", 231000, "", 288, 288, 1080, 1080, 20 * 1024 * 1024),
                AmIdentity.MatchProfile.LOOSE));
        // Truncated album prefix ratio: 50% passes loose only, 70% passes standard but not strict,
        // and "1989" vs "1989 (Taylor's Version)" stays apart everywhere.
        assertFalse(AmIdentity.albumClose("abcdefgh", "abcdefghijklmnop", AmIdentity.MatchProfile.STANDARD));
        assertTrue(AmIdentity.albumClose("abcdefgh", "abcdefghijklmnop", AmIdentity.MatchProfile.LOOSE));
        assertFalse(AmIdentity.albumClose("abcdefghij", "abcdefghijklmn", AmIdentity.MatchProfile.STRICT));
        assertTrue(AmIdentity.albumClose("abcdefghij", "abcdefghijklmn", AmIdentity.MatchProfile.STANDARD));
        for (var profile : List.of(AmIdentity.MatchProfile.STRICT, AmIdentity.MatchProfile.STANDARD, AmIdentity.MatchProfile.LOOSE)) {
            assertFalse(AmIdentity.albumClose("1989", "1989 (Taylor's Version)", profile));
        }
        assertSame(AmIdentity.MatchProfile.STRICT, AmIdentity.MatchProfile.forLevel("strict"));
        assertSame(AmIdentity.MatchProfile.STANDARD, AmIdentity.MatchProfile.forLevel("standard"));
        assertSame(AmIdentity.MatchProfile.LOOSE, AmIdentity.MatchProfile.forLevel("loose"));
        assertSame(AmIdentity.MatchProfile.STANDARD, AmIdentity.MatchProfile.forLevel("whatever"));
    }
    @Test public void sameDurationDoesNotMakeOriginalAndRerecordEquivalent() {
        assertFalse(AmIdentity.agrees(new AmIdentity.Track("1", "10", "Style", "Taylor Swift", "1989", 231000),
                query("Style (Taylor's Version)", "1989 (Taylor's Version)", 288)));
    }
    @Test public void playerListingBothArtistsMatchesStoreGuestCreditInTitle() {
        // Device log: "Fortnight" matched title, album and duration but never the artist.
        var store = new AmIdentity.Track("1", "10", "Fortnight (feat. Post Malone)", "Taylor Swift",
                "THE TORTURED POETS DEPARTMENT", 228966);
        for (String artist : List.of("Taylor Swift/Post Malone", "Taylor Swift; Post Malone", "Taylor Swift & Post Malone",
                "Taylor Swift\u3001Post Malone", "Taylor Swift\uFF0FPost Malone", "Taylor Swift feat. Post Malone")) {
            assertTrue(artist, AmIdentity.agrees(store, new ArtworkQuery("Fortnight (feat. Post Malone)", artist,
                    "THE TORTURED POETS DEPARTMENT", 228966, "", 288, 288, 1080, 1080, 20 * 1024 * 1024)));
        }
    }
    @Test public void creditedSetsMustBeEqualUnlessTheAlbumAnchorsTheTrack() {
        var store = new AmIdentity.Track("1", "10", "Fortnight (feat. Post Malone)", "Taylor Swift",
                "THE TORTURED POETS DEPARTMENT", 228966);
        // Without an album to anchor it, differing guest lists stay different artists.
        assertFalse(AmIdentity.agrees(store, new ArtworkQuery("Fortnight (feat. Post Malone)", "Taylor Swift/Ed Sheeran",
                "", 228966, "", 288, 288, 1080, 1080, 20 * 1024 * 1024)));
        // Inside the named album the lead artist decides; inaccurate guest tags still bind the album's cover.
        assertTrue(AmIdentity.agrees(store, new ArtworkQuery("Fortnight (feat. Post Malone)", "Taylor Swift/Ed Sheeran",
                "THE TORTURED POETS DEPARTMENT", 228966, "", 288, 288, 1080, 1080, 20 * 1024 * 1024)));
        assertFalse(AmIdentity.sameArtists("Taylor Swift", "Exile", "Taylor Swift & Bon Iver", "Exile"));
        assertFalse(AmIdentity.sameArtists("Post Malone", "Fortnight (feat. Post Malone)", "Taylor Swift",
                "Fortnight (feat. Post Malone)"));
        assertTrue(AmIdentity.sameArtists("Simon & Garfunkel", "The Boxer", "Simon & Garfunkel", "The Boxer"));
    }
    @Test public void albumTrackWithGuestsWrittenDifferentlyStillBindsItsAlbum() {
        // User case: an album mostly by one artist, one track listed as "Taylor Swift/Ed Sheeran/Future".
        var store = new AmIdentity.Track("1", "10", "End Game (feat. Ed Sheeran & Future)", "Taylor Swift", "reputation", 244821);
        for (String[] player : new String[][] {
                {"End Game", "Taylor Swift/Ed Sheeran/Future"}, {"End Game", "Taylor Swift"},
                {"End Game", "Ed Sheeran/Taylor Swift/Future"}, {"End Game (feat. Ed Sheeran & Future)", "Taylor Swift/Ed Sheeran/Future"},
                {"End Game feat. Ed Sheeran & Future", "Taylor Swift, Ed Sheeran, Future"}}) {
            assertTrue(player[0] + " / " + player[1], AmIdentity.agrees(store, new ArtworkQuery(player[0], player[1], "reputation",
                    244821, "", 288, 288, 1080, 1080, 20 * 1024 * 1024)));
        }
    }
    @Test public void albumCentricMatchKeepsAlbumVersionAndLeadArtist() {
        var store = new AmIdentity.Track("1", "10", "End Game (feat. Ed Sheeran & Future)", "Taylor Swift", "reputation", 244821);
        // No album: the album cannot anchor a looser track identity.
        assertFalse(AmIdentity.agrees(store, new ArtworkQuery("End Game", "Taylor Swift/Ed Sheeran/Future", "",
                244821, "", 288, 288, 1080, 1080, 20 * 1024 * 1024)));
        // Another album of the same name family.
        assertFalse(AmIdentity.agrees(store, new ArtworkQuery("End Game", "Taylor Swift", "Taylor Swift Karaoke: reputation",
                244821, "", 288, 288, 1080, 1080, 20 * 1024 * 1024)));
        // A guest's own listing does not take over the album.
        assertFalse(AmIdentity.agrees(store, new ArtworkQuery("End Game", "Ed Sheeran", "reputation",
                244821, "", 288, 288, 1080, 1080, 20 * 1024 * 1024)));
        // Version words stay part of the title.
        var karaoke = new AmIdentity.Track("2", "20", "End Game (feat. Ed Sheeran & Future) [Karaoke Version]", "Taylor Swift",
                "reputation", 244821);
        assertFalse(AmIdentity.agrees(karaoke, new ArtworkQuery("End Game", "Taylor Swift", "reputation",
                244821, "", 288, 288, 1080, 1080, 20 * 1024 * 1024)));
        assertEquals("style taylor s version", AmIdentity.coreTitle("Style (Taylor's Version)"));
    }
    @Test public void exactAlbumSelectsEdition() throws Exception {
        var original = new AmIdentity.Track("1", "10", "Style", "Taylor Swift", "1989", 231000);
        var deluxe = new AmIdentity.Track("2", "20", "Style", "Taylor Swift", "1989 (Deluxe Edition)", 231000);
        assertEquals(original, AmIdentity.unique(List.of(original, deluxe), query("Style", "1989", 288), "", ""));
    }
    @Test public void editionSuffixWithoutRerecordMarkerKeepsTheSameAlbum() {
        var explicit = new AmIdentity.Track("1", "10", "Style", "Taylor Swift", "1989 (Explicit)", 231000);
        var clean = new AmIdentity.Track("2", "20", "Style", "Taylor Swift", "1989 (Clean)", 231000);
        for (var store : List.of(explicit, clean)) {
            assertTrue(AmIdentity.agrees(store, query("Style", "1989", 288)));
            assertTrue(AmIdentity.agrees(store, query("Style", "1989 (Explicit)", 288)));
        }
        assertTrue(AmIdentity.albumClose("1989", "1989 (Explicit)"));
        assertTrue(AmIdentity.albumClose("1989", "1989 explicit"));
        assertTrue(AmIdentity.albumClose("1989 (Deluxe Edition)", "1989"));
        assertTrue(AmIdentity.albumClose("1989", "1989 (3am Edition)"));
        assertTrue(AmIdentity.albumClose("1989", "1989 (Mastered for iTunes)"));
        assertTrue(AmIdentity.albumClose("1989", "1989 (2024 Remaster)"));
        assertFalse(AmIdentity.albumClose("1989", "1989 (Taylor's Version)"));
    }
    @Test public void symbolAndVersionSuffixInTitleBindsTheAlbumCover() {
        var explicit = new AmIdentity.Track("1", "10", "Shake It Off (Explicit)", "Taylor Swift", "1989", 231000);
        var remix = new AmIdentity.Track("2", "20", "S&M (Remix)", "Rihanna", "Loud", 242000);
        var remaster = new AmIdentity.Track("3", "30", "Hotel California (2013 Remaster)", "Eagles", "Hotel California", 391000);
        assertTrue(AmIdentity.agrees(explicit, new ArtworkQuery("Shake It Off", "Taylor Swift", "1989", 231000, "", 288, 288, 1080, 1080, 20 * 1024 * 1024)));
        assertTrue(AmIdentity.agrees(remix, new ArtworkQuery("S&M", "Rihanna", "Loud", 242000, "", 288, 288, 1080, 1080, 20 * 1024 * 1024)));
        assertTrue(AmIdentity.agrees(remaster, new ArtworkQuery("Hotel California", "Eagles", "Hotel California", 391000, "", 288, 288, 1080, 1080, 20 * 1024 * 1024)));
        // Without the album an explicit/clean rating still matches: it is a content rating, not a
        // different recording (both sides of the same cover). Remix stays part of the title.
        assertTrue(AmIdentity.agrees(explicit, new ArtworkQuery("Shake It Off", "Taylor Swift", "", 231000, "", 288, 288, 1080, 1080, 20 * 1024 * 1024)));
        assertFalse(AmIdentity.agrees(remix, new ArtworkQuery("S&M", "Rihanna", "", 242000, "", 288, 288, 1080, 1080, 20 * 1024 * 1024)));
        // A foreign suffix such as Karaoke Version never collapses.
        var karaoke = new AmIdentity.Track("4", "40", "Shake It Off [Karaoke Version]", "Taylor Swift", "1989", 231000);
        assertFalse(AmIdentity.agrees(karaoke, new ArtworkQuery("Shake It Off", "Taylor Swift", "1989", 231000, "", 288, 288, 1080, 1080, 20 * 1024 * 1024)));
    }
    @Test public void multilingualArtistNameCollapsesBehindTheAlbum() {
        var store = new AmIdentity.Track("1", "10", "晴天", "周杰伦 Jay Chou", "葉惠美", 264000);
        for (String artist : List.of("Jay Chou", "周杰伦", "周杰伦 Jay Chou")) {
            assertTrue(artist, AmIdentity.agrees(store, new ArtworkQuery("晴天", artist, "葉惠美", 264000, "", 288, 288, 1080, 1080, 20 * 1024 * 1024)));
        }
        // Without the album a name subset is not trusted.
        assertFalse(AmIdentity.agrees(store, new ArtworkQuery("晴天", "Jay Chou", "", 264000, "", 288, 288, 1080, 1080, 20 * 1024 * 1024)));
        // Unrelated artists share no names.
        assertFalse(AmIdentity.agrees(store, new ArtworkQuery("晴天", "JJ Lin", "葉惠美", 264000, "", 288, 288, 1080, 1080, 20 * 1024 * 1024)));
    }
    @Test public void missingAlbumWithMultipleEditionsIsAmbiguous() throws Exception {
        try {
            AmIdentity.unique(List.of(new AmIdentity.Track("1", "10", "Style", "Taylor Swift", "1989", 231000),
                    new AmIdentity.Track("2", "20", "Style", "Taylor Swift", "1989 Deluxe", 231000)), query("Style", "", 288), "", "");
            fail();
        } catch (AmFailure failure) { assertEquals(Status.AMBIGUOUS, failure.status); }
    }
    @Test public void unicodeNamesDoNotCollapseToEmpty() {
        assertEquals("周杰伦 七里香", AmIdentity.normalize("周杰伦《七里香》"));
        assertNotEquals(AmIdentity.normalize("青花瓷"), AmIdentity.normalize("七里香"));
        assertEquals("宇多田ヒカル", AmIdentity.normalize("宇多田ヒカル"));
    }
    @Test public void nativeAliasInArtistNameIsDroppedButLatinSuffixStays() {
        assertEquals("meovv", AmIdentity.normalize("MEOVV (미야오)"));
        assertEquals("aespa", AmIdentity.normalize("aespa (에스파)"));
        assertEquals("bts", AmIdentity.normalize("BTS (방탄소년단)"));
        assertEquals("hoshimachi suisei", AmIdentity.normalize("Hoshimachi Suisei (星街すいせい)"));
        // "(Explicit)" is a content-rating, not a recording variant: it is dropped like "(Clean)".
        assertEquals("1989", AmIdentity.normalize("1989 (Explicit)"));
        assertEquals("1989", AmIdentity.normalize("1989 (Clean)"));
        // Rerecording and digit labels stay.
        assertEquals("1989 taylor s version", AmIdentity.normalize("1989 (Taylor's Version)"));
        assertEquals("2024", AmIdentity.normalize("(2024)"));
    }
    @Test public void explicitTitleMatchesWithAndWithoutAlbum() {
        var store = new AmIdentity.Track("1", "10", "Infinite Dream", "Bazzi", "Infinite Dream (Explicit)", 183000);
        for (String title : List.of("Infinite Dream (Explicit)", "Infinite Dream (Clean)", "Infinite Dream")) {
            assertTrue(title, AmIdentity.agrees(store, new ArtworkQuery(title, "Bazzi", "Infinite Dream (Explicit)", 183000, "", 288, 288, 1080, 1080, 20 * 1024 * 1024)));
            // No album: the explicit/clean rating alone still cannot hide the same recording.
            assertTrue(title, AmIdentity.agrees(store, new ArtworkQuery(title, "Bazzi", "", 183000, "", 288, 288, 1080, 1080, 20 * 1024 * 1024)));
        }
        var longTitle = new AmIdentity.Track("2", "20", "There's Always More That I Could Say", "Sigrid", "There's Always More That I Could Say (Explicit)", 232000);
        assertTrue(AmIdentity.agrees(longTitle, new ArtworkQuery("There's Always More That I Could Say (Explicit)", "Sigrid",
                "There's Always More That I Could Say (Explicit)", 232000, "", 288, 288, 1080, 1080, 20 * 1024 * 1024)));
    }
    @Test public void albumTypeSuffixAndTruncatedPrefixMatchTheAlbum() {
        assertEquals("rich man", AmIdentity.editionBase("Rich Man - The 6th Mini Album"));
        assertEquals("the 4th mini album", AmIdentity.editionBase("The 4th Mini Album")); // a real album title, not a suffix
        assertTrue(AmIdentity.albumClose("Rich Man - The 6th Mini Al", "Rich Man - The 6th Mini Album"));
        assertTrue(AmIdentity.albumClose("Rich Man", "Rich Man - The 6th Mini Album"));
        assertFalse(AmIdentity.albumClose("1989", "1989 (Taylor's Version)"));
        assertFalse(AmIdentity.albumClose("The 4th Mini Album", "1989"));
    }
    @Test public void screenshotCaseBurningUpAndRichManAutoMatch() {
        var meovv = new AmIdentity.Track("1", "10", "BURNING UP", "MEOVV (미야오)", "BURNING UP - The 1st Mini Album", 203000);
        assertTrue(AmIdentity.agrees(meovv, new ArtworkQuery("BURNING UP", "MEOVV", "BURNING UP - The 1st Mini Album", 203000, "", 288, 288, 1080, 1080, 20 * 1024 * 1024)));
        var aespa = new AmIdentity.Track("2", "20", "Rich Man", "aespa (에스파)", "Rich Man - The 6th Mini Album", 213000);
        assertTrue(AmIdentity.agrees(aespa, new ArtworkQuery("Rich Man", "aespa", "Rich Man - The 6th Mini Album", 213000, "", 288, 288, 1080, 1080, 20 * 1024 * 1024)));
    }
    @Test public void albumSongParameterKeepsBothIdentitiesAndMarket() throws Exception {
        var link = AmIdentity.link("https://music.apple.com/cn/album/album/1713845538?i=1713845746&uo=4");
        assertEquals("cn", link.country()); assertEquals("1713845538", link.albumId()); assertEquals("1713845746", link.songId());
    }
    @Test public void unsafeUrlAndDuplicateIdentityAreRejected() throws Exception {
        for (String url : List.of("https://music.apple.com.evil.test/us/album/a/1", "http://music.apple.com/us/album/a/1",
                "https://user@music.apple.com/us/album/a/1", "https://music.apple.com/us/album/a/1?i=2&i=3")) {
            try { AmIdentity.link(url); fail(url); } catch (AmFailure failure) { assertEquals(Status.UNSUPPORTED, failure.status); }
        }
    }
    @Test public void emptyBoundedSearchIsRetryNotPermanentNoMatch() throws Exception {
        try { AmIdentity.unique(List.of(), query("Style", "1989", 288), "", ""); fail(); }
        catch (AmFailure failure) { assertEquals(Status.RETRY_LATER, failure.status); }
    }
    @Test public void wrongArtistOrUnknownDurationCannotMatch() {
        assertFalse(AmIdentity.agrees(new AmIdentity.Track("1", "10", "Style", "Someone else", "1989", 231000), query("Style", "1989", 288)));
        assertFalse(AmIdentity.agrees(new AmIdentity.Track("1", "10", "Style", "Taylor Swift", "1989", 0), query("Style", "1989", 288)));
    }
}
