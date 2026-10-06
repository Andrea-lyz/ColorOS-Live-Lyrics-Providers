package io.github.andrealtb.artwork.am;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;
import io.github.andrealtb.artwork.contract.ArtworkQuery;

public class AmMatchingBoundaryTest {
    static ArtworkQuery query(String title, String artist, String album, long duration) {
        return new ArtworkQuery(title, artist, album, duration, "", 288, 288, 1080, 1080, 20 * 1024 * 1024);
    }
    @Test public void recordingAndNativeLanguageVersionsRemainDistinctOnEveryProfile() {
        var studio = new AmIdentity.Track("1", "10", "Song", "Artist Name", "Album", 200000);
        var original = query("Song", "Artist Name", "Album", 200000);
        for (String suffix : List.of("(中文版本)", "（中文版本）", "[中文版本]", "【现场版】", "(翻唱)", "(伴奏)",
                "(Remix)", "(Live)", "(Acoustic)", "(Demo)", "(Instrumental)", "(Radio Edit)", "(2013 Remaster)", "(with intro)", "(with outro)")) {
            var version = query("Song " + suffix, "Artist Name", "Album", 200000);
            assertNotEquals(suffix, AmCache.key(original,"us"), AmCache.key(version,"us"));
            for (var profile : List.of(AmIdentity.MatchProfile.STRICT, AmIdentity.MatchProfile.STANDARD, AmIdentity.MatchProfile.LOOSE)) {
                assertFalse(suffix, AmIdentity.agrees(studio, version, profile));
                assertFalse(suffix, AmIdentity.agrees(studio, query(version.title, version.artist, "", 200000), profile));
            }
        }
    }
    @Test public void completeAlbumNamesNeverBecomeDisplayTruncations() {
        for (var profile : List.of(AmIdentity.MatchProfile.STRICT, AmIdentity.MatchProfile.STANDARD, AmIdentity.MatchProfile.LOOSE)) {
            for (String edition : List.of("Deluxe", "Live", "Remaster", "Japan", "Vinyl", "Vol. 2", "THE ANTHOLOGY", "Taylor's Version")) {
                assertFalse(edition, AmIdentity.albumClose("A Long Album Name", "A Long Album Name (" + edition + ")", profile));
            }
            assertFalse(AmIdentity.albumClose("abcdefghij", "abcdefghijklmn", profile));
        }
        assertTrue(AmIdentity.albumClose("abcdefghijkl…", "abcdefghijklmn"));
        assertFalse("a truncated catalog candidate is not a complete album", AmIdentity.albumClose("abcdefghijklmn", "abcdefghijkl…"));
    }
    @Test public void nativeArtistAliasesSupportPairedBracketStylesOnlyInArtistFields() {
        for (String value : List.of("MEOVV (미야오)","MEOVV（미야오）","MEOVV [미야오]","MEOVV【미야오】")) {
            assertEquals("meovv", AmIdentity.normalizeArtist(value));
            assertNotEquals("meovv", AmIdentity.normalize(value));
        }
        assertNotEquals(AmIdentity.normalize("Song (现场版)"),AmIdentity.normalize("Song"));
    }
    @Test public void scriptFoldingKeepsAmbiguousCharactersAndVersionWords() {
        assertEquals("周杰伦",AmIdentity.normalizeArtist("周杰倫"));
        assertEquals("叶惠美",AmIdentity.normalize("葉惠美"));
        assertEquals("等你下课",AmIdentity.coreTitle("等你下課"));
        assertNotEquals(AmIdentity.normalize("乾"),AmIdentity.normalize("干"));
        assertEquals("song 现场版",AmIdentity.normalize("Song (現場版)"));
    }
    @Test public void guestCreditsAndTitleRemovalAgreeForBracketedWith() {
        var store = new AmIdentity.Track("1","10","等你下課","周杰倫 & 楊瑞代","等你下課 - Single",270000);
        for (String title : List.of("等你下课 (with 杨瑞代)","等你下课（with 杨瑞代）","等你下课 [with 杨瑞代]")) {
            assertTrue(title,AmIdentity.agrees(store,query(title,"周杰伦","等你下课",270000)));
        }
    }
    @Test public void withInsideTheRealTitleDoesNotBecomeAnUnmarkedGuestCredit() {
        assertEquals("stay with me",AmIdentity.coreTitle("Stay With Me"));
        assertEquals("song with intro",AmIdentity.coreTitle("Song (with intro)"));
        var track=new AmIdentity.Track("1","10","Stay","Sam Smith","Album",200000);
        assertFalse(AmIdentity.agrees(track,query("Stay With Me","Sam Smith","Album",200000)));
    }
    @Test public void sameLanguageFragmentsAndGuestsAreNotMultilingualAliases() {
        assertFalse(AmIdentity.namesOverlap("The Beatles","Song","The","Song"));
        assertFalse(AmIdentity.namesOverlap("Taylor Swift Junior","Song","Taylor Swift","Song"));
        assertFalse(AmIdentity.namesOverlap("Artist One/Artist Two","Song","Artist Two","Song"));
        assertTrue(AmIdentity.namesOverlap("周杰伦 Jay Chou","Song","Jay Chou","Song"));
    }
    @Test public void exactAlbumHasPriorityOverSeveralTolerantCandidateBonuses() throws Exception {
        var query = query("Song","Artist Name 艺人","Rich Man",200000);
        var exactAlbum = new AmIdentity.Track("1","10","Song","Artist Name","Rich Man",200000);
        var typeSuffix = new AmIdentity.Track("2","20","Song","Artist Name 艺人","Rich Man - EP",200000);
        assertTrue(AmIdentity.agrees(exactAlbum,query));
        assertTrue(AmIdentity.agrees(typeSuffix,query));
        assertSame(exactAlbum,AmIdentity.unique(List.of(typeSuffix,exactAlbum),query,"",""));
    }
    @Test public void diagnosticsUseTheSameProfileAsAcceptance() {
        var query = query("Song","Artist Name","Album",200000);
        var track = new AmIdentity.Track("1","10","Song","Artist Name","Album",209000);
        assertTrue(AmIdentity.diagnostics(List.of(track),query,AmIdentity.MatchProfile.STANDARD).contains("completeMatches=1"));
        assertTrue(AmIdentity.diagnostics(List.of(track),query,AmIdentity.MatchProfile.STRICT).contains("completeMatches=0"));
    }
    @Test public void exactAlbumSpellingCannotBypassContradictoryCatalogRating() {
        var track=new AmIdentity.Track("1","10","Song","Artist Name","Album (Clean)",200000,new AmEdition.Info("explicit","2025-01-01",12));
        var query=query("Song","Artist Name","Album (Clean)",200000);
        for(var profile:List.of(AmIdentity.MatchProfile.STRICT,AmIdentity.MatchProfile.STANDARD,AmIdentity.MatchProfile.LOOSE)) {
            assertFalse(AmIdentity.agrees(track,query,profile));
        }
    }
}
