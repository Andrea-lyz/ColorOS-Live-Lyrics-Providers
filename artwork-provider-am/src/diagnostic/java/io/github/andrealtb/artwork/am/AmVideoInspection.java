package io.github.andrealtb.artwork.am;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline probes never return an asset, alter the resolve result, or operate on SystemUI. */
final class AmVideoInspection {
    private static final ExecutorService PREPARE = Executors.newSingleThreadExecutor(r -> daemon(r, "artwork-inspection-file"));
    private static final ExecutorService PROBES = Executors.newFixedThreadPool(3, r -> daemon(r, "artwork-inspection-media"));
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "artwork-inspection-watch"));
    private static AmVideoInspection instance;
    private final Context app;
    private final AmInspectionStore store;
    private final AtomicBoolean busy = new AtomicBoolean();

    private AmVideoInspection(Context context) {
        app = context.getApplicationContext();
        store = new AmInspectionStore(new File(app.getFilesDir(), "video-inspection"));
    }
    static synchronized AmVideoInspection get(Context context) {
        if (instance == null) instance = new AmVideoInspection(context);
        return instance;
    }
    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread;
    }
    boolean hasSample() { return store.hasSample(); }

    /** Only invoked after a complete video download has failed validation, while the file still exists. */
    void capture(File source, AmHls.Variant variant, AmHls.FilePlan plan, AmFailure failure) {
        if (!AmSettings.prefs(app).getBoolean("debug", false) || !busy.compareAndSet(false, true)) return;
        boolean retained = false;
        try {
            if (store.hasSample()) return;
            String info = AmDiagnostics.environment(app) + "\ncaptured=" + java.time.Instant.now()
                    + "\norigin=failed_download request=" + AmDiagnostics.requestId()
                    + "\nreason=" + failure.reason + " detail=" + (failure.detail == null ? "none" : failure.detail)
                    + "\nwidth=" + variant.width() + " height=" + variant.height() + " bitrate=" + variant.bitrate()
                    + "\nexpectedBytes=" + plan.bytes() + " durationSeconds=" + plan.durationSeconds()
                    + "\nmediaRef=" + AmCache.hash(plan.uri().getPath()).substring(0, 16)
                    + "\nOriginal public artwork video, byte-for-byte; no transcoding.\n";
            retained = store.retain(source, info);
            if (retained) {
                AmDiagnostics.record(app, "ARTWORK_AM_INSPECTION_RETAINED", "bytes=" + store.source().length());
                start();
            }
        } catch (Exception error) {
            AmDiagnostics.record(app, "ARTWORK_AM_INSPECTION_ERROR", AmExceptionDiagnostic.describe("retain_sample", error));
        } finally { if (!retained) busy.set(false); }
    }

    /** Background UI action; accepts only one size-bounded local sample and does not upload it. */
    boolean importSample(InputStream input) throws Exception {
        if (!busy.compareAndSet(false, true)) return false;
        boolean retained = false;
        try {
            retained = store.importSample(input, AmDiagnostics.environment(app) + "\ncaptured=" + java.time.Instant.now()
                    + "\norigin=manual_import\nOriginal local comparison video, byte-for-byte; no transcoding.\n");
            if (retained) start();
            return retained;
        } finally { if (!retained) busy.set(false); }
    }

    private void start() {
        PREPARE.execute(() -> {
            try {
                store.write("structure.txt", AmMp4Structure.describe(store.source()));
                AtomicInteger remaining = new AtomicInteger(3);
                for (String route : new String[] { "path", "fd", "fd-seek" }) {
                    Probe probe = new Probe(route, remaining);
                    PROBES.execute(probe::run);
                }
            } catch (Exception error) {
                write("structure.txt", "state=failed " + AmExceptionDiagnostic.describe("inspect_container", error));
                busy.set(false);
            }
        });
    }

    boolean clear() throws Exception {
        if (!busy.compareAndSet(false, true)) return false;
        try { store.clear(); return true; }
        finally { busy.set(false); }
    }

    /** Runs on the activity's IO worker. A blocked native call never prevents exporting the original. */
    boolean export(OutputStream output) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (busy.get() && System.nanoTime() < deadline) Thread.sleep(100);
        boolean complete = !busy.get();
        store.export(output, AmDiagnostics.snapshot(app));
        return complete;
    }

    private void write(String name, String text) {
        try { store.write(name, text); }
        catch (Exception error) { AmDiagnostics.record(app, "ARTWORK_AM_INSPECTION_ERROR", "stage=write_report exception=" + error.getClass().getSimpleName()); }
    }

    private final class Probe {
        private final String route;
        private final AtomicInteger remaining;
        private final StringBuilder report = new StringBuilder();
        private String stage = "queued";
        private boolean timedOut;
        private boolean done;

        Probe(String route, AtomicInteger remaining) { this.route = route; this.remaining = remaining; }
        private synchronized void stage(String next) { stage = next; persist(); }
        private synchronized void line(String line) { report.append(line).append('\n'); persist(); }
        private synchronized void timeout() { if (!done) { timedOut = true; persist(); } }
        private synchronized void finish() { done = true; persist(); }
        private void persist() {
            write(route + ".txt", "route=" + route + " state=" + (done ? "finished" : timedOut ? "stalled" : "running")
                    + " lastStage=" + stage + " exceeded12s=" + timedOut
                    + "\nDiagnostic only; getters are tested independently even when sample time is negative.\n" + report);
        }

        private void run() {
            MediaExtractor extractor = null;
            FileInputStream stream = null;
            var timer = WATCHDOG.schedule(this::timeout, 12, TimeUnit.SECONDS);
            try {
                stage("create_extractor"); extractor = new MediaExtractor();
                if (route.equals("path")) {
                    stage("set_data_source_path"); extractor.setDataSource(store.source().getAbsolutePath());
                } else {
                    stage("open_descriptor"); stream = new FileInputStream(store.source());
                    stage("set_data_source_fd"); extractor.setDataSource(stream.getFD(), 0, store.source().length());
                }
                stage("track_count"); int count = extractor.getTrackCount(); line("trackCount=" + count);
                int video = -1;
                for (int i = 0; i < Math.min(count, 8); i++) {
                    stage("track_format_" + i); MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    String safeMime = mime != null && mime.matches("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+") ? mime : "unknown";
                    line("track=" + i + " mime=" + safeMime);
                    if (mime != null && mime.startsWith("video/") && video < 0) {
                        video = i;
                        for (String key : new String[] { MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT, MediaFormat.KEY_ROTATION }) {
                            if (format.containsKey(key)) line(key + "=" + format.getInteger(key));
                        }
                    }
                }
                if (video < 0) { line("result=no_video_track"); return; }
                stage("select_track"); extractor.selectTrack(video);
                if (route.equals("fd-seek")) {
                    stage("seek_previous_sync_0"); extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                }
                // A failure in one getter must not hide another getter's result.
                MediaExtractor selected = extractor;
                getter("sample_time_us", selected::getSampleTime);
                getter("sample_track_index", selected::getSampleTrackIndex);
                getter("sample_size", selected::getSampleSize);
                getter("sample_flags", selected::getSampleFlags);
                getter("sample_read_bytes", () -> selected.readSampleData(ByteBuffer.allocate(4 * 1024 * 1024), 0));
            } catch (Exception error) {
                line(AmExceptionDiagnostic.describe(stage, error));
            } finally {
                if (extractor != null) {
                    try { stage("release_extractor"); extractor.release(); }
                    catch (RuntimeException error) { line(AmExceptionDiagnostic.describe("release_extractor", error)); }
                }
                if (stream != null) try { stream.close(); } catch (Exception ignored) { }
                finish(); timer.cancel(false);
                if (remaining.decrementAndGet() == 0) {
                    busy.set(false);
                    AmDiagnostics.record(app, "ARTWORK_AM_INSPECTION_FINISHED", "routes=3");
                }
            }
        }

        private void getter(String name, java.util.function.LongSupplier call) {
            stage(name);
            try { line(name + "=" + call.getAsLong()); }
            catch (RuntimeException error) { line(AmExceptionDiagnostic.describe(name, error)); }
        }
    }
}
