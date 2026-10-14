package io.github.andrealtb.artwork.am;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/** User-authorized artwork equivalence, never a claim that the audio recordings are identical. */
final class AmEdition {
    record Info(String rating, String releaseDay, int trackCount) {
        static final Info UNKNOWN = new Info("", "", 0);
        Info {
            rating = "clean".equals(rating) ? "cleaned" : rating == null ? "" : rating;
            releaseDay = releaseDay != null && releaseDay.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}.*") ? releaseDay.substring(0, 10) : "";
        }
    }
    record Candidate(String id, String artist, String album, Info info) {}

    /**
     * The one place that answers which of several catalog albums a query means. The rules are tried
     * in order and the first that has the evidence decides; a rule without evidence leaves the tie.
     *
     * <p>Its two callers each kept a private rule set, so a new album category arrived as a third
     * branch in a third file. They now meet here, where a new category is a rule with evidence.
     *
     * <p>Rule 1 is the store's own equivalence: one artist, one album name once its rating label is
     * gone, one release day, one track count, the pair marked explicit and clean. It runs at every
     * strictness, because it merges a release the store itself describes as one album rather than
     * choosing between releases.
     *
     * <p>Rule 2 is a choice, so {@code settlesEditions} gates it: same-titled albums by one artist that
     * each verified the requested track, such as MAYHEM's 14- and 17-song releases. Explicit first, as
     * in rule 1; then the most songs, since a superset edition answers every track of the album alike
     * and its cover does not change from song to song; then the earliest catalog ID. Strict and
     * standard pass false and leave the choice to a binding.
     *
     * <p>Both rules need every candidate to name one artist and one album, and one catalog ID
     * described two ways stops the answer: a choice must not depend on a failure or a contradiction.
     */
    static String choose(List<Candidate> candidates, boolean settlesEditions) {
        TreeMap<String, Candidate> distinct = new TreeMap<>();
        for (Candidate candidate : candidates) {
            Candidate previous = distinct.putIfAbsent(candidate.id(), candidate);
            if (previous != null && !previous.equals(candidate)) return null;
        }
        if (distinct.size() < 2) return null;
        Candidate first = distinct.firstEntry().getValue();
        String artist = AmIdentity.normalizeArtist(first.artist()), album = AmIdentity.ratingBase(first.album());
        if (artist.isEmpty() || album.isEmpty()) return null;
        for (Candidate candidate : distinct.values()) {
            if (candidate.info() == null || !AmIdentity.normalizeArtist(candidate.artist()).equals(artist)
                    || !AmIdentity.ratingBase(candidate.album()).equals(album)) return null;
        }
        if (distinct.size() == 2) {
            Candidate a = distinct.firstEntry().getValue(), b = distinct.lastEntry().getValue();
            Info x = a.info(), y = b.info();
            if (!x.releaseDay().isEmpty() && x.releaseDay().equals(y.releaseDay())
                    && x.trackCount() > 0 && x.trackCount() == y.trackCount()) {
                if ("explicit".equals(x.rating()) && "cleaned".equals(y.rating())) return a.id();
                if ("cleaned".equals(x.rating()) && "explicit".equals(y.rating())) return b.id();
            }
        }
        if (!settlesEditions) return null;
        return distinct.values().stream().min(Comparator
                .comparing((Candidate candidate) -> !"explicit".equals(candidate.info().rating()))
                .thenComparing(Comparator.comparingInt((Candidate candidate) -> candidate.info().trackCount()).reversed())
                .thenComparing(candidate -> new BigInteger(candidate.id()))).orElseThrow().id();
    }
}
