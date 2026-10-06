package io.github.andrealtb.artwork.am;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.andrealtb.artwork.contract.ArtworkQuery;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

/** Unicode preserving equality. Version words are never removed. */
final class AmIdentity {
    record Track(String songId, String albumId, String title, String artist, String album, long durationMs, AmEdition.Info edition) {
        Track(String songId, String albumId, String title, String artist, String album, long durationMs) {
            this(songId, albumId, title, artist, album, durationMs, AmEdition.Info.UNKNOWN);
        }
    }
    record AppleLink(String country, String albumId, String songId) {}

    /** A bracketed alias containing no Latin letters or digits: "MEOVV (미야오)", "aespa (에스파)". */
    private static final Pattern NATIVE_ALIAS = Pattern.compile("\\([^()]*\\)|\\[[^\\[\\]]*\\]|【[^【】]*】");
    private static final Pattern LATIN_IN_ALIAS = Pattern.compile("[a-zA-Z0-9]");
    /** Rating annotations are interpreted against catalog evidence, never removed by normalize(). */
    private static final Pattern RATING_LABEL = Pattern.compile(
            "\\s*(?:\\(\\s*(explicit|clean)\\s*\\)|\\[\\s*(explicit|clean)\\s*\\]|【\\s*(explicit|clean)\\s*】)\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** Artist-only aliases such as "MEOVV (미야오)"; title and album brackets retain their words. */
    private static String stripNativeAlias(String value) {
        if (value == null || value.isEmpty()) return value;
        Matcher matcher = NATIVE_ALIAS.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String alias = matcher.group(0);
            if (LATIN_IN_ALIAS.matcher(alias).find()) matcher.appendReplacement(buffer, Matcher.quoteReplacement(alias));
            else matcher.appendReplacement(buffer, "");
        }
        matcher.appendTail(buffer);
        return buffer.toString().trim();
    }
    /** "Infinite Dream (Explicit)" -> "Infinite Dream"; "(Clean)" behaves the same. */
    private static String stripRatingLabel(String value) {
        if (value == null || value.isEmpty()) return value;
        return RATING_LABEL.matcher(value).replaceAll("").trim();
    }

