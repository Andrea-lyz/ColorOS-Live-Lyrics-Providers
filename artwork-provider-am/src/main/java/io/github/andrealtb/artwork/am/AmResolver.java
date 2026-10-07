package io.github.andrealtb.artwork.am;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.util.List;
import java.util.UUID;
import io.github.andrealtb.artwork.contract.ArtworkAsset;
import io.github.andrealtb.artwork.contract.ArtworkFileVerifier;
import io.github.andrealtb.artwork.contract.ArtworkQuery;
import io.github.andrealtb.artwork.contract.ArtworkResult;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

/** Public Web adapter. No JS token scraping, account credentials or third-party backend. */
final class AmResolver {
    record Resolved(File file, ArtworkAsset asset) {}
    private final Context context;
    private static final AmSharedDownload DOWNLOADS = new AmSharedDownload();
    private final AmCache cache;
    private final AmNetwork network;
    AmResolver(Context context) {
        this.context = context;
        cache = AmCache.get(context);
        network = new AmNetwork(() -> AmSettings.online(context), reason -> AmSettings.trace(context, "ARTWORK_AM_HTTP",
                reason.contains("_stalled_in_") ? reason + " importance=" + AmSettings.importance() : reason));
    }
    Resolved resolve(ArtworkQuery query, AmNetwork.Task task) throws AmFailure {
        if (!AmSettings.enabled(context)) throw new AmFailure(Status.NETWORK_BLOCKED, "provider_disabled");
        AmIdentity.AppleLink link = query.appleMusicUrl.isEmpty() ? null : AmIdentity.link(query.appleMusicUrl);
        // An exact catalog link outranks the user's album choice, which is meant for local files without one.
        AmBindings.Binding binding = link == null ? AmBindings.find(AmBindings.load(context), query) : null;
        AmDiagnostics.record(context, "ARTWORK_AM_QUERY", "route=" + (link != null ? "link" : binding != null ? "binding" : "automatic")
                + " market=" + (link != null ? link.country() : binding != null ? binding.country() : AmSettings.country(context))
                + " titlePresent=" + !query.title.isEmpty() + " artistPresent=" + !query.artist.isEmpty()
                + " albumPresent=" + !query.album.isEmpty() + " durationMs=" + query.durationMs
                + " width=" + query.displayWidthPx + " height=" + query.displayHeightPx
                + " maxWidth=" + query.maxWidth + " maxHeight=" + query.maxHeight + " maxBytes=" + query.maxFileBytes);
        if (binding != null) {
            AmRecentAlbums.note(context, query, AmRecentAlbums.Outcome.BOUND);
            AmSettings.trace(context, "ARTWORK_AM_BINDING", "user_album");
            return bound(query, binding, task);
        }
        try {
            Resolved resolved = matched(query, link, task);
            AmRecentAlbums.note(context, query, AmRecentAlbums.Outcome.MATCHED);
            return resolved;
        } catch (AmFailure failure) {
            AmRecentAlbums.Outcome outcome = AmRecentAlbums.outcome(failure);
            if (outcome != null) AmRecentAlbums.note(context, query, outcome);
            throw failure;
        }
    }

    /** The user named this album for the local album name: no track check, the album's own motion cover. */
    private Resolved bound(ArtworkQuery query, AmBindings.Binding binding, AmNetwork.Task task) throws AmFailure {
        String country = binding.country(), albumId = binding.albumId();
        String key = AmCache.boundKey(query, country, albumId);
        String networkEpoch = AmConnectivity.get(context).token();
        task.check();
        AmCache.Hit hit = cache.lookup(key, networkEpoch);
        if (hit != null && hit.file() != null) {
            try {
                Resolved ready = inspect(hit.file(), query);
                AmSettings.trace(context, "ARTWORK_AM_CACHE", "bound_file_hit");
                return ready;
            } catch (Exception error) { cache.unpin(hit.file()); hit = null; }
        }
        Resolved cached = albumVideo(query, country, albumId, key, task);
        if (cached != null) return cached;
        if (hit != null && hit.failure() != null) {
            throw new AmFailure(hit.failure().status, hit.failure().reason, hit.failure().retryAfterMs);
        }
        long pausesAtStart = AmPauseDetector.pauses();
        try {
            AmSettings.trace(context, "ARTWORK_AM_CACHE", "miss");
            if (!AmSettings.online(context)) throw new AmFailure(Status.NETWORK_BLOCKED, "network_policy");
            AmPage.Album album = AmPage.album(network.text(AmCatalog.pageUri(country, albumId), 3 * 1024 * 1024, task), albumId);
            task.check();
            AmSettings.trace(context, "ARTWORK_AM_MATCH", "user_bound_album");
            return download(query, country, album, key, task);
        } catch (AmFailure failure) {
            return failed(failure, query, country, albumId, key, networkEpoch, pausesAtStart, task);
        }
    }

