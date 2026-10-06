package io.github.andrealtb.artwork.am;

import java.net.URI;
import java.net.URLEncoder;
import java.util.List;
import java.util.function.BiConsumer;
import io.github.andrealtb.artwork.contract.ArtworkQuery;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

/** Candidate discovery may fall back to an exact album search; every path verifies the actual track table. */
final class AmCatalog {
    interface Fetch { String text(URI uri, int limit) throws AmFailure; }
    private final Fetch fetch;
    private final BiConsumer<String, List<AmIdentity.Track>> diagnostic;
    private final java.util.function.Consumer<String> discovery;
    private final AmIdentity.MatchProfile profile;
    AmCatalog(Fetch fetch, BiConsumer<String, List<AmIdentity.Track>> diagnostic) { this(fetch, diagnostic, detail -> {}); }
    AmCatalog(Fetch fetch, BiConsumer<String, List<AmIdentity.Track>> diagnostic, java.util.function.Consumer<String> discovery) {
        this(fetch, diagnostic, discovery, AmIdentity.MatchProfile.STANDARD);
    }
    AmCatalog(Fetch fetch, BiConsumer<String, List<AmIdentity.Track>> diagnostic, java.util.function.Consumer<String> discovery,
            AmIdentity.MatchProfile profile) {
        this.fetch = fetch; this.diagnostic = diagnostic; this.discovery = discovery; this.profile = profile;
    }
    AmPage.Album resolve(ArtworkQuery query, AmIdentity.AppleLink link, String country, AmPage.Album known) throws AmFailure {
        if (known != null && (link == null || link.albumId().isEmpty() || link.albumId().equals(known.id()))) {
            return verify(known, query, link == null ? "" : link.songId(), "catalog_cache");
        }
        if (link != null && !link.albumId().isEmpty()) return verified(link.albumId(), country, query, link.songId(), "web_link");
        String songId = link == null ? "" : link.songId();
        if (link != null) {
            // A pasted song link pins the store track id: exact lookup, no search fallback.
            String uri = "https://itunes.apple.com/lookup?id=" + link.songId() + "&entity=song&country=" + searchMarket(country);
            List<AmIdentity.Track> tracks = AmPage.itunes(fetch.text(URI.create(uri), 2 * 1024 * 1024));
            diagnostic.accept("itunes_song", tracks);
            AmIdentity.Track selected;
            try { selected = AmIdentity.unique(tracks, query, songId, "", profile); }
            catch (AmFailure failure) {
                discovery.accept("song_match=" + failure.reason + " candidates=" + tracks.size());
                if (failure.status != Status.RETRY_LATER || query.album.isEmpty()) throw failure;
                return albumFallback(query, country, songId);
            }
            return verified(selected.albumId(), country, query, selected.songId(), "web_song_album");
        }
        // No exact link: search the store with progressively broader terms. Every round still
        // verifies the track table, so broader terms can never attach a foreign album.
        AmIdentity.Track selected = null;
        for (String term : searchTerms(query)) {
            String uri = "https://itunes.apple.com/search?term=" + encode(term) + "&media=music&entity=musicTrack&country=" + searchMarket(country) + "&limit=100";
            List<AmIdentity.Track> tracks = AmPage.itunes(fetch.text(URI.create(uri), 2 * 1024 * 1024));
            diagnostic.accept(selected == null ? "itunes_song" : "itunes_song_fallback", tracks);
            try { selected = AmIdentity.unique(tracks, query, "", "", profile); break; }
            catch (AmFailure failure) {
                discovery.accept("song_match=" + failure.reason + " candidates=" + tracks.size() + " term=" + term);
                if (failure.status != Status.RETRY_LATER) throw failure; // ambiguity or schema: broader terms add nothing
            }
        }
        if (selected == null) return albumFallback(query, country, "");
        return verified(selected.albumId(), country, query, selected.songId(), "web_song_album");
    }
    /**
     * The iTunes Search API returns an HTTP 200 with an empty body for storefronts that Apple
     * Music serves (CN). Search the US catalog instead: Adam IDs are global, and the album page
     * is still verified in the user's own storefront, where the artwork must actually exist.
     */
    private static String searchMarket(String country) { return "cn".equals(country) ? "us" : country; }
    /** Search terms from the most specific to the broadest; each round still verifies the track table. */
    private static List<String> searchTerms(ArtworkQuery query) {
        java.util.LinkedHashSet<String> terms = new java.util.LinkedHashSet<>();
        String raw = (query.artist + " " + query.title + (query.album.isEmpty() ? "" : " " + query.album)).trim();
        if (!raw.isEmpty()) terms.add(raw);
        // The normalized form drops "(Explicit)" and stray punctuation that pollutes the search.
        String clean = AmIdentity.normalize(raw);
        if (!clean.isEmpty()) terms.add(clean);
        String artistTitle = (query.artist + " " + query.title).trim();
        if (!artistTitle.isEmpty()) terms.add(artistTitle);
        String titleArtist = (query.title + " " + query.artist).trim();
        if (!titleArtist.isEmpty()) terms.add(titleArtist);
        String core = AmIdentity.coreTitle(query.title);
        String lead = AmIdentity.primaryArtist(query.artist);
        String coreLead = (core + (lead.isEmpty() ? "" : " " + lead)).trim();
        if (!coreLead.isEmpty()) terms.add(coreLead);
        String title = query.title.trim();
        if (!title.isEmpty()) terms.add(title);
        return new java.util.ArrayList<>(terms);
    }
    /** Track search exhausted: the named album itself is verified; its track table still gates the result. */
    private AmPage.Album albumFallback(ArtworkQuery query, String country, String songId) throws AmFailure {
        List<String> albums = albumSearch(query.artist, query, country);
        // An album is credited to its lead artist; a full guest list can keep the album out of the results.
        String lead = AmIdentity.primaryArtist(query.artist);
        if (albums.isEmpty() && !lead.isEmpty() && !lead.equals(AmIdentity.normalize(query.artist))) {
            albums = albumSearch(lead, query, country);
        }
        if (albums.size() > 1) throw new AmFailure(Status.AMBIGUOUS, "multiple_album_matches");
        if (albums.isEmpty()) throw new AmFailure(Status.RETRY_LATER,
                query.album.isEmpty() ? "catalog_match_unconfirmed" : "catalog_album_unconfirmed",
                query.album.isEmpty() ? 60_000 : 30_000);
        return verified(albums.get(0), country, query, songId, "web_album_fallback");
    }
    private List<String> albumSearch(String artist, ArtworkQuery query, String country) throws AmFailure {
        // Without an album name the track title stands in: the single/EP it belongs to is still
        // verified by the album page's track table, so a foreign album can never attach.
        String term = artist + " " + (query.album.isEmpty() ? query.title : query.album);
        List<String> matches = AmPage.albumIds(fetch.text(albumSearchUri(term, searchMarket(country)), 2 * 1024 * 1024), query.album, query.artist);
        discovery.accept("stage=itunes_album matched=" + matches.size() + " market=" + searchMarket(country));
        if (!matches.isEmpty()) return matches;
        // Web search supplies candidates only. Missing edition metadata cannot resolve ambiguity;
        // the selected album's actual track table is still verified by resolve().
        java.util.Set<String> ids = new java.util.TreeSet<>();
        List<AmPage.AlbumHit> candidates = webAlbums(fetch, term, country);
        int titleMatches = 0;
        for (AmPage.AlbumHit hit : candidates) {
            String want = AmIdentity.normalize(query.album), have = AmIdentity.normalize(hit.title());
            boolean sameAlbum = want.isEmpty() || want.equals(have) || AmIdentity.albumClose(want, have);
            if (sameAlbum) titleMatches++;
            if (sameAlbum
                    && (AmIdentity.sameArtists(query.artist, "", hit.artist(), "")
                        || !AmIdentity.primaryArtist(query.artist).isEmpty()
                            && AmIdentity.primaryArtist(query.artist).equals(AmIdentity.primaryArtist(hit.artist())))) ids.add(hit.id());
        }
        discovery.accept("stage=web_album candidates=" + candidates.size() + " titleMatches=" + titleMatches
                + " matched=" + ids.size() + " market=" + country);
        return List.copyOf(ids);
    }
    /** iTunes can return HTTP 200 with no music in a storefront that Apple Music serves (CN). */
    static List<AmPage.AlbumHit> searchAlbums(Fetch fetch, String term, String country) throws AmFailure {
        List<AmPage.AlbumHit> hits = AmPage.albumHits(fetch.text(albumSearchUri(term, searchMarket(country)), 2 * 1024 * 1024));
        return hits.isEmpty() ? webAlbums(fetch, term, country) : hits;
    }
    private static List<AmPage.AlbumHit> webAlbums(Fetch fetch, String term, String country) throws AmFailure {
        URI uri = URI.create("https://music.apple.com/" + country + "/search?term=" + encode(term));
        return AmPage.searchAlbums(fetch.text(uri, 3 * 1024 * 1024), country);
    }
    static URI albumSearchUri(String term, String country) {
        return URI.create("https://itunes.apple.com/search?term=" + encode(term) + "&media=music&entity=album&country=" + country + "&limit=25");
    }
    /** Display details (title, art) of an album pasted as a link; never used for matching. */
    static URI albumLookupUri(String country, String id) {
        return URI.create("https://itunes.apple.com/lookup?id=" + encode(id) + "&country=" + encode(country));
    }
    static URI pageUri(String country, String id) { return URI.create("https://music.apple.com/" + country + "/album/-/" + id); }
    private AmPage.Album verify(AmPage.Album album, ArtworkQuery query, String song, String stage) throws AmFailure {
        diagnostic.accept(album.skippedTracks() > 0 ? stage + " skippedTracks=" + album.skippedTracks() : stage, album.tracks());
        AmIdentity.unique(album.tracks(), query, song, album.id(), profile);
        return album;
    }
    private AmPage.Album page(String id, String country) throws AmFailure {
        return AmPage.album(fetch.text(pageUri(country, id), 3 * 1024 * 1024), id);
    }
    /**
     * Verify the album page in the user's own storefront first; the artwork must really exist
     * there. Only a page fetch/parse failure retries in the search market once (the Adam ID is
     * global, and an auto match beats a manual bind); a track that is absent from the album's
     * track table is not a market problem and never retries.
     */
    private AmPage.Album verified(String id, String country, ArtworkQuery query, String song, String stage) throws AmFailure {
        String market = searchMarket(country);
        AmPage.Album album;
        try {
            album = page(id, country);
        } catch (AmFailure failure) {
            if (failure.status != Status.RETRY_LATER || market.equals(country)) throw failure;
            discovery.accept("page_market_fallback id=" + id + " reason=" + failure.reason);
            album = page(id, market);
            stage += "_market_fallback";
        }
        return verify(album, query, song, stage);
    }
    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
}