    static String normalize(String value) {
        return AmMatchText.normalize(value);
    }
    static String normalizeArtist(String value) {
        return normalize(stripNativeAlias(Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)));
    }
    /** Discovery/pairing helper only; callers must establish the catalog's content rating. */
    static String ratingBase(String value) {
        return normalize(stripRatingLabel(Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)));
    }
    private static String ratingLabel(String value) {
        Matcher matcher = RATING_LABEL.matcher(Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC));
        if (!matcher.find()) return "";
        for (int i = 1; i <= 3; i++) if (matcher.group(i) != null) {
            return matcher.group(i).equalsIgnoreCase("clean") ? "cleaned" : "explicit";
        }
        return "";
    }

    /** A title's guest credit, e.g. "Fortnight (feat. Post Malone)". */
    private static final Pattern FEATURED = Pattern.compile("[(\\[]\\s*(?:feat\\.?|ft\\.?|featuring|with(?!\\s+(?:intro|outro)\\b))\\s+([^)\\]]+)[)\\]]|\\s+(?:feat\\.|ft\\.|featuring)\\s+([^()\\[\\]]+)$",
            Pattern.CASE_INSENSITIVE);
    /** Artist-list separators players and stores use; full-width forms are folded by NFKC first. */
    private static final Pattern CREDIT_SEPARATOR = Pattern.compile("\\s*(?:[/;,\u3001&]|\\b(?:feat|ft)\\b\\.?|\\bfeaturing\\b)\\s*",
            Pattern.CASE_INSENSITIVE);

    /** Only a guest credit, bracketed or trailing; version words such as "Taylor's Version" stay in the title. */
    private static final Pattern GUEST_CLAUSE = FEATURED;

    /** Delimited release-type suffixes only; editions, remasters, regions and volumes stay distinct. */
    private static final Pattern EDITION_TRAILER = Pattern.compile(
            "(?:\\s+[-–—]\\s+|\\s*\\()(?:(?:the\\s+)?\\d{1,2}(?:st|nd|rd|th)?\\s+)?"
                    + "(?:mini\\s+album|full\\s+album|single\\s+album|digital\\s+single|single|ep)\\)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** Title without its guest credit, e.g. "End Game (feat. Ed Sheeran & Future)" and "End Game" agree. */
    static String coreTitle(String title) {
        return normalize(GUEST_CLAUSE.matcher(Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFKC)).replaceAll(""));
    }


    /** Credited artists: the artist field split into names, plus the guests named in the title. */
    static Set<String> credits(String artist, String title) {
        Set<String> names = new TreeSet<>();
        addCredits(artist, names);
        Matcher featured = FEATURED.matcher(Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFKC));
        while (featured.find()) addCredits(featured.group(1) == null ? featured.group(2) : featured.group(1), names);
        return names;
    }
    private static void addCredits(String value, Set<String> names) {
        for (String part : CREDIT_SEPARATOR.split(Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC))) {
            String name = normalizeArtist(part);
            if (!name.isEmpty()) names.add(name);
        }
    }
    /**
     * Same artists: identical fields, or identical credited sets. Device log: a player reporting both
     * artists of "Fortnight (feat. Post Malone)" never matched the store's single "Taylor Swift".
     * Sets must be equal, so a lone primary artist never matches a duet credited to two.
     */
    static boolean sameArtists(String artist, String title, String otherArtist, String otherTitle) {
        String a = normalizeArtist(artist), b = normalizeArtist(otherArtist);
        if (a.isEmpty() || b.isEmpty()) return false;
        return a.equals(b) || credits(artist, title).equals(credits(otherArtist, otherTitle));
    }
    /**
     * One side's lead artist is credited by the other: a full guest list never hides the duet,
     * and a lone store credit never hides the featuring artist the player reports.
     */
    static boolean sharesLeadArtist(String artist, String title, String otherArtist, String otherTitle) {
        String lead = primaryArtist(artist), otherLead = primaryArtist(otherArtist);
        if (lead.isEmpty() || otherLead.isEmpty()) return false;
        if (lead.equals(otherLead)) return true;
        return credits(otherArtist, otherTitle).contains(lead) || credits(artist, title).contains(otherLead);
    }
    /**
     * Multilingual or alias-dotted names: "周杰伦 Jay Chou" and "Jay Chou" credit the same artist
     * when a complete lead name appears in an explicitly mixed-script credit. Same-language
     * fragments and combined guest-token sets are not aliases. Only accepted behind the album.
     */
    static boolean namesOverlap(String artist, String title, String otherArtist, String otherTitle) {
        String a = primaryArtist(artist), b = primaryArtist(otherArtist);
        if (a.isEmpty() || b.isEmpty()) return false;
        return multilingualAlias(a, b) || multilingualAlias(b, a);
    }
    private static boolean multilingualAlias(String longer, String shorter) {
        if (!(" " + longer + " ").contains(" " + shorter + " ") || longer.equals(shorter)) return false;
        String remainder = (" " + longer + " ").replace(" " + shorter + " ", " ").trim();
        boolean shortLatin = shorter.matches("[a-z]+(?: [a-z]+)+");
        boolean shortNative = shorter.matches("[^\\p{IsLatin}\\p{N} ]+");
        return shortLatin && remainder.matches("[^\\p{IsLatin}\\p{N} ]+")
                || shortNative && remainder.matches("[a-z]+(?: [a-z]+)+");
    }
    /** Album name without its release-type descriptors; all edition/recording words survive. */
    static String editionBase(String album) {
        String value = Normalizer.normalize(album == null ? "" : album, Normalizer.Form.NFKC).trim();
        for (;;) {
            Matcher matcher = EDITION_TRAILER.matcher(value);
            if (!matcher.find()) return normalize(value);
            String base = matcher.replaceAll("").trim();
            if (base.isEmpty()) return normalize(value);
            value = base;
        }
    }
    /**
     * Release types may differ. A query ending in an explicit ellipsis may be a long prefix of
     * the complete catalog name; complete names never acquire a guessed truncation marker.
     */
    static boolean albumClose(String a, String b) {
        return albumClose(a, b, MatchProfile.STANDARD);
    }
    static boolean albumClose(String a, String b, MatchProfile profile) {
        if (a.isEmpty() || b.isEmpty()) return false;
        String baseA = editionBase(a), baseB = editionBase(b);
        if (baseA.equals(baseB)) return true;
        String rawA = normalize(a), rawB = normalize(b);
        int shorter = Math.min(rawA.length(), rawB.length());
        // Only the query may be display-truncated; never interpret a complete edition name as a prefix.
        return truncated(a) && rawB.startsWith(rawA) && shorter >= 6
                && shorter * 100 >= Math.max(rawA.length(), rawB.length()) * profile.prefixRatioPct;
    }
    static boolean truncated(String value) {
        String text = value == null ? "" : value.trim();
        return text.endsWith("...") || text.endsWith("…");
    }
    static boolean albumAgrees(String want, String have, AmEdition.Info info, MatchProfile profile) {
        if (normalize(want).equals(normalize(have))) return true;
        String wantRating = ratingLabel(want), haveRating = ratingLabel(have);
        if (!wantRating.isEmpty() && !wantRating.equals(info.rating())
                || !haveRating.isEmpty() && !haveRating.equals(info.rating())) return false;
        return albumClose(wantRating.isEmpty() ? want : stripRatingLabel(Normalizer.normalize(want, Normalizer.Form.NFKC)),
                haveRating.isEmpty() ? have : stripRatingLabel(Normalizer.normalize(have, Normalizer.Form.NFKC)), profile);
    }
    /** First credited name; album-level search only, before the track table is verified. */
    static String primaryArtist(String artist) { return normalizeArtist(leadCredit(artist)); }
    /** The first credited name as written, for search terms shown to the user. */
    static String leadCredit(String artist) {
        for (String part : CREDIT_SEPARATOR.split(Normalizer.normalize(artist == null ? "" : artist, Normalizer.Form.NFKC))) {
            if (!normalizeArtist(part).isEmpty()) return part.trim();
        }
        return "";
    }

    static AppleLink link(String value) throws AmFailure {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"music.apple.com".equalsIgnoreCase(uri.getHost())
                    || uri.getPort() != -1 || uri.getRawUserInfo() != null || uri.getFragment() != null) throw new IllegalArgumentException();
            String[] parts = uri.getPath().split("/");
            if (parts.length < 4 || !parts[1].matches("[a-zA-Z]{2}")
                    || !(parts[2].equals("album") || parts[2].equals("song"))) throw new IllegalArgumentException();
            String id = parts[parts.length - 1];
            if (!id.matches("[0-9]{1,20}")) throw new IllegalArgumentException();
            String song = parts[2].equals("song") ? id : "";
            if (uri.getRawQuery() != null) for (String arg : uri.getRawQuery().split("&")) {
                String[] pair = arg.split("=", 2);
                if (URLDecoder.decode(pair[0], "UTF-8").equals("i")) {
                    if (pair.length != 2 || !pair[1].matches("[0-9]{1,20}") || !song.isEmpty()) throw new IllegalArgumentException();
                    song = pair[1];
                }
            }
            return new AppleLink(parts[1].toLowerCase(Locale.ROOT), parts[2].equals("album") ? id : "", song);
        } catch (Exception error) { throw new AmFailure(Status.UNSUPPORTED, "invalid_apple_link"); }
    }

    /**
     * Matching tolerance dial. The standard level is the calibrated default; strict and loose move
     * only the duration and truncated-album-prefix thresholds, never the identity rules (a wrong
     * artist, album or recording is rejected on every level).
     */
    public static final class MatchProfile {
        public final long exactDeltaMs;
        public final long albumDeltaMs;
        public final int prefixRatioPct;
        public MatchProfile(long exactDeltaMs, long albumDeltaMs, int prefixRatioPct) {
            this.exactDeltaMs = exactDeltaMs; this.albumDeltaMs = albumDeltaMs; this.prefixRatioPct = prefixRatioPct;
        }
        public static final MatchProfile STRICT = new MatchProfile(2000, 8000, 75);
        public static final MatchProfile STANDARD = new MatchProfile(3000, 12000, 60);
        public static final MatchProfile LOOSE = new MatchProfile(6000, 20000, 45);
        public static MatchProfile forLevel(String level) {
            if ("strict".equals(level)) return STRICT;
            if ("loose".equals(level)) return LOOSE;
            return STANDARD;
        }
    }

    /**
     * The track answers the query. Strict equality first (title, credited set, album, duration);
     * then bounded tolerance: a guest list that credits the same lead artist, a multilingual or
     * mixed-script lead name, a corroborated rating annotation, and a configured duration drift
     * when the exact title is backed by the album. Recording and edition labels remain distinct.
     * Nothing weaker than that is ever accepted.
     */
    static boolean agrees(Track track, ArtworkQuery query) {
        return agrees(track, query, MatchProfile.STANDARD);
    }
    static boolean agrees(Track track, ArtworkQuery query, MatchProfile profile) {
        String title = normalize(query.title), artist = normalizeArtist(query.artist), album = normalize(query.album);
        if (title.isEmpty() || artist.isEmpty() || query.durationMs <= 0 || track.durationMs() <= 0) return false;
        String trackTitle = normalize(track.title());
        boolean exactTitle = title.equals(trackTitle);
        if (!recordingAgrees(track, query)) return false;

        long delta = Math.abs(query.durationMs - track.durationMs());
        if (delta > profile.albumDeltaMs) return false;             // never: a different recording
        if (delta > profile.exactDeltaMs && !exactTitle) return false; // tolerance needs the exact title

        boolean artistsAgree = sameArtists(query.artist, query.title, track.artist(), track.title())
                // Guest-list and multilingual tolerance needs the album behind it; a bare pair stays strict.
                || !album.isEmpty() && (sharesLeadArtist(query.artist, query.title, track.artist(), track.title())
                    || namesOverlap(query.artist, query.title, track.artist(), track.title()));
        if (!artistsAgree) return false;

        String trackAlbum = normalize(track.album());
        boolean albumExact = album.isEmpty() || album.equals(trackAlbum);
        if (!albumExact && !albumAgrees(query.album, track.album(), track.edition(), profile)) return false;

        // A live or rerelease master (duration drift) is accepted only with the exact title
        // and the album behind it; never from a bare title-only match.
        if (delta > profile.exactDeltaMs) {
            return exactTitle && !album.isEmpty();
        }
        if (exactTitle) return true;
        if (album.isEmpty()) return false;
        // Guest credits and corroborated rating annotations still require the album's artist.
        return sameAlbumTrack(track, query) || primaryArtist(query.artist).equals(primaryArtist(track.artist()));
    }
    private static boolean recordingAgrees(Track track, ArtworkQuery query) {
        String wanted = ratingLabel(query.title), supplied = ratingLabel(track.title()), albumRating = ratingLabel(query.album);
        String evidence = track.edition().rating();
        boolean rated = evidence.equals("explicit") || evidence.equals("cleaned");
        if (rated && !albumRating.isEmpty() && !albumRating.equals(evidence)) return false;
        if (rated && (!wanted.isEmpty() && !wanted.equals(evidence) || !supplied.isEmpty() && !supplied.equals(evidence))) return false;
        if (coreTitle(query.title).equals(coreTitle(track.title()))) return true;
        // Only a corroborated content-rating annotation can disappear. Recording/language labels survive.
        return !query.album.isEmpty() && rated && (!wanted.isEmpty() || !supplied.isEmpty())
                && coreTitle(stripRatingLabel(Normalizer.normalize(query.title, Normalizer.Form.NFKC)))
                    .equals(coreTitle(stripRatingLabel(Normalizer.normalize(track.title(), Normalizer.Form.NFKC))));
    }

    /**
     * The motion cover belongs to the album. Inside the named album, a track whose guests are written
     * differently (an artist list such as "Taylor Swift/Ed Sheeran/Future" versus a title credit) is the
     * same track when the guest-free titles agree and each side's lead artist is credited by the other.
     * A guest's own listing or another artist never takes over the album.
     */
    static boolean sameAlbumTrack(Track track, ArtworkQuery query) {
        String core = coreTitle(query.title);
        if (core.isEmpty() || !core.equals(coreTitle(track.title()))) return false;
        String lead = primaryArtist(query.artist), storeLead = primaryArtist(track.artist());
        return !lead.isEmpty() && !storeLead.isEmpty()
                && credits(track.artist(), track.title()).contains(lead)
                && credits(query.artist, query.title).contains(storeLead);
    }

    static Track unique(List<Track> tracks, ArtworkQuery query, String songId, String albumId) throws AmFailure {
        return unique(tracks, query, songId, albumId, MatchProfile.STANDARD);
    }
    static Track unique(List<Track> tracks, ArtworkQuery query, String songId, String albumId, MatchProfile profile) throws AmFailure {
        TreeMap<String, Track> matches = new TreeMap<>();
        for (Track track : tracks) {
            if (!songId.isEmpty() && !songId.equals(track.songId())) continue;
            if (!albumId.isEmpty() && !albumId.equals(track.albumId())) continue;
            if (agrees(track, query, profile)) matches.put(track.albumId() + "/" + track.songId(), track);
        }
        if (matches.size() > 1) {
            Track equivalent = query.album.isEmpty() ? null : explicitCleanTrack(List.copyOf(matches.values()));
            if (equivalent != null) return equivalent;
            // Several candidates qualify; the most exact one wins, a tie stays ambiguous.
            Track best = bestMatch(List.copyOf(matches.values()), query, profile);
            if (best != null) return best;
            throw new AmFailure(Status.AMBIGUOUS, "multiple_catalog_matches");
        }
        // Search is bounded and may be incomplete: an empty candidate list is not a permanent miss.
        if (matches.isEmpty()) throw new AmFailure(Status.RETRY_LATER, "catalog_match_unconfirmed", 60_000);
        return matches.firstEntry().getValue();
    }
    private static Track explicitCleanTrack(List<Track> matches) {
        if (matches.size() != 2) return null;
        Track a = matches.get(0), b = matches.get(1);
        if (a.albumId().equals(b.albumId()) || !ratingBase(a.title()).equals(ratingBase(b.title()))
                || a.durationMs() <= 0 || b.durationMs() <= 0 || Math.abs(a.durationMs() - b.durationMs()) > 3000) return null;
        java.util.ArrayList<AmEdition.Candidate> editions = new java.util.ArrayList<>();
        for (Track track : matches) editions.add(new AmEdition.Candidate(track.albumId(), track.artist(), track.album(), track.edition()));
        String chosen = AmEdition.explicitCleanChoice(editions);
        if (chosen == null) return null;
        return a.albumId().equals(chosen) ? a : b;
    }
    /** Among several qualifying tracks the most exact one wins; a tie stays ambiguous. */
    private static Track bestMatch(List<Track> matches, ArtworkQuery query, MatchProfile profile) {
        long best = Long.MIN_VALUE; Track chosen = null; boolean tie = false;
        for (Track track : matches) {
            long score = matchScore(track, query, profile);
            if (chosen == null || score > best) { best = score; chosen = track; tie = false; }
            else if (score == best) tie = true;
        }
        return tie ? null : chosen;
    }
    /** Exact fields outrank tolerant ones; only candidates that already passed {@link #agrees} are scored. */
    private static long matchScore(Track track, ArtworkQuery query, MatchProfile profile) {
        // Lexicographic fields: exact album, title, credited artists, then duration. A weaker
        // album cannot win by adding several lower-priority bonuses; true ties stay ambiguous.
        long score = 0;
        String title = normalize(query.title), trackTitle = normalize(track.title());
        if (title.equals(trackTitle)) score += 1L << 44;
        else if (coreTitle(query.title).equals(coreTitle(track.title()))) score += 1L << 40;
        if (sameArtists(query.artist, query.title, track.artist(), track.title())) score += 1L << 36;
        else if (sharesLeadArtist(query.artist, query.title, track.artist(), track.title())) score += 1L << 32;
        String album = normalize(query.album), trackAlbum = normalize(track.album());
        if (!album.isEmpty()) {
            if (album.equals(trackAlbum)) score += 1L << 48;
        }
        long delta = Math.abs(query.durationMs - track.durationMs());
        if (delta <= profile.exactDeltaMs) score += 1L << 28;
        else if (delta <= profile.albumDeltaMs) score += 1L << 24;
        return score - delta;
    }
    static String diagnostics(List<Track> tracks, ArtworkQuery query) {
        return diagnostics(tracks, query, MatchProfile.STANDARD);
    }
    static String diagnostics(List<Track> tracks, ArtworkQuery query, MatchProfile profile) {
        int titles = 0, artists = 0, albums = 0, durations = 0, complete = 0;
        java.util.Map<String, Track> matched = new TreeMap<>();
        for (Track track : tracks) {
            if (recordingAgrees(track, query)) titles++;
            if (sameArtists(query.artist, query.title, track.artist(), track.title())
                    || !query.album.isEmpty() && (sharesLeadArtist(query.artist, query.title, track.artist(), track.title())
                        || namesOverlap(query.artist, query.title, track.artist(), track.title()))) artists++;
            if (query.album.isEmpty() || albumAgrees(query.album, track.album(), track.edition(), profile)) albums++;
            if (query.durationMs > 0 && track.durationMs() > 0 && Math.abs(query.durationMs - track.durationMs())
                    <= (query.album.isEmpty() ? profile.exactDeltaMs : profile.albumDeltaMs)) durations++;
            if (agrees(track, query, profile)) { complete++; matched.put(track.albumId() + "/" + track.songId(), track); }
        }
        return "candidates=" + tracks.size() + " titleMatches=" + titles + " artistMatches=" + artists
                + " albumMatches=" + albums + " durationMatches=" + durations + " completeMatches=" + complete
                + " explicitCleanEquivalent=" + (!query.album.isEmpty() && explicitCleanTrack(List.copyOf(matched.values())) != null);
    }
}