    private Resolved matched(ArtworkQuery query, AmIdentity.AppleLink link, AmNetwork.Task task) throws AmFailure {
        if (query.artist.isEmpty() || query.durationMs == 0) throw new AmFailure(Status.AMBIGUOUS, "identity_fields_missing");
        String country = link == null ? AmSettings.country(context) : link.country();
        AmIdentity.MatchProfile profile = AmIdentity.MatchProfile.forLevel(AmSettings.matchLevel(context));
        String key = AmCache.key(query, country, profile);
        AmConnectivity connectivity = AmConnectivity.get(context);
        String networkEpoch = connectivity.token();
        task.check();
        AmCache.Hit hit = cache.lookup(key, networkEpoch);
        if (hit != null) {
            AmSettings.trace(context, "ARTWORK_AM_CACHE", hit.file() == null ? "cached_status" : "validated_file_hit");
            if (hit.file() != null) {
                try {
                    Resolved ready = inspect(hit.file(), query);
                    AmPage.Album owner = verifiedAlbum(query, country, link, profile);
                    if (owner != null) {
                        AmCache.Hit association = cache.lookup(AmCache.albumAssetKey(query, country, owner.id()));
                        if (association != null && association.file() != null) {
                            try { if (association.file().equals(hit.file())) cache.rememberVideo(country, owner.id(), hit.file(), ready.asset()); }
                            finally { cache.unpin(association.file()); }
                        }
                    }
                    return ready;
                }
                catch (Exception error) { cache.unpin(hit.file()); throw new AmFailure(Status.UNSUPPORTED, "cached_media_invalid"); }
            }
        }
        long pausesAtStart = AmPauseDetector.pauses();
        AmPage.Album known = verifiedAlbum(query, country, link, profile);
        String albumId = known == null ? null : known.id();
        if (known != null) {
            AmSettings.matching(context, "catalog_cache", known.tracks(), query, profile);
            Resolved cached = albumVideo(query, country, known.id(), key, task);
            if (cached != null) return cached;
        }
        if (hit != null && hit.failure() != null && !(known != null && hit.failure().reason.equals("catalog_match_unconfirmed"))) {
            throw new AmFailure(hit.failure().status, hit.failure().reason, hit.failure().retryAfterMs);
        }
        try {
            AmSettings.trace(context, "ARTWORK_AM_CACHE", "miss");
            if (!AmSettings.online(context)) throw new AmFailure(Status.NETWORK_BLOCKED, "network_policy");
            AmPage.Album album = new AmCatalog((uri, limit) -> network.text(uri, limit, task),
                    (stage, tracks) -> AmSettings.matching(context, stage, tracks, query, profile),
                    detail -> AmSettings.trace(context, "ARTWORK_AM_CATALOG", detail),
                    profile).resolve(query, link, country, known);
            task.check();
            albumId = album.id();
            cache.rememberAlbum(query, country, album, profile);
            AmSettings.trace(context, "ARTWORK_AM_MATCH", "unique_track_album_confirmed");
            return download(query, country, album, key, task);
        } catch (AmFailure failure) {
            return failed(failure, query, country, albumId, key, networkEpoch, pausesAtStart, task);
        }
    }

