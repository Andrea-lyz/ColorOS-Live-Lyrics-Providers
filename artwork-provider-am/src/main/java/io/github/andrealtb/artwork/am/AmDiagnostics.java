package io.github.andrealtb.artwork.am;

import android.content.Context;
import android.os.Build;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Opt-in, bounded, asynchronous diagnostics. Never log query text, URLs or exception messages. */
final class AmDiagnostics {
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(256), runnable -> {
                Thread thread = new Thread(runnable, "artwork-diagnostics");
                thread.setDaemon(true);
                return thread;
            });
    private static final String SESSION = java.util.UUID.randomUUID().toString().substring(0, 8);
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final ThreadLocal<String> REQUEST = new ThreadLocal<>();

    private AmDiagnostics() {}

    static void begin() { REQUEST.set(SESSION + "-" + SEQUENCE.incrementAndGet()); }
    static <T> T withRequest(String request, ArtworkSources.Resolve<T> operation) throws AmFailure {
        String previous = REQUEST.get();
        REQUEST.set(request);
        try { return operation.run(); }
        finally { if (previous == null) REQUEST.remove(); else REQUEST.set(previous); }
    }
    static void end() { REQUEST.remove(); }
    static String requestId() { String request = REQUEST.get(); return request == null ? SESSION + "-none" : request; }

    static void record(Context context, String event, String fields) {
        try {
            if (!AmSettings.prefs(context).getBoolean("debug", false)) return;
            String request = REQUEST.get();
            String line = java.time.Instant.now() + " [CLL] level=INFO component=provider/artwork_am area=resource event="
                    + safe(event) + " request=" + (request == null ? SESSION + "-none" : request) + " " + safe(fields);
            android.util.Log.i("CLL-Artwork-AM", line);
            File root = new File(context.getFilesDir(), "diagnostics");
            WRITER.execute(() -> {
                try {
                    AmDiagnosticFile file = new AmDiagnosticFile(root);
                    long dropped = DROPPED.getAndSet(0);
                    if (dropped > 0) file.append("event=DIAGNOSTIC_DROPPED count=" + dropped);
                    file.append(line);
                } catch (Exception ignored) { DROPPED.incrementAndGet(); }
            });
        } catch (RuntimeException ignored) { DROPPED.incrementAndGet(); }
    }

    /** Call on a background thread; the queue barrier includes all previously accepted events. */
    static byte[] snapshot(Context context) throws Exception {
        File root = new File(context.getFilesDir(), "diagnostics");
        byte[] body = WRITER.submit(() -> new AmDiagnosticFile(root).snapshot()).get(10, TimeUnit.SECONDS);
        String header = "Dynamic artwork diagnostics\n" + environment(context)
                + "\nexported=" + java.time.Instant.now() + " droppedPending=" + DROPPED.get()
                + "\nNo song text, media IDs, URLs, credentials or private paths are included.\n\n";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        return out.toByteArray();
    }

    static void clear(Context context) throws Exception {
        File root = new File(context.getFilesDir(), "diagnostics");
        WRITER.submit(() -> { new AmDiagnosticFile(root).clear(); return null; }).get(10, TimeUnit.SECONDS);
    }

    static String environment(Context context) {
        String version = "unknown";
        try { version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (Exception ignored) { /* Export still works without package metadata. */ }
        return "package=" + safe(context.getPackageName()) + " version=" + safe(version)
                + " sdk=" + Build.VERSION.SDK_INT + " release=" + safe(Build.VERSION.RELEASE)
                + " manufacturer=" + safe(Build.MANUFACTURER) + " model=" + safe(Build.MODEL)
                + " enabled=" + AmSettings.enabled(context) + " debug=" + AmSettings.prefs(context).getBoolean("debug", false)
                + " market=" + AmSettings.country(context);
    }

    /** Only call with code-controlled fields, never a throwable message, query or server response. */
    private static String safe(String value) {
        if (value == null) return "unknown";
        return value.substring(0, Math.min(value.length(), 2048)).replace('\n', ' ').replace('\r', ' ');
    }
}
