package io.github.andrealtb.artwork.am;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.util.UUID;
import org.json.JSONObject;
import io.github.andrealtb.artwork.contract.ArtworkAsset;
import io.github.andrealtb.artwork.contract.ArtworkFileVerifier;
import io.github.andrealtb.artwork.contract.ArtworkQuery;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

/** Query identity is checked before reuse; album-name + catalog-ID keys keep editions separate. */
final class NcmResolver {
    /** Metadata only: no open descriptor, cache pin or downloaded file may outlive an unused lookup. */
    record Prepared(String key, String failureKey, NcmSearch.Song song, AmBindings.Binding binding, long epoch,
            String networkEpoch, java.net.URI video) {}
    private static final AmSharedDownload DOWNLOADS = new AmSharedDownload();
    private final Context context;
    private final AmCache cache;
    NcmResolver(Context context) { this.context = context; cache = AmCache.get(context); }

    /** Existing identity + complete verified MP4 only; a miss must leave AM its normal first attempt. */
    AmResolver.Resolved cachedAutomatic(ArtworkQuery query, AmNetwork.Task task) throws AmFailure {
        task.check();
        if (!NcmSession.enabled(context)) return null;
        NcmSession.Session session = NcmSession.read(context);
        if (session == null) return null;
        String level = AmSettings.matchLevel(context);
        NcmSearch.Song song = known(NcmSearch.queryKey(query, level), query, AmIdentity.MatchProfile.forLevel(level));
        if (song == null) return null;
        return cached(NcmSearch.albumKey(song, query), query, session.epoch(), task,
                AmConnectivity.get(context).token(), true);
    }

    AmResolver.Resolved resolve(ArtworkQuery query, AmBindings.Binding binding, AmNetwork.Task task) throws AmFailure {
        return complete(query, prepare(query, binding, task), task);
    }

    Prepared prepare(ArtworkQuery query, AmBindings.Binding binding, AmNetwork.Task task) throws AmFailure {
        task.check();
        if (binding == null && (query.title.isEmpty() || query.artist.isEmpty())) {
            throw new AmFailure(Status.AMBIGUOUS, "identity_fields_missing");
        }
        NcmSession.Session session = NcmSession.read(context);
        if (!NcmSession.enabled(context) || session == null) throw new AmFailure(Status.NETWORK_BLOCKED, "netease_login_required");
        long epoch = session.epoch();
        NcmApi api = new NcmApi(context, () -> permitted(epoch) && AmSettings.online(context));
        AmIdentity.MatchProfile profile = AmIdentity.MatchProfile.forLevel(AmSettings.matchLevel(context));
        String matchKey = NcmSearch.queryKey(query, AmSettings.matchLevel(context));
        NcmSearch.Song song;
        if (binding != null) {
            song = new NcmSearch.Song(binding.songId(), "bound", binding.artist(), binding.title(), binding.albumId(), 1);
        } else {
            song = known(matchKey, query, profile);
            if (song == null) {
                java.util.List<NcmSearch.Song> candidates = api.search(NcmSearch.searchTerm(query), task);
                AmSettings.trace(context, "ARTWORK_NETEASE_SEARCH", "by_title candidates=" + candidates.size());
                song = NcmSearch.choose(candidates, query, profile);
                AmSettings.trace(context, "ARTWORK_NETEASE_MATCH", "song_id_confirmed");
                remember(matchKey, song);
            }
        }
        String key = NcmSearch.albumKey(song, query);
        String failureKey = NcmSearch.failureKey(song, query);
        String networkEpoch = AmConnectivity.get(context).token();
        check(epoch, task);
        AmCache.Hit hit = cache.lookup(key, networkEpoch, task.recoveringTransport());
        if (hit != null && hit.file() != null) {
            // Only a hint until selection: the consumer reopens and verifies its own lease.
            cache.unpin(hit.file());
            return new Prepared(key, failureKey, song, binding, epoch, networkEpoch, null);
        }
        checkFailure(failureKey, networkEpoch, task);
        long pausesAtStart = AmPauseDetector.pauses();
        try {
            java.net.URI video = lookupCover(song, binding, epoch, task);
            check(epoch, task);
            return new Prepared(key, failureKey, song, binding, epoch, networkEpoch, video);
        } catch (AmFailure failure) {
            rememberFailure(failureKey, epoch, networkEpoch, pausesAtStart, failure);
            throw failure;
        }
    }

