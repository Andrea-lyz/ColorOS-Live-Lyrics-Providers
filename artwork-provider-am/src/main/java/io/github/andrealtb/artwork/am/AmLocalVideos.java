package io.github.andrealtb.artwork.am;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.provider.OpenableColumns;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;
import io.github.andrealtb.artwork.contract.ArtworkAsset;
import io.github.andrealtb.artwork.contract.ArtworkFileVerifier;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

/**
 * Covers made from the user's own videos: private files named by their content hash, kept while a
 * binding names them. They live outside the evictable cache, so clearing the cache never drops one.
 */
final class AmLocalVideos {
    /** A finished import; {@code original} when the picture was copied rather than encoded again. */
    record Imported(String id, String name, int width, int height, long durationMs, long bytes,
            boolean original, boolean cropped) {}
    private record Source(AmLocalPolicy.Probe probe, int track, MediaFormat format) {}
    private record Encoder(String name, MediaCodecInfo.VideoCapabilities video, boolean high) {}
    private static final String FOLDER = "local-artwork-v1";
    private static final long MIN_FREE_BYTES = 64L * 1024 * 1024;

    private AmLocalVideos() {}

    static File file(Context context, String id) {
        if (!AmLocalPolicy.videoId(id)) throw new IllegalArgumentException("invalid_local_id");
        return new File(folder(context), id + ".mp4");
    }

    static void delete(Context context, String id) {
        if (AmLocalPolicy.videoId(id)) file(context, id).delete();
    }