    /** One native 1080 video of the confirmed album, shared by all display sizes. */
    private Resolved albumVideo(ArtworkQuery query, String country, String albumId, String key, AmNetwork.Task task) throws AmFailure {
        AmCache.Hit video = cache.lookup(AmCache.albumAssetKey(query, country, albumId));
        if (video != null && video.file() != null) {
            try {
                Resolved ready = inspect(video.file(), query);
                task.check();
                cache.remember(key, video.file(), null);
                cache.rememberVideo(country, albumId, video.file(), ready.asset());
                AmSettings.trace(context, "ARTWORK_AM_CACHE", "verified_album_file_hit");
                return ready;
            } catch (AmFailure failure) { cache.unpin(video.file()); throw failure; }
            catch (Exception error) { cache.unpin(video.file()); }
        }
        migrateLargeSlot(query, country, albumId);
        AmCache.Hit compatible = cache.sharedVideo(query, country, albumId);
        if (compatible != null) {
            try {
                Resolved ready = inspect(compatible.file(), query);
                task.check();
                AmSettings.dimensions(context, "ARTWORK_AM_SHARED_1080_CACHE", ready.asset().width, ready.asset().height, ready.asset().fileBytes);
                // Each consumer owns a separate pin on the same immutable 1080 video.
                return ready;
            } catch (AmFailure failure) { cache.unpin(compatible.file()); throw failure; }
            catch (Exception error) { cache.unpin(compatible.file()); }
        }
        return null;
    }

    /** Fetches, remuxes, verifies and caches the album's square motion video. */
    private Resolved download(ArtworkQuery query, String country, AmPage.Album album, String key, AmNetwork.Task task) throws AmFailure {
        return DOWNLOADS.run(AmCache.albumAssetKey(query, country, album.id()), task,
                () -> AmSettings.trace(context, "ARTWORK_AM_DOWNLOAD_JOIN", "same_album_1080"), () -> {
                    Resolved shared = albumVideo(query, country, album.id(), key, task);
                    if (shared != null) {
                        AmSettings.trace(context, "ARTWORK_AM_DOWNLOAD_REUSED", "completed_1080");
                        return shared;
                    }
                    return downloadExclusive(query, country, album, key, task);
                });
    }