    AmResolver.Resolved complete(ArtworkQuery query, Prepared prepared, AmNetwork.Task task) throws AmFailure {
        String key = prepared.key(), networkEpoch = prepared.networkEpoch();
        long epoch = prepared.epoch();
        check(epoch, task);
        AmResolver.Resolved ready = cached(key, query, epoch, task, networkEpoch);
        if (ready != null) return ready;
        checkFailure(prepared.failureKey(), networkEpoch, task);
        return DOWNLOADS.run(key + ":" + epoch, prepared.failureKey(), task, () -> AmSettings.trace(context, "ARTWORK_NETEASE_DOWNLOAD", "same_album_join"), () -> {
            check(epoch, task);
            AmResolver.Resolved shared = cached(key, query, epoch, task, networkEpoch);
            if (shared != null) return shared;
            File temp = null, pinned = null;
            long pausesAtStart = AmPauseDetector.pauses();
            try {
                java.net.URI video = prepared.video() != null ? prepared.video()
                        : lookupCover(prepared.song(), prepared.binding(), epoch, task);
                temp = cache.temporary();
                new AmNetwork(() -> permitted(epoch) && AmSettings.online(context),
                        detail -> AmSettings.trace(context, "ARTWORK_NETEASE_DOWNLOAD", detail), NcmProtocol::validateVideo)
                        .file(video, temp, query.maxFileBytes, task);
                check(epoch, task);
                AmResolver.Resolved verified = inspect(temp, query);
                pinned = cache.commit(temp);
                check(epoch, task);
                ArtworkAsset asset = verified.asset();
                cache.remember(key, pinned, null);
                cache.rememberSource(pinned, AmVideoSources.Source.NETEASE);
                AmSettings.dimensions(context, "ARTWORK_NETEASE_READY", asset.width, asset.height, asset.fileBytes);
                AmResolver.Resolved result = new AmResolver.Resolved(pinned, new ArtworkAsset(asset.assetId,
                        pinned.getName().substring(0, 64), asset.codec, asset.width, asset.height, asset.durationMs, asset.fileBytes, asset.validForMs),
                        "netease", epoch);
                pinned = null; // pin ownership moves to the service lease
                return result;
            } catch (AmFailure failure) {
                rememberFailure(prepared.failureKey(), epoch, networkEpoch, pausesAtStart, failure);
                throw failure;
            } catch (Exception failure) { throw new AmFailure(Status.UNSUPPORTED, "netease_media_invalid"); }
            finally { if (temp != null) temp.delete(); if (pinned != null) cache.unpin(pinned); }
        });
    }
    private java.net.URI lookupCover(NcmSearch.Song chosen, AmBindings.Binding binding, long epoch,
            AmNetwork.Task task) throws AmFailure {
        check(epoch, task);
        NcmSession.Session active = NcmSession.read(context);
        if (active == null || active.epoch() != epoch) throw new AmFailure(Status.NETWORK_BLOCKED, "netease_login_required");
        NcmApi api = new NcmApi(context, () -> permitted(epoch) && AmSettings.online(context));
        if (System.currentTimeMillis() - active.refreshedAt() >= 86_400_000) {
            active = api.refresh(active, task);
            try {
                if (!NcmSession.save(context, active, epoch)) throw new AmFailure(Status.NETWORK_BLOCKED, "netease_login_required");
            } catch (AmFailure failure) { throw failure; }
            catch (Exception unavailable) { throw new AmFailure(Status.ERROR, "netease_session_storage_failed"); }
        }
        if (binding != null) {
            NcmSearch.Song actual = api.song(chosen.id(), task);
            if (!actual.albumId().equals(chosen.albumId()) || !AmIdentity.normalize(actual.album()).equals(AmIdentity.normalize(chosen.album())))
                throw new AmFailure(Status.AMBIGUOUS, "netease_binding_changed");
        }
        return api.cover(active, chosen.id(), task);
    }
    private void rememberFailure(String key, long epoch, String networkEpoch, long pausesAtStart, AmFailure failure) {
        boolean transport = AmConnectivity.transport(failure.reason);
        boolean stable = !transport || AmPauseDetector.pauses() == pausesAtStart
                && networkEpoch.equals(AmConnectivity.get(context).token());
        if (permitted(epoch) && stable && !failure.reason.equals("cancelled")) cache.remember(key, null, failure.result(), networkEpoch);
        else if (transport) AmSettings.trace(context, "ARTWORK_NETEASE_FAILURE_NOT_CACHED", "process_or_network_changed");
    }
    private void checkFailure(String key, String networkEpoch, AmNetwork.Task task) throws AmFailure {
        AmCache.Hit hit = cache.lookup(key, networkEpoch, task.recoveringTransport());
        if (hit == null) return;
        if (hit.file() != null) cache.unpin(hit.file());
        if (hit.failure() != null)
            throw new AmFailure(hit.failure().status, hit.failure().reason, hit.failure().retryAfterMs);
    }
    private boolean permitted(long epoch) { return AmSettings.enabled(context) && NcmSession.enabled(context) && NcmSession.epoch() == epoch; }
    private void check(long epoch, AmNetwork.Task task) throws AmFailure {
        task.check();
        if (!permitted(epoch)) throw new AmFailure(Status.NETWORK_BLOCKED, "netease_source_disabled");
    }
    private AmResolver.Resolved cached(String key, ArtworkQuery query, long epoch, AmNetwork.Task task, String networkEpoch) throws AmFailure {
        return cached(key, query, epoch, task, networkEpoch, false);
    }
    private AmResolver.Resolved cached(String key, ArtworkQuery query, long epoch, AmNetwork.Task task,
            String networkEpoch, boolean positiveOnly) throws AmFailure {
        AmCache.Hit hit = cache.lookup(key, networkEpoch, task.recoveringTransport());
        if (hit == null) return null;
        // Legacy album-wide failures were inferred from a single song. Only verified files migrate.
        if (hit.failure() != null) return null;
        try {
            AmResolver.Resolved inspected = inspect(hit.file(), query);
            check(epoch, task);
            cache.rememberSource(hit.file(), AmVideoSources.Source.NETEASE);
            return new AmResolver.Resolved(hit.file(), inspected.asset(), "netease", epoch);
        } catch (AmFailure failure) {
            cache.unpin(hit.file());
            if (positiveOnly && (failure.status == Status.UNSUPPORTED || failure.reason.equals("netease_source_disabled"))) return null;
            throw failure;
        }
        catch (Exception invalid) { cache.unpin(hit.file()); return null; }
    }
    private static AmResolver.Resolved inspect(File file, ArtworkQuery query) throws Exception {
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
            ArtworkAsset asset = ArtworkFileVerifier.inspect(fd, UUID.randomUUID().toString(), "netease-v1", query.maxFileBytes);
            if (!asset.fits(query)) throw new AmFailure(Status.UNSUPPORTED, "resource_limits");
            return new AmResolver.Resolved(file, asset);
        }
    }
    private NcmSearch.Song known(String key, ArtworkQuery query, AmIdentity.MatchProfile profile) {
        try {
            String saved = AmSettings.prefs(context).getString("ncm_match_" + key, "");
            if (saved == null || saved.length() > 4096) return null;
            JSONObject value = new JSONObject(saved);
            long remaining = value.getLong("expires") - System.currentTimeMillis();
            if (remaining <= 0 || remaining > 86_400_000) return null;
            NcmSearch.Song song = new NcmSearch.Song(value.getString("id"), value.getString("name"), value.getString("artist"),
                    value.getString("album"), value.getString("albumId"), value.getLong("duration"));
            return NcmSearch.valid(song) && NcmSearch.accepts(song, query, profile) ? song : null;
        } catch (Exception missing) { return null; }
    }
    private void remember(String key, NcmSearch.Song song) {
        try {
            android.content.SharedPreferences prefs = AmSettings.prefs(context);
            android.content.SharedPreferences.Editor edit = prefs.edit();
            java.util.List<String> matches = prefs.getAll().keySet().stream().filter(name -> name.startsWith("ncm_match_")).sorted().collect(java.util.stream.Collectors.toList());
            // Bounded metadata-only index. It contains neither a signed URL nor a credential.
            if (matches.size() >= 128) for (int i = 0; i <= matches.size() - 128; i++) edit.remove(matches.get(i));
            edit.putString("ncm_match_" + key, song.json().put("expires", System.currentTimeMillis() + 86_400_000).toString()).apply();
        } catch (Exception ignored) { /* advisory identity cache */ }
    }
    /** Legacy videos predate source labels; saved song identities/bindings can recover their album indexes. */
    static java.util.Set<String> cachedAlbumKeys(Context context) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (var item : AmSettings.prefs(context).getAll().entrySet()) {
            if (!item.getKey().startsWith("ncm_match_") || !(item.getValue() instanceof String saved) || saved.length() > 4096) continue;
            try {
                JSONObject song = new JSONObject(saved);
                addAlbumKeys(keys, song.optString("album"), song.optString("albumId"));
            } catch (Exception ignored) { /* no provenance from an unreadable identity */ }
        }
        for (AmBindings.Binding binding : AmBindings.load(context))
            if (binding.netease()) addAlbumKeys(keys, binding.title(), binding.albumId());
        return keys;
    }
    private static void addAlbumKeys(java.util.Set<String> keys, String album, String albumId) {
        if (album.isBlank() || album.length() > 512 || !albumId.matches("[0-9]{1,20}")) return;
        for (int limit : new int[]{1080, io.github.andrealtb.artwork.contract.ArtworkContract.MAX_RESOLUTION})
            keys.add(NcmSearch.albumKey(album, albumId, limit, limit, io.github.andrealtb.artwork.contract.ArtworkContract.MAX_FILE_BYTES));
    }
}
