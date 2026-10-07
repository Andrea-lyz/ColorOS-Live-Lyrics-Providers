package io.github.andrealtb.artwork.am;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import io.github.andrealtb.artwork.contract.ArtworkQuery;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

final class NcmSearch {
    record Song(String id, String title, String artist, String album, String albumId, long durationMs, String artwork) {
        Song(String id, String title, String artist, String album, String albumId, long durationMs) {
            this(id, title, artist, album, albumId, durationMs, "");
        }
        AmIdentity.Track track() { return new AmIdentity.Track(id, albumId, title, artist, album, durationMs); }
        JSONObject json() throws Exception {
            return new JSONObject().put("id", id).put("name", title).put("artist", artist)
                    .put("album", album).put("albumId", albumId).put("duration", durationMs);
        }
    }
    record Input(String terms, String songId, boolean netease) {}
    private static final Pattern SHARE = Pattern.compile("分享\\s*(.+?)的单曲[《「](.+?)[》」]", Pattern.DOTALL);
    private NcmSearch() {}
    /** Share text already carries the identity, so a short URL need not be visited. */
    static Input input(String text) throws AmFailure {
        text = text.trim();
        Matcher share = SHARE.matcher(text);
        if (share.find()) return new Input(share.group(2).trim() + " " + share.group(1).trim(), "", true);
        if (!text.matches("(?is)^https?://.*")) return new Input(text, "", false);
        try {
            URI uri = URI.create(text);
            if (!"music.163.com".equals(uri.getHost())) {
                if ("163cn.tv".equals(uri.getHost())) throw new AmFailure(Status.UNSUPPORTED, "netease_share_text_required");
                return new Input(text, "", false);
            }
            if (uri.getUserInfo() != null || uri.getPort() != -1) throw new IllegalArgumentException();
            String part = uri.getFragment() == null ? uri.getRawQuery() : URI.create(uri.getFragment()).getRawQuery();
            String path = uri.getFragment() == null ? uri.getPath() : URI.create(uri.getFragment()).getPath();
            if (!List.of("/song", "/m/song", "/song/").contains(path) || part == null) throw new IllegalArgumentException();
            for (String pair : part.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2 && kv[0].equals("id")) {
                    String id = URLDecoder.decode(kv[1], "UTF-8");
                    if (id.matches("[0-9]{1,20}")) return new Input("", id, true);
                }
            }
        } catch (AmFailure failure) { throw failure; }
        catch (Exception invalid) { /* reject malformed song URLs */ }
        throw new AmFailure(Status.UNSUPPORTED, "netease_invalid_link");
    }
    static List<Song> songs(JSONObject response) throws AmFailure {
        try {
            JSONArray songs = response.optJSONArray("songs");
            if (songs == null && response.optJSONObject("result") != null) songs = response.getJSONObject("result").optJSONArray("songs");
            if (songs == null) {
                JSONObject result = response.optJSONObject("result");
                if (result != null && result.optInt("songCount", -1) == 0) return List.of();
                throw new IllegalArgumentException();
            }
            List<Song> list = new ArrayList<>();
            for (int i = 0; i < Math.min(songs.length(), 50); i++) {
                JSONObject song = songs.optJSONObject(i);
                if (song == null) continue;
                JSONObject album = song.optJSONObject("al");
                if (album == null) album = song.optJSONObject("album");
                JSONArray artists = song.optJSONArray("ar");
                if (artists == null) artists = song.optJSONArray("artists");
                StringBuilder credit = new StringBuilder();
                if (artists != null) for (int j = 0; j < Math.min(artists.length(), 20); j++) {
                    JSONObject artist = artists.optJSONObject(j);
                    if (artist == null || artist.optString("name").isEmpty()) continue;
                    if (credit.length() > 0) credit.append('/');
                    credit.append(artist.optString("name"));
                }
                Song item = new Song(song.optString("id"), song.optString("name"), credit.toString(),
                        album == null ? "" : album.optString("name"), album == null ? "" : album.optString("id"),
                        song.optLong("dt", song.optLong("duration")),
                        album == null ? "" : NcmArtwork.thumbnailUrl(album.optString("picUrl"), AmPage.ARTWORK_PX));
                if (valid(item)) list.add(item);
            }
            return list;
        } catch (Exception changed) { throw new AmFailure(Status.RETRY_LATER, "netease_search_schema", 60_000); }
    }
    static boolean valid(Song song) {
        return song.id().matches("[0-9]{1,20}") && song.albumId().matches("[0-9]{1,20}")
                && !song.title().isBlank() && !song.artist().isBlank() && !song.album().isBlank()
                && song.title().length() <= 512 && song.artist().length() <= 512 && song.album().length() <= 512
                && song.durationMs() > 0 && song.durationMs() <= 86_400_000;
    }
    /** A song endpoint needs a song title, not the Apple album query or a concatenated credit list. */
    static String searchTerm(ArtworkQuery query) {
        return AmIdentity.aliasBase(query.title);
    }
    static Song choose(List<Song> songs, ArtworkQuery query, AmIdentity.MatchProfile profile) throws AmFailure {
        if (query.title.isEmpty() || query.artist.isEmpty()) throw new AmFailure(Status.AMBIGUOUS, "identity_fields_missing");
        java.util.Map<String, Song> candidates = new java.util.TreeMap<>();
        for (Song song : songs) if (valid(song) && accepts(song, query, profile)) candidates.putIfAbsent(song.id(), song);
        Song best = null;
        int bestScore = -1;
        boolean tied = false;
        for (Song song : candidates.values()) {
            int score = score(song, query, profile);
            if (score > bestScore) { best = song; bestScore = score; tied = false; }
            else if (score == bestScore) tied = true;
        }
        if (best == null) throw new AmFailure(Status.RETRY_LATER, "catalog_match_unconfirmed", 60_000);
        if (tied) throw new AmFailure(Status.AMBIGUOUS, "multiple_catalog_matches");
        return best;
    }
    /** Optional player album/duration help disambiguate; neither is a prerequisite for a song ID. */
    private static int score(Song song, ArtworkQuery query, AmIdentity.MatchProfile profile) {
        int score = AmIdentity.sameArtists(query.artist, query.title, song.artist(), song.title()) ? 16 : 0;
        if (!query.album.isEmpty()) {
            if (AmIdentity.normalize(query.album).equals(AmIdentity.normalize(song.album()))) score += 8;
            else if (AmIdentity.albumClose(query.album, song.album(), profile)) score += 4;
        }
        if (query.durationMs > 0) {
            long delta = Math.abs(song.durationMs() - query.durationMs);
            if (delta <= profile.exactDeltaMs) score += 2;
            else if (delta <= profile.albumDeltaMs) score++;
        }
        return score;
    }
    /** Keep recording labels and credited artists; a translated title annotation is only an alias. */
    static boolean accepts(Song song, ArtworkQuery query, AmIdentity.MatchProfile profile) {
        String title = AmIdentity.coreTitle(AmIdentity.aliasBase(query.title));
        return !title.isEmpty() && title.equals(AmIdentity.coreTitle(AmIdentity.aliasBase(song.title())))
                && (AmIdentity.sameArtists(query.artist, query.title, song.artist(), song.title())
                    || AmIdentity.sharesLeadArtist(query.artist, query.title, song.artist(), song.title()));
    }
    static String albumKey(Song song, ArtworkQuery query) {
        return albumKey(song.album(), song.albumId(), query.maxWidth, query.maxHeight, query.maxFileBytes);
    }
    static String albumKey(String album, String albumId, int maxWidth, int maxHeight, long maxFileBytes) {
        return AmCache.hash("netease-album-native-v1\n" + AmIdentity.normalize(album) + "\n" + albumId
                + "\n" + maxWidth + "x" + maxHeight + "\n" + maxFileBytes);
    }
    /** The endpoint answers for one song; its absence/failure cannot exclude every song on an album. */
    static String failureKey(Song song, ArtworkQuery query) {
        return AmCache.hash("netease-song-failure-v1\n" + song.id() + "\n" + albumKey(song, query));
    }
    static String queryKey(ArtworkQuery query, String level) {
        return AmCache.hash("netease-query-v3\n" + level + "\n" + AmIdentity.normalize(AmIdentity.aliasBase(query.title)) + "\n"
                + AmIdentity.normalizeArtist(query.artist) + "\n" + AmIdentity.normalize(query.album) + "\n" + query.durationMs);
    }
}