    private Resolved downloadExclusive(ArtworkQuery query, String country, AmPage.Album album, String key, AmNetwork.Task task) throws AmFailure {
        if (album.master() == null) throw new AmFailure(Status.NO_MOTION, "confirmed_album_no_motion");
        String master = network.text(album.master(), 256 * 1024, task);
        List<AmHls.Variant> variants = new java.util.ArrayList<>(AmHls.variants(album.master(), master, query));
        AmFailure last = null;
        int attempts = 0;
        while (!variants.isEmpty()) {
            if (++attempts > 4) break;
            AmHls.Variant variant = variants.remove(0);
            task.check();
            AmDiagnostics.record(context, "ARTWORK_AM_VARIANT_ATTEMPT", "attempt=" + attempts + " remaining=" + variants.size()
                    + " width=" + variant.width() + " height=" + variant.height() + " bitrate=" + variant.bitrate()
                    + " fps=" + variant.fps() + " variantRef=" + AmCache.hash(variant.uri().getPath()).substring(0, 16));
            AmSettings.dimensions(context, "ARTWORK_AM_VARIANT", variant.width(), variant.height(), 0);
            File temp = null, normalized = null, pinned = null;
            AmHls.FilePlan sourcePlan = null;
            boolean downloaded = false;
            try {
                String child = network.text(variant.uri(), 256 * 1024, task);
                AmHls.FilePlan plan = AmHls.filePlan(variant.uri(), child, query.maxFileBytes);
                sourcePlan = plan;
                AmDiagnostics.record(context, "ARTWORK_AM_HLS_PLAN", "bytes=" + plan.bytes() + " durationSeconds=" + plan.durationSeconds()
                        + " mediaRef=" + AmCache.hash(plan.uri().getPath()).substring(0, 16));
                String resourceKey = AmCache.hash("avc-remux-v1\n" + country + "\n" + album.id() + "\n" + variant.uri()
                        + "\n" + plan.bytes() + "\n" + plan.durationSeconds());
                AmCache.Hit resource = cache.lookup(resourceKey);
                if (resource != null && resource.file() != null) {
                    try {
                        Resolved reused = inspect(resource.file(), query);
                        if (reused.asset().width != variant.width() || reused.asset().height != variant.height()
                                || !"video/avc".equals(reused.asset().codec)) throw new IllegalArgumentException();
                        cache.remember(key, resource.file(), null);
                        cache.remember(AmCache.albumAssetKey(query, country, album.id()), resource.file(), null);
                        cache.rememberVideo(country, album.id(), resource.file(), reused.asset());
                        AmSettings.trace(context, "ARTWORK_AM_CACHE", "album_variant_hit");
                        return reused;
                    } catch (Exception error) { cache.unpin(resource.file()); }
                }
                temp = cache.temporary();
                network.file(plan, temp, query.maxFileBytes, task);
                downloaded = true;
                AmSettings.dimensions(context, "ARTWORK_AM_DOWNLOAD", variant.width(), variant.height(), temp.length());
                normalized = cache.temporary();
                AmMp4Normalizer.remux(temp, normalized, variant, plan, query.maxFileBytes, task,
                        AmSettings.prefs(context).getBoolean("debug", false)
                                ? detail -> AmSettings.trace(context, "ARTWORK_AM_INITIAL_SAMPLE", detail) : null);
                AmSettings.dimensions(context, "ARTWORK_AM_REMUX", variant.width(), variant.height(), normalized.length());
                // Inspect the final zero-origin MP4 before immutable cache commit.
                Resolved checked = inspect(normalized, query);
                if (checked.asset().width != variant.width() || checked.asset().height != variant.height()
                        || !"video/avc".equals(checked.asset().codec)
                        || Math.abs(checked.asset().durationMs - Math.round(plan.durationSeconds() * 1000)) > 1000) {
                    throw new AmFailure(Status.UNSUPPORTED, "media_manifest_mismatch");
                }
                task.check();
                pinned = cache.commit(normalized);
                ArtworkAsset asset = checked.asset();
                Resolved result = new Resolved(pinned, new ArtworkAsset(asset.assetId, pinned.getName().substring(0, 64), asset.codec,
                        asset.width, asset.height, asset.durationMs, asset.fileBytes, asset.validForMs));
                cache.remember(key, pinned, null);
                cache.remember(resourceKey, pinned, null);
                cache.remember(AmCache.albumAssetKey(query, country, album.id()), pinned, null);
                cache.rememberVideo(country, album.id(), pinned, result.asset());
                AmSettings.dimensions(context, "ARTWORK_AM_READY", asset.width, asset.height, asset.fileBytes);
                return result;
            } catch (AmFailure failure) {
                if (downloaded && failure.status == Status.UNSUPPORTED) inspectFailure(temp, variant, sourcePlan, failure);
                last = AmFailure.preferVariantFailure(last, failure);
                AmDiagnostics.record(context, "ARTWORK_AM_VARIANT_FAILED", "attempt=" + attempts + " status=" + failure.status
                        + " reason=" + failure.reason + " detail=" + (failure.detail == null ? "none" : failure.detail));
                // A slow or unsupported rendition moves to the next variant; API-level answers do not.
                boolean tryNextVariant = failure.status == Status.UNSUPPORTED
                        || failure.status == Status.RETRY_LATER && AmConnectivity.transport(failure.reason);
                if (!tryNextVariant) throw failure;
                if (failure.status == Status.RETRY_LATER) {
                    AmSettings.trace(context, "ARTWORK_AM_VARIANT_FALLBACK", failure.reason);
                }
            } catch (Exception error) {
                if (downloaded) inspectFailure(temp, variant, sourcePlan, new AmFailure(Status.UNSUPPORTED,
                        "media_validation_failed", 0, AmExceptionDiagnostic.describe("download_or_validation", error)));
                AmDiagnostics.record(context, "ARTWORK_AM_VARIANT_FAILED", "attempt=" + attempts
                        + " reason=media_validation_failed " + AmExceptionDiagnostic.describe("download_or_validation", error));
                last = AmFailure.preferVariantFailure(last, new AmFailure(Status.UNSUPPORTED, "media_validation_failed"));
            }
            finally { if (temp != null) temp.delete(); if (normalized != null) normalized.delete(); }
            if (pinned != null) cache.unpin(pinned);
        }
        throw last == null ? new AmFailure(Status.UNSUPPORTED, "no_usable_variant") : last;
    }

