package io.github.andrealtb.artwork.am;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.SystemClock;
import java.io.File;
import java.nio.ByteBuffer;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

/** Copy AVC samples into a local MP4 with zero-origin PTS; no decoder/encoder is created. */
final class AmMp4Normalizer {
    static void remux(File source, File output, AmHls.Variant variant, AmHls.FilePlan plan,
            long maxBytes, AmNetwork.Task task, java.util.function.Consumer<String> diagnostic) throws Exception {
        String stage = "extractor_create";
        MediaExtractor extractor = null;
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            extractor = new MediaExtractor();
            stage = "extractor_set_data_source";
            extractor.setDataSource(source.getAbsolutePath());
            stage = "extractor_track_count";
            if (extractor.getTrackCount() != 1) throw new AmFailure(Status.UNSUPPORTED, "source_track_layout");
            stage = "extractor_read_track_format";
            MediaFormat format = extractor.getTrackFormat(0);
            stage = "read_video_format_fields";
            if (!"video/avc".equals(format.getString(MediaFormat.KEY_MIME))
                    || format.getInteger(MediaFormat.KEY_WIDTH) != variant.width()
                    || format.getInteger(MediaFormat.KEY_HEIGHT) != variant.height()
                    || (format.containsKey(MediaFormat.KEY_ROTATION) && format.getInteger(MediaFormat.KEY_ROTATION) != 0)) {
                throw new AmFailure(Status.UNSUPPORTED, "source_format_mismatch");
            }
            stage = "extractor_select_track";
            extractor.selectTrack(0);
            stage = "extractor_initial_sample";
            AmInitialSample.Sample initial = AmInitialSample.read(extractor::getSampleTime, extractor::getSampleFlags, diagnostic);
            long origin = initial.timeUs();
            int initialFlags = initial.flags();
            if (diagnostic != null) {
                try {
                    diagnostic.accept("timeUs=" + origin + " flags=" + initialFlags
                            + " trackIndex=" + extractor.getSampleTrackIndex() + " size=" + extractor.getSampleSize()
                            + " sourceBytes=" + source.length());
                } catch (RuntimeException error) {
                    diagnostic.accept(AmExceptionDiagnostic.describe("diagnostic_initial_sample_getters", error));
                }
            }
            if ((initialFlags & MediaExtractor.SAMPLE_FLAG_SYNC) == 0) {
                throw new AmFailure(Status.UNSUPPORTED, "source_initial_keyframe_missing", 0,
                        "timeUs=" + origin + " flags=" + initialFlags + " invalidTime=" + (origin < 0)
                                + " sync=" + ((initialFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0));
            }
            stage = "set_output_duration";
            format.setLong(MediaFormat.KEY_DURATION, Math.round(plan.durationSeconds() * 1_000_000));
            stage = "muxer_create";
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            stage = "muxer_add_track";
            int track = muxer.addTrack(format);
            stage = "muxer_start";
            muxer.start(); started = true;
            ByteBuffer buffer = ByteBuffer.allocate(4 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long deadline = SystemClock.elapsedRealtime() + 3000;
            long compressed = 0, highestPts = 0;
            int samples = 0;
            stage = "extractor_sample_time";
            while (extractor.getSampleTime() >= 0) {
                task.check();
                long pts = extractor.getSampleTime() - origin;
                stage = "extractor_sample_flags";
                int flags = extractor.getSampleFlags();
                stage = "extractor_sample_size";
                long size = extractor.getSampleSize();
                if (++samples > 10000 || SystemClock.elapsedRealtime() >= deadline || size <= 0 || size > buffer.capacity()
                        || pts < 0 || pts > Math.round((plan.durationSeconds() + 1) * 1_000_000)
                        || (flags & (MediaExtractor.SAMPLE_FLAG_ENCRYPTED | MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME)) != 0) {
                    throw new AmFailure(Status.UNSUPPORTED, "source_sample_budget");
                }
                buffer.clear();
                stage = "extractor_read_sample";
                int read = extractor.readSampleData(buffer, 0);
                if (read != size || (compressed += read) > source.length()) throw new AmFailure(Status.UNSUPPORTED, "source_sample_incomplete");
                // Keep B-frame presentation reordering; do not force timestamps to increase or alter speed.
                info.set(0, read, pts, (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                stage = "muxer_write_sample";
                muxer.writeSampleData(track, buffer, info);
                highestPts = Math.max(highestPts, pts);
                if (output.length() > maxBytes) throw new AmFailure(Status.UNSUPPORTED, "remux_size_budget");
                stage = "extractor_advance";
                if (!extractor.advance()) break;
                stage = "extractor_sample_time";
            }
            if (samples == 0 || Math.abs(highestPts - Math.round(plan.durationSeconds() * 1_000_000)) > 1_000_000) {
                throw new AmFailure(Status.UNSUPPORTED, "source_duration_mismatch");
            }
            stage = "muxer_stop";
            muxer.stop(); started = false;
            if (output.length() <= 0 || output.length() > maxBytes) throw new AmFailure(Status.UNSUPPORTED, "remux_size_budget");
        } catch (AmFailure failure) {
            throw failure;
        } catch (Exception error) {
            throw new AmFailure(Status.UNSUPPORTED, AmFailure.mediaStageReason(stage), 0,
                    AmExceptionDiagnostic.describe(stage, error));
        } finally {
            try {
                if (muxer != null) {
                    if (started) try { muxer.stop(); } catch (RuntimeException ignored) { /* incomplete output is discarded */ }
                    muxer.release();
                }
            } finally { if (extractor != null) extractor.release(); }
        }
    }
}
