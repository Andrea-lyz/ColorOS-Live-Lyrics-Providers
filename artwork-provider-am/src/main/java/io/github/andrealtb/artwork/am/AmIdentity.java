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
    private static final Pattern NATIVE_ALIAS = Pattern.compile("\\s*[(\\[（【][^()\\[\\]（）【】]*[)\\】】]");
    private static final Pattern LATIN_IN_ALIAS = Pattern.compile("[a-zA-Z0-9]");
    /** "(Explicit)" / "(Clean)" are content-ratings, not different recordings: "Infinite Dream (Explicit)" is "Infinite Dream". */
    private static final Pattern RATING_LABEL = Pattern.compile("\\s*[(\\[（【]\\s*(?:explicit|clean)\\s*[)\\】】]", Pattern.CASE_INSENSITIVE);

    /**
     * "MEOVV (미야오)" -> "MEOVV"; a bracketed alias that contains no Latin letter or digit is
     * dropped before NFKC, because normalization would erase the brackets and leave the foreign
     * script glued to the name. "(Explicit)", "(Taylor's Version)" and "(2024)" keep their brackets.
     */
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
        return Normalizer.normalize(stripRatingLabel(stripNativeAlias(value)), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** A title's guest credit, e.g. "Fortnight (feat. Post Malone)". */
    private static final Pattern FEATURED = Pattern.compile("[(\\[]\\s*(?:feat\\.?|ft\\.?|featuring|with)\\s+([^)\\]]+)[)\\]]",
            Pattern.CASE_INSENSITIVE);
    /** Artist-list separators players and stores use; full-width forms are folded by NFKC first. */
    private static final Pattern CREDIT_SEPARATOR = Pattern.compile("\\s*(?:[/;,\u3001&]|\\b(?:feat|ft)\\b\\.?|\\bfeaturing\\b)\\s*",
            Pattern.CASE_INSENSITIVE);

    /** Only a guest credit, bracketed or trailing; version words such as "Taylor's Version" stay in the title. */
    private static final Pattern GUEST_CLAUSE = Pattern.compile(
            "\\s*[(\\[]\\s*(?:feat\\.?|ft\\.?|featuring)\\s+[^)\\]]+[)\\]]|\\s+(?:feat\\.|ft\\.|featuring)\\s+[^()\\[\\]]+$",
            Pattern.CASE_INSENSITIVE);

    /**
     * A trailing edition clause that never changes the cover: "(Explicit)", "(Deluxe Edition)",
     * "(Mastered for iTunes)", " - Expanded", "[Japan]", "- The 6th Mini Album". Matches the
     * normalized form, where brackets become spaces, so it also folds bare "1989 Explicit".
     */
    private static final Pattern EDITION_TRAILER = Pattern.compile(
            "(?:\\s+|^)(?:explicit|clean|deluxe|expanded|edition|remaster|remastered|reissue|super\\s+deluxe|bonus\\s+tracks?|"
                    + "special\\s+edition|fan\\s+edition|digital\\s+(?:album|edition)|japan(?:ese)?|international|standard|anniversary|3am|"
                    + "mastered\\s+for\\s+itunes|digitally\\s+remastered|limited\\s+edition|collector(?:'s)?\\s+edition|number(?:ed)?\\s+edition|"
                    + "gatefold|vinyl|box\\s+set|\\d{4}\\s+remaster(?:ed)?|mini\\s+album|full\\s+album|single\\s+album|digital\\s+single|ep|"
                    + "(?:the\\s+)?\\d{1,2}(?:st|nd|rd|th)?\\s+(?:mini|full|single)\\s+album|vol(?:ume)?\\s*\\d+)(?:\\s+edition)?\\s*$",
            Pattern.CASE_INSENSITIVE);
    /** Rerecording markers inside a trailing clause; those albums have distinct covers. */
    private static final Pattern RE_RECORD_MARK = Pattern.compile(
            "taylor'?s?\\s+version|taylor\\s+s\\s+version|re[- ]?record|taylor\\s+swift", Pattern.CASE_INSENSITIVE);
    /**
     * A title suffix that does not change the recording identity: "(Explicit)", "(Radio Edit)",
     * "(2013 Remaster)", "(Live)". Only ever accepted behind the album.
     */
    private static final Pattern TITLE_TRAILER = Pattern.compile(
            "\\s+(?:explicit|clean|remix|radio\\s+edit|single\\s+version|album\\s+version|main\\s+version|video\\s+version|"
                    + "soundtrack\\s+version|original\\s+mix|extended\\s+mix|extended|edit|acoustic|instrumental|acapella|demo|reprise|live|"
                    + "remaster(?:ed)?|deluxe|bonus\\s+tracks?|with\\s+intro|with\\s+outro|\\d{4}\\s+remaster(?:ed)?)\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** Title without its guest credit, e.g. "End Game (feat. Ed Sheeran & Future)" and "End Game" agree. */
    static String coreTitle(String title) {
        return normalize(GUEST_CLAUSE.matcher(Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFKC)).replaceAll(""));
    }

    /**
     * Title without guest credit and without a harmless version suffix: "Shake It Off (Explicit)"
     * and "Shake It Off (Radio Edit)" both collapse to "Shake It Off". Rerecording markers and
     * foreign words such as "[Karaoke Version]" stay. Only ever accepted behind the album.
     */
    static String coreTitleLoose(String title) {
        String core = coreTitle(title);
        Matcher matcher = TITLE_TRAILER.matcher(core);
        if (!matcher.find()) return core;
        String base = matcher.replaceAll("").trim();
        return base.isEmpty() ? core : base;
    }

    /** Credited artists: the artist field split into names, plus the guests named in the title. */
    static Set<String> credits(String artist, String title) {
        Set<String> names = new TreeSet<>();
        addCredits(artist, names);
        Matcher featured = FEATURED.matcher(Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFKC));
        while (featured.find()) addCredits(featured.group(1), names);
        return names;
    }
    private static void addCredits(String value, Set<String> names) {
        for (String part : CREDIT_SEPARATOR.split(Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC))) {
            String name = normalize(part);
            if (!name.isEmpty()) names.add(name);
        }
    }
    /**
     * Same artists: identical fields, or identical credited sets. Device log: a player reporting both
     * artists of "Fortnight (feat. Post Malone)" never matched the store's single "Taylor Swift".
     * Sets must be equal, so a lone primary artist never matches a duet credited to two.
     */
    static boolean sameArtists(String artist, String title, String otherArtist, String otherTitle) {
        String a = normalize(artist), b = normalize(otherArtist);
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
     * when one side's name tokens are contained in the other's. Only ever accepted behind the album.
     */
    static boolean namesOverlap(String artist, String title, String otherArtist, String otherTitle) {
        Set<String> a = nameTokens(artist, title), b = nameTokens(otherArtist, otherTitle);
        if (a.isEmpty() || b.isEmpty()) return false;
        return a.containsAll(b) || b.containsAll(a);
    }
    /** Word-level tokens of every credited name: "周杰伦 Jay Chou" -> {周杰伦, jay, chou}. */
    private static Set<String> nameTokens(String artist, String title) {
        Set<String> tokens = new TreeSet<>();
        for (String credit : credits(artist, title)) {
            for (String word : credit.split(" ")) {
                if (!word.isEmpty()) tokens.add(word);
            }
        }
        return tokens;
    }
    /** Album name without a harmless trailing edition clause; rerecordings keep their marker. */
    static String editionBase(String album) {
        String value = normalize(album);
        if (value.isEmpty()) return "";
        Matcher matcher = EDITION_TRAILER.matcher(value);
        if (!matcher.find()) return value;
        if (RE_RECORD_MARK.matcher(matcher.group(0)).find()) return value;
        String base = matcher.replaceAll("").trim();
        return base.isEmpty() ? value : base;
    }
    /**
     * "Midnights" and "Midnights (3am Edition)", "1989" and "1989 (Explicit)" name the same album;
     * rerecordings do not. A display-truncated name ("Rich Man - The 6th Mini Al...") is accepted
     * when it is a long prefix of the full album name.
     */
    static boolean albumClose(String a, String b) {
        return albumClose(a, b, MatchProfile.STANDARD);
    }
    static boolean albumClose(String a, String b, MatchProfile profile) {
        if (a.isEmpty() || b.isEmpty()) return false;
        String baseA = editionBase(a), baseB = editionBase(b);
        if (baseA.equals(baseB)) return true;
        // Truncated player text: the shorter raw name is a prefix of the longer one and carries
        // most of its length. "1989" vs "1989 (Taylor's Version)" stays apart (too short a prefix).
        String rawA = normalize(a), rawB = normalize(b);
        int shorter = Math.min(rawA.length(), rawB.length());
        return shorter >= 6 && (rawA.startsWith(rawB) || rawB.startsWith(rawA))
                && shorter * 100 >= Math.max(rawA.length(), rawB.length()) * profile.prefixRatioPct;
    }
    /** First credited name; album-level search only, before the track table is verified. */
    static String primaryArtist(String artist) { return normalize(leadCredit(artist)); }
    /** The first credited name as written, for search terms shown to the user. */
    static String leadCredit(String artist) {
        for (String part : CREDIT_SEPARATOR.split(Normalizer.normalize(artist == null ? "" : artist, Normalizer.Form.NFKC))) {
            if (!normalize(part).isEmpty()) return part.trim();
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
     * alias-dotted name, an edition or version suffix that does not change cover or recording,
     * and up to 12s for live/rerelease masters when the exact title is backed by the album.
     * Nothing weaker than that is ever accepted.
     */
    static boolean agrees(Track track, ArtworkQuery query) {
        return agrees(track, query, MatchProfile.STANDARD);
    }
    static boolean agrees(Track track, ArtworkQuery query, MatchProfile profile) {
        String title = normalize(query.title), artist = normalize(query.artist), album = normalize(query.album);
        if (title.isEmpty() || artist.isEmpty() || query.durationMs <= 0 || track.durationMs() <= 0) return false;
        String trackTitle = normalize(track.title());
        boolean exactTitle = title.equals(trackTitle);
        boolean titleOk = exactTitle || coreTitle(query.title).equals(coreTitle(track.title()));
        // Symbol and version differences ("Shake It Off (Explicit)" vs "Shake It Off") are accepted
        // only behind the album; a bare title pair stays strict.
        if (!titleOk && (album.isEmpty() || !coreTitleLoose(query.title).equals(coreTitleLoose(track.title())))) return false;

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
        if (!albumExact && !albumClose(album, trackAlbum, profile)) return false;

        // A live or rerelease master (duration drift) is accepted only with the exact title
        // and the album behind it; never from a bare title-only match.
        if (delta > profile.exactDeltaMs) {
            return exactTitle && !album.isEmpty() && (album.equals(trackAlbum) || albumClose(album, trackAlbum, profile));
        }
        if (exactTitle) return true;
        if (album.isEmpty()) return false;
        // Inside the named album a version suffix ("Shake It Off (Explicit)") binds the album's
        // cover like the exact title, but only when the lead artist agrees; a guest's own listing
        // never takes over the album.
        return sameAlbumTrack(track, query) || primaryArtist(query.artist).equals(primaryArtist(track.artist()));
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
            Track best = bestMatch(List.copyOf(matches.values()), query);
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
        if (a.albumId().equals(b.albumId()) || !normalize(a.title()).equals(normalize(b.title()))
                || a.durationMs() <= 0 || b.durationMs() <= 0 || Math.abs(a.durationMs() - b.durationMs()) > 3000) return null;
        java.util.ArrayList<AmEdition.Candidate> editions = new java.util.ArrayList<>();
        for (Track track : matches) editions.add(new AmEdition.Candidate(track.albumId(), track.artist(), track.album(), track.edition()));
        String chosen = AmEdition.explicitCleanChoice(editions);
        if (chosen == null) return null;
        return a.albumId().equals(chosen) ? a : b;
    }
    /** Among several qualifying tracks the most exact one wins; a tie stays ambiguous. */
    private static Track bestMatch(List<Track> matches, ArtworkQuery query) {
        int best = 0; Track chosen = null; boolean tie = false;
        for (Track track : matches) {
            int score = matchScore(track, query);
            if (chosen == null || score > best) { best = score; chosen = track; tie = false; }
            else if (score == best) tie = true;
        }
        return tie ? null : chosen;
    }
    /** Exact fields outrank tolerant ones; only candidates that already passed {@link #agrees} are scored. */
    private static int matchScore(Track track, ArtworkQuery query) {
        int score = 0;
        String title = normalize(query.title), trackTitle = normalize(track.title());
        if (title.equals(trackTitle)) score += 4;
        else if (coreTitle(query.title).equals(coreTitle(track.title()))) score += 2;
        if (sameArtists(query.artist, query.title, track.artist(), track.title())) score += 4;
        else if (sharesLeadArtist(query.artist, query.title, track.artist(), track.title())) score += 2;
        String album = normalize(query.album), trackAlbum = normalize(track.album());
        if (!album.isEmpty()) {
            if (album.equals(trackAlbum)) score += 4;
            else if (albumClose(album, trackAlbum)) score += 2;
        }
        long delta = Math.abs(query.durationMs - track.durationMs());
        if (delta <= 3000) score += 4;
        else if (delta <= 12000) score += 2;
        return score;
    }
    static String diagnostics(List<Track> tracks, ArtworkQuery query) {
        int titles = 0, artists = 0, albums = 0, durations = 0, complete = 0;
        java.util.Map<String, Track> matched = new TreeMap<>();
        for (Track track : tracks) {
            if (normalize(query.title).equals(normalize(track.title()))
                    || !query.album.isEmpty() && coreTitle(query.title).equals(coreTitle(track.title()))) titles++;
            if (sameArtists(query.artist, query.title, track.artist(), track.title())
                    || !query.album.isEmpty() && sameAlbumTrack(track, query)) artists++;
            if (query.album.isEmpty() || normalize(query.album).equals(normalize(track.album()))) albums++;
            if (query.durationMs > 0 && track.durationMs() > 0 && Math.abs(query.durationMs - track.durationMs()) <= 3000) durations++;
            if (agrees(track, query)) { complete++; matched.put(track.albumId() + "/" + track.songId(), track); }
        }
        return "candidates=" + tracks.size() + " titleMatches=" + titles + " artistMatches=" + artists
                + " albumMatches=" + albums + " durationMatches=" + durations + " completeMatches=" + complete
                + " explicitCleanEquivalent=" + (!query.album.isEmpty() && explicitCleanTrack(List.copyOf(matched.values())) != null);
    }
}