    private void inspectFailure(File source, AmHls.Variant variant, AmHls.FilePlan plan, AmFailure failure) {
        try {
            if (source != null && plan != null && AmSettings.prefs(context).getBoolean("debug", false)) {
                AmInspectionFeature.capture(context, source, variant, plan, failure);
            }
        } catch (RuntimeException ignored) { /* Inspection must not change the resolver's failure or retry policy. */ }
    }

    /** Caches the source failure; never substitutes a small video for the fixed 1080 resource. */
    private Resolved failed(AmFailure failure, ArtworkQuery query, String country, String albumId, String key,
            String networkEpoch, long pausesAtStart, AmNetwork.Task task) throws AmFailure {
        if (failure.detail != null) AmSettings.trace(context, "ARTWORK_AM_FAILURE_DETAIL", failure.reason + " step=" + failure.detail);
        task.check();
        boolean transport = AmConnectivity.transport(failure.reason);
        // Timeouts measured across a stopped process say nothing about the network.
        boolean paused = AmPauseDetector.pauses() != pausesAtStart;
        if (transport && paused) AmSettings.trace(context, "ARTWORK_AM_FAILURE_NOT_CACHED", failure.reason + "_process_paused");
        else if (!transport || networkEpoch.equals(AmConnectivity.get(context).token())) {
            cache.remember(key, null, failure.result(), networkEpoch);
        }
        throw failure;
    }

    private Resolved inspect(File file, ArtworkQuery query) throws Exception {
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
            String version = file.getName().matches("[a-f0-9]{64}\\.mp4") ? file.getName().substring(0, 64) : "web-v1";
            ArtworkAsset asset = ArtworkFileVerifier.inspect(fd, UUID.randomUUID().toString(), version, query.maxFileBytes);
            if (!asset.fits(query) || asset.width != AmHls.ARTWORK_SIZE || asset.height != AmHls.ARTWORK_SIZE) {
                throw new AmFailure(Status.UNSUPPORTED, "resource_limits");
            }
            return new Resolved(file, asset);
        }
    }
    private AmPage.Album verifiedAlbum(ArtworkQuery query, String country, AmIdentity.AppleLink link, AmIdentity.MatchProfile profile) {
        AmPage.Album known = cache.album(query, country);
        if (known == null) return null;
        try {
            if (link != null && !link.albumId().isEmpty() && !link.albumId().equals(known.id())) return null;
            AmIdentity.unique(known.tracks(), query, link == null ? "" : link.songId(), known.id(), profile);
            return known;
        } catch (AmFailure failure) { return null; }
    }
    private void migrateLargeSlot(ArtworkQuery query, String country, String albumId) {
        ArtworkQuery large = new ArtworkQuery(query.title, query.artist, query.album, query.durationMs, query.appleMusicUrl,
                1080, 1080, 1080, 1080, io.github.andrealtb.artwork.contract.ArtworkContract.MAX_FILE_BYTES);
        AmCache.Hit old = cache.lookup(AmCache.hash("album-asset-v2\n" + country + "\n" + albumId
                + "\n1080x1080\n1080x1080\n" + large.maxFileBytes));
        if (old == null || old.file() == null) return;
        try { Resolved ready = inspect(old.file(), large); cache.rememberVideo(country, albumId, old.file(), ready.asset()); }
        catch (Exception ignored) { /* only a validated legacy asset enters the new inventory */ }
        finally { cache.unpin(old.file()); }
    }
}