    /** Removes videos no binding names any more and partial files an interrupted import left behind. */
    static void sweep(Context context, Collection<AmBindings.Binding> bindings) {
        Set<String> kept = new HashSet<>();
        for (AmBindings.Binding binding : bindings) if (binding.local()) kept.add(binding.albumId() + ".mp4");
        File[] files = folder(context).listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (name.matches("[a-f0-9]{64}\\.mp4") ? !kept.contains(name) : name.endsWith(".part")) file.delete();
        }
    }

    /**
     * Checks the picked video, makes a cover of it and stores it. Runs on a worker thread; one import
     * at a time, since the start of each removes what earlier ones left unbound.
     */
    static Imported importVideo(Context context, Uri uri, IntConsumer percent, BooleanSupplier cancelled) throws AmFailure {
        ContentResolver resolver = context.getContentResolver();
        File folder = folder(context);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new AmFailure(Status.ERROR, "local_storage");
        sweep(context, AmBindings.load(context));
        if (!room(context, folder)) throw new AmFailure(Status.ERROR, "local_storage");
        String name = AmLocalPolicy.displayName(displayName(resolver, uri));
        Source source;
        try (ParcelFileDescriptor descriptor = open(resolver, uri)) {
            source = probe(descriptor);
        } catch (AmFailure failure) {
            throw failure;
        } catch (Exception error) {
            throw new AmFailure(Status.UNSUPPORTED, "local_unreadable", 0, AmExceptionDiagnostic.describe("local_probe", error));
        }
        AmLocalPolicy.Probe probe = source.probe();
        String rejected = AmLocalPolicy.rejection(probe);
        if (rejected != null) throw new AmFailure(Status.UNSUPPORTED, rejected);
        File temp = null;
        try {
            temp = File.createTempFile("import-", ".part", folder);
            // The copy must pass the same check as an encode; one that does not is encoded instead.
            ArtworkAsset asset = AmLocalPolicy.original(probe) && copied(resolver, uri, source, temp, cancelled) ? copyAsset(temp) : null;
            boolean original = asset != null;
            if (!original) {
                encode(resolver, uri, source, temp, percent, cancelled);
                if (cancelled.getAsBoolean()) throw new AmFailure(Status.ERROR, "cancelled");
                asset = verify(temp);
            }
            String id = commit(temp, folder);
            return new Imported(id, name, asset.width, asset.height, asset.durationMs, asset.fileBytes, original,
                    !original && AmLocalPolicy.cropped(probe));
        } catch (AmFailure failure) {
            throw failure;
        } catch (Exception error) {
            throw new AmFailure(Status.ERROR, "local_storage", 0, AmExceptionDiagnostic.describe("local_storage", error));
        } finally {
            if (temp != null) temp.delete();
        }
    }

    private static ArtworkAsset copyAsset(File copy) {
        try { return verify(copy); }
        catch (AmFailure rejected) { return null; }
    }

    private static File folder(Context context) { return new File(context.getFilesDir(), FOLDER); }

    /** Space the system can give, counting caches it would clear; unknown space is left to the write to report. */
    private static boolean room(Context context, File folder) {
        try {
            StorageManager storage = context.getSystemService(StorageManager.class);
            return storage.getAllocatableBytes(storage.getUuidForPath(folder)) >= MIN_FREE_BYTES;
        } catch (Exception unknown) {
            return true;
        }
    }

    /** The exact copy, when the file allows one; false sends it through the encoder instead. */
    private static boolean copied(ContentResolver resolver, Uri uri, Source source, File temp, BooleanSupplier cancelled)
            throws AmFailure {
        try (ParcelFileDescriptor descriptor = open(resolver, uri)) {
            MediaExtractor extractor = extractor(descriptor, source.track());
            try {
                AmTranscoder.remux(extractor, source.format(), temp, AmLocalPolicy.MAX_BYTES, cancelled);
            } finally {
                extractor.release();
            }
            return true;
        } catch (AmFailure failure) {
            if (failure.reason.equals("cancelled")) throw failure;
            return false;
        } catch (Exception error) {
            return false;
        }
    }

    private static void encode(ContentResolver resolver, Uri uri, Source source, File temp, IntConsumer percent,
            BooleanSupplier cancelled) throws AmFailure {
        AmLocalPolicy.Probe probe = source.probe();
        MediaFormat format = source.format();
        String decoder = decoder(format);
        if (decoder == null && MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION.equals(probe.mime())) {
            // Phone Dolby Vision recordings carry an HEVC base layer every HEVC decoder plays.
            format = new MediaFormat(format);
            format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_HEVC);
            format.removeKey(MediaFormat.KEY_PROFILE);
            format.removeKey(MediaFormat.KEY_LEVEL);
            decoder = decoder(format);
        }
        if (decoder == null) throw new AmFailure(Status.UNSUPPORTED, "local_decoder_missing", 0, "mime=" + probe.mime());
        Encoder encoder = encoder();
        if (encoder == null) throw new AmFailure(Status.UNSUPPORTED, "local_encoder_missing");
        int alignment = Math.max(encoder.video().getWidthAlignment(), encoder.video().getHeightAlignment());
        int side = AmLocalPolicy.side(Math.min(probe.width(), probe.height()), alignment,
                size -> encoder.video().isSizeSupported(size, size));
        if (side == 0) throw new AmFailure(Status.UNSUPPORTED, "local_encoder_missing", 0, "size_unsupported");
        int fps = AmLocalPolicy.frameRate(probe.fps());
        int bitrate = AmLocalPolicy.bitrate(side, fps, probe.durationMs());
        long totalUs = (probe.durationMs() > 0 ? Math.min(probe.durationMs(), AmLocalPolicy.MAX_DURATION_MS)
                : AmLocalPolicy.MAX_DURATION_MS) * 1000;
        int[] shown = { -1 };
        LongConsumer progress = doneUs -> {
            int value = (int) Math.max(0, Math.min(99, doneUs * 100 / Math.max(1, totalUs)));
            if (value != shown[0]) { shown[0] = value; percent.accept(value); }
        };
        for (int attempt = 0; ; attempt++) {
            AmTranscoder.Plan plan = new AmTranscoder.Plan(decoder, encoder.name(), encoder.high(), side, bitrate, fps,
                    probe.width(), probe.height(), probe.rotation(), probe.hdr());
            try (ParcelFileDescriptor descriptor = open(resolver, uri)) {
                MediaExtractor extractor = extractor(descriptor, source.track());
                try {
                    AmTranscoder.transcode(extractor, format, plan, temp, AmLocalPolicy.MAX_BYTES, progress, cancelled);
                    return;
                } finally {
                    extractor.release();
                }
            } catch (AmFailure failure) {
                // One retry below the size the encoder overshot; a second overshoot is reported.
                if (!failure.reason.equals("local_too_large") || attempt > 0) throw failure;
                bitrate = Math.max(300_000, bitrate / 5 * 3);
            } catch (Exception error) {
                throw new AmFailure(Status.UNSUPPORTED, "local_unreadable", 0, AmExceptionDiagnostic.describe("local_encode_open", error));
            }
        }
    }

    private static Source probe(ParcelFileDescriptor descriptor) throws Exception {
        String rotation = null, duration = null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(descriptor.getFileDescriptor());
            rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        } catch (RuntimeException unreadable) {
            // Container metadata is a fallback only; the extractor below decides what is readable.
        } finally {
            retriever.release();
        }
        try { Os.lseek(descriptor.getFileDescriptor(), 0, OsConstants.SEEK_SET); }
        catch (Exception unseekable) { /* the extractor reports an unreadable source itself */ }
        MediaExtractor extractor = new MediaExtractor();
        try {
            source(extractor, descriptor);
            for (int index = 0; index < extractor.getTrackCount(); index++) {
                MediaFormat format = extractor.getTrackFormat(index);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) continue;
                int degrees = format.containsKey(MediaFormat.KEY_ROTATION)
                        ? format.getInteger(MediaFormat.KEY_ROTATION) : (int) number(rotation);
                long millis = format.containsKey(MediaFormat.KEY_DURATION)
                        ? format.getLong(MediaFormat.KEY_DURATION) / 1000 : number(duration);
                // Some fragmented MP4s state no length anywhere (a zeroed mvhd, no mehd, empty sample
                // tables); the video's own sample times answer instead.
                if (millis <= 0) millis = scanDuration(extractor, index);
                AmLocalPolicy.Probe probe = new AmLocalPolicy.Probe(mime,
                        format.getNumber(MediaFormat.KEY_WIDTH, 0).intValue(), format.getNumber(MediaFormat.KEY_HEIGHT, 0).intValue(),
                        ((degrees % 360) + 360) % 360, millis, format.getNumber(MediaFormat.KEY_FRAME_RATE, 0).doubleValue(),
                        format.getNumber(MediaFormat.KEY_PROFILE, 0).intValue(),
                        format.getNumber(MediaFormat.KEY_COLOR_TRANSFER, 0).intValue());
                return new Source(probe, index, format);
            }
            throw new AmFailure(Status.UNSUPPORTED, "local_not_video");
        } finally {
            extractor.release();
        }
    }

    private static long number(String text) {
        try { return text == null ? 0 : Long.parseLong(text.trim()); }
        catch (NumberFormatException unknown) { return 0; }
    }

    /**
     * A container that states no length anywhere: read the video's own sample times, stopping as soon
     * as the limit is passed so a long file is never scanned to its end. Zero when nothing is readable.
     */
    private static long scanDuration(MediaExtractor extractor, int track) {
        long limitUs = (AmLocalPolicy.MAX_DURATION_MS + AmLocalPolicy.DURATION_TOLERANCE_MS) * 1000;
        try {
            extractor.selectTrack(track);
            long last = -1, step = 0;
            while (extractor.getSampleTime() >= 0) {
                long now = extractor.getSampleTime();
                if (last >= 0) step = Math.max(step, now - last);
                if (now > limitUs) return now / 1000;
                last = now;
                if (!extractor.advance()) break;
            }
            // The last frame's own length is not in the samples; one frame step stands in for it.
            return last <= 0 ? 0 : (last + step) / 1000;
        } catch (RuntimeException unreadable) {
            return 0;
        }
    }

    private static MediaExtractor extractor(ParcelFileDescriptor descriptor, int track) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            source(extractor, descriptor);
            extractor.selectTrack(track);
            return extractor;
        } catch (Exception error) {
            extractor.release();
            throw error;
        }
    }

    private static void source(MediaExtractor extractor, ParcelFileDescriptor descriptor) throws Exception {
        long length = descriptor.getStatSize();
        if (length > 0) extractor.setDataSource(descriptor.getFileDescriptor(), 0, length);
        else extractor.setDataSource(descriptor.getFileDescriptor());
    }

    private static String decoder(MediaFormat format) {
        MediaCodecList codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        MediaFormat query = new MediaFormat(format);
        query.removeKey(MediaFormat.KEY_ROTATION);
        try {
            String name = codecs.findDecoderForFormat(query);
            if (name != null) return name;
        } catch (RuntimeException unsupported) { /* fall back to a plain type and size match */ }
        // Some containers report a profile or level combination the lookup refuses that a decoder still plays.
        String mime = format.getString(MediaFormat.KEY_MIME);
        int width = format.getNumber(MediaFormat.KEY_WIDTH, 0).intValue(), height = format.getNumber(MediaFormat.KEY_HEIGHT, 0).intValue();
        for (MediaCodecInfo info : codecs.getCodecInfos()) {
            if (info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (!type.equalsIgnoreCase(mime)) continue;
                MediaCodecInfo.VideoCapabilities video = info.getCapabilitiesForType(type).getVideoCapabilities();
                if (video != null && video.isSizeSupported(width, height)) return info.getName();
            }
        }
        return null;
    }

    /** A hardware AVC encoder fed from a surface, else a software one. */
    private static Encoder encoder() {
        Encoder software = null;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (!type.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_AVC)) continue;
                MediaCodecInfo.CodecCapabilities capabilities = info.getCapabilitiesForType(type);
                if (capabilities.getVideoCapabilities() == null) continue;
                boolean surface = false, high = false;
                for (int color : capabilities.colorFormats) {
                    surface |= color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface;
                }
                for (MediaCodecInfo.CodecProfileLevel level : capabilities.profileLevels) {
                    high |= level.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh;
                }
                if (!surface) continue;
                Encoder encoder = new Encoder(info.getName(), capabilities.getVideoCapabilities(), high);
                if (info.isHardwareAccelerated()) return encoder;
                if (software == null) software = encoder;
            }
        }
        return software;
    }

    /** The same check the lock screen runs on every file, plus this import's own size rule. */
    private static ArtworkAsset verify(File file) throws AmFailure {
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
            ArtworkAsset asset = ArtworkFileVerifier.inspect(descriptor, UUID.randomUUID().toString(), "local-v1", AmLocalPolicy.MAX_BYTES);
            if (!"video/avc".equals(asset.codec) || Math.max(asset.width, asset.height) > AmLocalPolicy.MAX_SIDE) {
                throw new AmFailure(Status.UNSUPPORTED, "local_output_invalid");
            }
            return asset;
        } catch (AmFailure failure) {
            throw failure;
        } catch (Exception error) {
            throw new AmFailure(Status.UNSUPPORTED, "local_output_invalid", 0, AmExceptionDiagnostic.describe("local_verify", error));
        }
    }

    /** Durably renames the finished file to its content hash; importing the same result again reuses it. */
    private static String commit(File temp, File folder) throws Exception {
        try (FileOutputStream sync = new FileOutputStream(temp, true)) { sync.getFD().sync(); }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream stream = new FileInputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = stream.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        String id = String.format(java.util.Locale.ROOT, "%064x", new BigInteger(1, digest.digest()));
        File target = new File(folder, id + ".mp4");
        if (target.isFile() && target.length() == temp.length()) return id;
        if (!temp.renameTo(target)) throw new java.io.IOException("local_commit");
        return id;
    }

    private static ParcelFileDescriptor open(ContentResolver resolver, Uri uri) throws AmFailure {
        try {
            ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r");
            if (descriptor == null) throw new java.io.FileNotFoundException();
            return descriptor;
        } catch (Exception error) {
            throw new AmFailure(Status.UNSUPPORTED, "local_unreadable", 0, AmExceptionDiagnostic.describe("local_open", error));
        }
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (RuntimeException unnamed) { /* a provider without names still imports */ }
        return "";
    }
}
