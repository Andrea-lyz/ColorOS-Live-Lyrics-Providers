package io.github.andrealtb.artwork.am;

import java.net.URI;
import java.net.URLEncoder;
import java.util.List;
import java.util.function.BiConsumer;
import io.github.andrealtb.artwork.contract.ArtworkQuery;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

/** Search the named album first, with one song lookup as a bounded discovery fallback. */
final class AmCatalog {
    private static final int MAX_ALBUM_PAGES = 6;
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
            return verified(selected.albumId(), country, query, selected.songId(), "web_song_album", selected.edition());
        }
        if (!query.album.isEmpty()) {
            try { return albumFallback(query, country, ""); }
            catch (AmFailure failure) {
                // Only missing identity evidence merits a song lookup. No motion, transport errors,
                // incomplete pages and ambiguity go straight back to the source router.
                if (!failure.reason.equals("catalog_album_unconfirmed")
                        && !failure.reason.equals("catalog_match_unconfirmed")) throw failure;
                discovery.accept("stage=album_to_song reason=" + failure.reason);
            }
        }
        String term = songSearchTerm(query);
        String uri = "https://itunes.apple.com/search?term=" + encode(term)
                + "&media=music&entity=musicTrack&country=" + searchMarket(country) + "&limit=100";
        List<AmIdentity.Track> tracks = AmPage.itunes(fetch.text(URI.create(uri), 2 * 1024 * 1024));
        diagnostic.accept("itunes_song_fallback", tracks);
        discovery.accept("stage=song_fallback candidates=" + tracks.size() + " round=1");
        java.util.Map<String, AmPage.AlbumCandidate> discovered = new java.util.TreeMap<>();
        rememberAlbums(tracks, query, discovered);
        AmIdentity.Track selected;
        try { selected = AmIdentity.unique(tracks, query, "", "", profile); }
        catch (AmFailure failure) {
            // Other tracks may reveal an album hidden from the album search (e.g. an Explicit
            // release). Reuse that evidence once; do not repeat either catalog search.
            if (!failure.reason.equals("catalog_match_unconfirmed") || discovered.isEmpty()) throw failure;
            return verifyAlbums(List.copyOf(discovered.values()), query, country, "");
        }
        return verified(selected.albumId(), country, query, selected.songId(), "web_song_album", selected.edition());
    }
    /**
     * The iTunes Search API returns an HTTP 200 with an empty body for storefronts that Apple
     * Music serves (CN). Search the US catalog instead: Adam IDs are global, and the album page
     * is still verified in the user's own storefront, where the artwork must actually exist.
     */
    private static String searchMarket(String country) { return "cn".equals(country) ? "us" : country; }
    /** Discovery only: aliases and rating annotations must still pass the actual table check. */
    private static String songSearchTerm(ArtworkQuery query) {
        return (AmIdentity.primaryArtist(query.artist) + " "
                + AmIdentity.ratingBase(AmIdentity.aliasBase(query.title))).trim();
    }
    /** Discover the named album, then verify its actual track table before using its motion cover. */
    private AmPage.Album albumFallback(ArtworkQuery query, String country, String songId) throws AmFailure {
        return verifyAlbums(albumSearch(query, country), query, country, songId);
    }
    private AmPage.Album verifyAlbums(List<AmPage.AlbumCandidate> candidates, ArtworkQuery query,
            String country, String songId) throws AmFailure {
        List<AmPage.AlbumCandidate> albums = candidates.stream()
                .filter(candidate -> AmIdentity.ratingCompatible(query, candidate.edition()))
                .collect(java.util.stream.Collectors.toList());
        discovery.accept("stage=album_evidence eligible=" + albums.size());
        if (albums.size() > MAX_ALBUM_PAGES) throw new AmFailure(Status.AMBIGUOUS, "multiple_album_matches");
        if (albums.isEmpty()) throw new AmFailure(Status.RETRY_LATER,
                query.album.isEmpty() ? "catalog_match_unconfirmed" : "catalog_album_unconfirmed",
                query.album.isEmpty() ? 60_000 : 30_000);
        java.util.ArrayList<AmPage.Album> confirmed = new java.util.ArrayList<>();
        AmFailure unresolved = null;
        for (AmPage.AlbumCandidate candidate : albums) {
            try { confirmed.add(verified(candidate.id(), country, query, songId, "web_album_fallback", candidate.edition())); }
            catch (AmFailure failure) {
                if (failure.status == Status.ERROR || failure.status == Status.NETWORK_BLOCKED || failure.status == Status.AMBIGUOUS) throw failure;
                // Only a complete, successfully parsed table can exclude an album. Transport and
                // schema failures leave a candidate unresolved, even if another album is confirmed.
                if (!failure.reason.equals("catalog_match_unconfirmed")) unresolved = failure;
            }
        }
        if (confirmed.size() > 1) throw new AmFailure(Status.AMBIGUOUS, "multiple_album_matches");
        if (unresolved != null) throw unresolved;
        if (confirmed.isEmpty()) throw new AmFailure(Status.RETRY_LATER, "catalog_match_unconfirmed", 60_000);
        return confirmed.get(0);
    }
    private void rememberAlbums(List<AmIdentity.Track> tracks, ArtworkQuery query,
            java.util.Map<String, AmPage.AlbumCandidate> albums) throws AmFailure {
        if (query.album.isEmpty()) return;
        for (AmIdentity.Track track : tracks) {
            if (!AmIdentity.albumAgrees(query.album, track.album(), track.edition(), profile)
                    || !(AmIdentity.sameArtists(query.artist, query.title, track.artist(), track.title())
                        || AmIdentity.sharesLeadArtist(query.artist, query.title, track.artist(), track.title())
                        || AmIdentity.namesOverlap(query.artist, query.title, track.artist(), track.title()))) continue;
            // A song's release day is not the album's day and cannot prove edition equivalence.
            AmPage.AlbumCandidate candidate = new AmPage.AlbumCandidate(track.albumId(),
                    new AmEdition.Info(track.edition().rating(), "", 0));
            AmPage.AlbumCandidate old = albums.get(candidate.id());
            if (old != null && !old.edition().rating().isEmpty() && !candidate.edition().rating().isEmpty()
                    && !old.edition().rating().equals(candidate.edition().rating()))
                throw new AmFailure(Status.RETRY_LATER, "catalog_album_metadata_unconfirmed", 60_000);
            if (old == null || old.edition().rating().isEmpty()) albums.put(candidate.id(), candidate);
        }
    }
    private List<AmPage.AlbumCandidate> albumSearch(ArtworkQuery query, String country) throws AmFailure {
        // Without an album name the track title stands in: the single/EP it belongs to is still
        // verified by the album page's track table, so a foreign album can never attach.
        String term = query.album.isEmpty() ? songSearchTerm(query) : query.album;
        List<AmPage.AlbumCandidate> matches = AmPage.albumCandidates(fetch.text(albumSearchUri(term, searchMarket(country)), 2 * 1024 * 1024),
                query.album, query.artist, profile);
        discovery.accept("stage=itunes_album matched=" + matches.size() + " market=" + searchMarket(country));
        if (!matches.isEmpty()) return matches;
        // Web search supplies candidates only. Missing edition metadata cannot resolve ambiguity;
        // the selected album's actual track table is still verified by resolve().
        java.util.Map<String, AmPage.AlbumCandidate> ids = new java.util.TreeMap<>();
        List<AmPage.AlbumHit> candidates = webAlbums(fetch, term, country);
        int titleMatches = 0;
        for (AmPage.AlbumHit hit : candidates) {
            String want = AmIdentity.normalize(query.album);
            AmEdition.Info info = new AmEdition.Info(hit.explicit() ? "explicit" : "", hit.releaseDay(), hit.trackCount());
            boolean sameAlbum = want.isEmpty() || AmIdentity.albumAgrees(query.album, hit.title(), info, profile);
            if (sameAlbum) titleMatches++;
            if (sameAlbum
                    && (AmIdentity.sameArtists(query.artist, "", hit.artist(), "")
                        || !AmIdentity.primaryArtist(query.artist).isEmpty()
                                && AmIdentity.primaryArtist(query.artist).equals(AmIdentity.primaryArtist(hit.artist()))
                        || !want.isEmpty() && AmIdentity.namesOverlap(query.artist, "", hit.artist(), "")))
                ids.put(hit.id(), new AmPage.AlbumCandidate(hit.id(), info));
        }
        discovery.accept("stage=web_album candidates=" + candidates.size() + " titleMatches=" + titleMatches
                + " matched=" + ids.size() + " market=" + country);
        return List.copyOf(ids.values());
    }
    /** iTunes can return HTTP 200 with no music in a storefront that Apple Music serves (CN). */
    static List<AmPage.AlbumHit> searchAlbums(Fetch fetch, String term, String country) throws AmFailure {
        // Manual binding searches the selected storefront. CN's iTunes music catalog is not a
        // substitute for the CN Apple Music page, and unrelated US hits must not suppress it.
        if (country.equals("cn")) return webAlbums(fetch, term, country);
        List<AmPage.AlbumHit> hits = AmPage.albumHits(fetch.text(albumSearchUri(term, country), 2 * 1024 * 1024));
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
        try { AmIdentity.unique(album.tracks(), query, song, album.id(), profile); }
        catch (AmFailure failure) {
            if (failure.reason.equals("catalog_match_unconfirmed") && album.skippedTracks() > 0)
                throw new AmFailure(Status.RETRY_LATER, "album_tracks_incomplete", 60_000);
            throw failure;
        }
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
        return verified(id, country, query, song, stage, AmEdition.Info.UNKNOWN);
    }
    private AmPage.Album verified(String id, String country, ArtworkQuery query, String song, String stage, AmEdition.Info info) throws AmFailure {
        String market = searchMarket(country);
        AmPage.Album album;
        try {
            album = page(id, country);
        } catch (AmFailure failure) {
            if (failure.status != Status.RETRY_LATER || market.equals(country)
                    || !(failure.reason.equals("upstream_not_found") || failure.reason.equals("web_schema_changed"))) throw failure;
            discovery.accept("page_market_fallback id=" + id + " reason=" + failure.reason);
            album = page(id, market);
            stage += "_market_fallback";
        }
        // The page parser has already pinned every row and the header to this exact album ID.
        // Carry its independently discovered album rating into the parsed table and cache snapshot.
        if (!info.equals(AmEdition.Info.UNKNOWN)) album = new AmPage.Album(album.id(), album.tracks().stream().map(track ->
                new AmIdentity.Track(track.songId(), track.albumId(), track.title(), track.artist(), track.album(), track.durationMs(), info))
                    .collect(java.util.stream.Collectors.toList()),
                album.master(), album.skippedTracks());
        return verify(album, query, song, stage);
    }
    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
}
