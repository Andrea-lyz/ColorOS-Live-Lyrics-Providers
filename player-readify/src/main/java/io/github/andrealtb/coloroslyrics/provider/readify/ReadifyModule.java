package io.github.andrealtb.coloroslyrics.provider.readify;

import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** Version-pinned Readify 3.1.0 adapter. All publication is serialized on main. */
public final class ReadifyModule extends XposedModule {
    private static final String TARGET = "com.readin.app";
    private static final String TAG = "ReadifyLyrics";
    private static final String KEY = "lyricInfo";
    private final Map<MediaSession, Record> sessions = new WeakHashMap<>();
    private final ThreadLocal<Boolean> writing = new ThreadLocal<>();
    private Handler main;
    private boolean installed;
    private volatile boolean supportedVersion;
    private volatile boolean versionResolved;
    private final AtomicLong callbacks = new AtomicLong();
    private final AtomicLong metadataEvents = new AtomicLong();
    private final AtomicLong playbackEvents = new AtomicLong();
    private WeakReference<Object> owner = new WeakReference<>(null);
    private String sentence = "";
    private String unit = "";
    private SentenceWindow window;
    private volatile String observedChunkId = "";
    private volatile WeakReference<Object> observedChunkOwner = new WeakReference<>(null);
    private long generation = 1;
    private long updates;
    private int errors;
    private boolean queued;
    private int lastCandidates = -1;
    private static final class Record {
        MediaMetadata original;
        String identity = "";
        String published;
        boolean verified;
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        if (!TARGET.equals(param.getPackageName()) || installed) return;
        installed = true;
        main = new Handler(Looper.getMainLooper());
        info("provider=1.0.0-dev loaded; player-only scope");
        try { installContextHooks(); }
        catch (Throwable e) { failure("context hooks", e); }
        try {
            installMediaHooks();
            Class<?> service = param.getClassLoader().loadClass("com.readin.app.reader.tts.MediaService");
            after(service.getDeclaredMethod("onCreate"), chain -> {
                android.content.Context context = (android.content.Context) chain.getThisObject();
                checkVersion(context, "media-service");
                info("Readify MediaService created");
            });
            info("media hooks ready; adapter=Readify-3.1.0 api=102");
        } catch (Throwable e) { failure("media hooks", e); return; }
        try { installChunkHooks(param.getClassLoader()); }
        catch (Throwable e) { failure("chunk hooks", e); }
        try { installReadiumHook(param.getClassLoader()); }
        catch (Throwable e) { failure("readium hooks", e); }
        main.post(() -> {
            try {
                Class<?> thread = Class.forName("android.app.ActivityThread");
                Method current = thread.getDeclaredMethod("currentApplication");
                current.setAccessible(true);
                Object application = current.invoke(null);
                if (application instanceof android.content.Context)
                    checkVersion((android.content.Context) application, "application");
                else info("application context pending");
            } catch (Throwable e) { failure("application context", e); }
        });
        main.postDelayed(this::runtimeSummary, 12000);
    }

    private void installContextHooks() throws Exception {
        after(android.app.Application.class.getDeclaredMethod("attach", android.content.Context.class),
            chain -> checkVersion((android.content.Context) chain.getArg(0), "application-attach"));
        for (Constructor<?> constructor : MediaSession.class.getDeclaredConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length > 0 && types[0] == android.content.Context.class) {
                after(constructor, chain -> {
                    checkVersion((android.content.Context) chain.getArg(0), "media-session");
                    info("platform MediaSession constructed");
                });
            }
        }
    }

    private synchronized void checkVersion(android.content.Context context, String origin) {
        if (versionResolved || context == null) return;
        try {
            String version = context.getPackageManager().getPackageInfo(TARGET, 0).versionName;
            supportedVersion = "3.1.0".equals(version);
            versionResolved = true;
            info((supportedVersion ? "host version accepted: 3.1.0" : "unsupported host version; publication disabled")
                + "; via=" + origin);
        } catch (Throwable e) { failure("version check", e); }
    }

    private void runtimeSummary() {
        info("runtime summary: version=" + (versionResolved ? (supportedVersion ? "accepted" : "rejected") : "pending")
            + " callbacks=" + callbacks.get() + " metadata=" + metadataEvents.get()
            + " playback=" + playbackEvents.get() + " sessions=" + sessions.size()
            + " sentenceChars=" + sentence.length());
    }

    // Host dex packages can contain Java keywords (e.g. "native"). Keep lookup dynamic:
    // lint's PrivateApiDetector cannot parse those valid DEX names as Java source types.
    private static Field contentCacheField(ClassLoader loader, String className, String fieldName)
            throws ReflectiveOperationException {
        return loader.loadClass(className).getDeclaredField(fieldName);
    }

    private void installChunkHooks(ClassLoader loader) throws Exception {
        Class<?> controller = loader.loadClass("com.readin.app.reader.tts.chunk.ChunkSdkPlaybackController");
        Field content = controller.getDeclaredField("b");
        content.setAccessible(true);
        for (Constructor<?> constructor : controller.getDeclaredConstructors()) {
            if (constructor.getParameterTypes().length > 0 && constructor.getParameterTypes()[0] == android.content.Context.class)
                after(constructor, chain -> checkVersion((android.content.Context) chain.getArg(0), "chunk-controller"));
        }
        Method resolve = loader.loadClass("com.readin.app.reader.tts.chunk.a").getDeclaredMethod("e", String.class);
        Method text = loader.loadClass("org.readium.navigator.media.tts.b").getDeclaredMethod("c");
        Field cache = contentCacheField(loader, "com.readin.app.reader.tts.chunk.ReadiumTtsContentSession", "b");
        cache.setAccessible(true);
        Field nativeCache = contentCacheField(loader, "com.readin.app.reader.tts.native.NativeChunkContentSession", "c");
        nativeCache.setAccessible(true);
        Method packetId = loader.loadClass("com.myhexin.customSynthesize.library.chunk.g0").getDeclaredMethod("a");
        Method tokenId = loader.loadClass("com.myhexin.customSynthesize.library.chunk.s0").getDeclaredMethod("d");
        after(controller.getDeclaredMethod("j", packetId.getDeclaringClass()), chain -> {
            String id = (String) packetId.invoke(chain.getArg(0));
            Object utterance = resolve.invoke(content.get(chain.getThisObject()), id);
            acceptChunk(chain.getThisObject(), content.get(chain.getThisObject()), cache, nativeCache, text, id,
                    utterance == null ? "" : (String) text.invoke(utterance));
        });
        // Fallback if the module observes a token after missing the packet start.
        after(controller.getDeclaredMethod("w", tokenId.getDeclaringClass()), chain -> {
            String id = (String) tokenId.invoke(chain.getArg(0));
            Object utterance = resolve.invoke(content.get(chain.getThisObject()), id);
            acceptChunk(chain.getThisObject(), content.get(chain.getThisObject()), cache, nativeCache, text, id,
                    utterance == null ? "" : (String) text.invoke(utterance));
        });
        after(controller.getDeclaredMethod("F"), chain -> clearOwner(chain.getThisObject()));
        after(controller.getDeclaredMethod("T"), chain -> clearOwner(chain.getThisObject()));
        after(controller.getDeclaredMethod("o"), chain -> clearOwner(chain.getThisObject()));
        info("chunk hooks ready: packet/token/stop/finish");
    }

    private void acceptChunk(Object controller, Object content, Field cache, Field nativeCache, Method textMethod,
            String id, String currentText) {
        if (observedChunkOwner.get() == controller && id != null && id.equals(observedChunkId)) return;
        observedChunkOwner = new WeakReference<>(controller);
        observedChunkId = id;
        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> texts = new ArrayList<>();
        try {
            // Read only the host's already-prefetched cache; never advance its coroutine source.
            Field selectedCache = cache.getDeclaringClass().isInstance(content) ? cache : nativeCache;
            if (!selectedCache.getDeclaringClass().isInstance(content)) {
                throw new IllegalArgumentException("unsupported content implementation");
            }
            Map<?, ?> map = (Map<?, ?>) selectedCache.get(content);
            ArrayList<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            int center = -1;
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).getKey().equals(id)) { center = i; break; }
            }
            if (center >= 0) {
                for (int i = Math.max(0, center - 2); i <= Math.min(entries.size() - 1, center + 2); i++) {
                    ids.add((String) entries.get(i).getKey());
                    texts.add((String) textMethod.invoke(entries.get(i).getValue()));
                }
            }
        } catch (Throwable e) {
            ids.clear(); texts.clear();
            failure("window cache", e);
        }
        accept(controller, id, currentText, SentenceWindow.select(ids, texts, id, currentText));
    }

    private void installReadiumHook(ClassLoader loader) throws Exception {
        Class<?> player = loader.loadClass("org.readium.navigator.media.tts.TtsPlayer");
        Field flow = player.getDeclaredField("i");
        flow.setAccessible(true);
        Method value = loader.loadClass("kotlinx.coroutines.flow.x").getMethod("getValue");
        Method text = loader.loadClass("org.readium.navigator.media.tts.TtsPlayer$c").getDeclaredMethod("e");
        after(player.getDeclaredMethod("R", loader.loadClass("sr.j")), chain -> {
            Object utterance = value.invoke(flow.get(chain.getThisObject()));
            if (utterance != null) {
                String current = (String) text.invoke(utterance);
                accept(chain.getThisObject(), current, current);
            }
        });
        // R can be inlined by ART; request deoptimization of the caller as well.
        Class<?> callback = loader.loadClass("org.readium.navigator.media.tts.TtsPlayer$speakUtterance$2");
        for (Method method : callback.getDeclaredMethods()) deoptimize(method);
        info("readium range hook ready");
    }

    private interface Observation { void run(XposedInterface.Chain chain) throws Exception; }
    private void after(Executable method, Observation action) {
        method.setAccessible(true);
        hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            try { action.run(chain); } catch (Throwable e) { failure("observation", e); }
            return result;
        });
        deoptimize(method);
    }

    private void installMediaHooks() throws Exception {
        after(MediaSession.class.getDeclaredMethod("setMetadata", MediaMetadata.class), chain -> {
            if (Boolean.TRUE.equals(writing.get())) return;
            if (metadataEvents.incrementAndGet() == 1) info("host metadata callback received");
            MediaSession session = (MediaSession) chain.getThisObject();
            MediaMetadata metadata = (MediaMetadata) chain.getArg(0);
            main.post(() -> {
                Record record = sessions.get(session);
                if (record == null) { record = new Record(); sessions.put(session, record); }
                String identity = identity(metadata);
                if (!record.identity.isEmpty() && !record.identity.equals(identity)) {
                    sentence = ""; unit = ""; window = null; observedChunkId = ""; observedChunkOwner = new WeakReference<>(null); generation++;
                }
                record.identity = identity;
                record.original = metadata;
                record.published = null;
                schedule();
            });
        });
        after(MediaSession.class.getDeclaredMethod("setPlaybackState", PlaybackState.class), chain -> {
            if (playbackEvents.incrementAndGet() == 1) info("host playback callback received");
            MediaSession session = (MediaSession) chain.getThisObject();
            PlaybackState state = (PlaybackState) chain.getArg(0);
            main.post(() -> {
                if (sessions.containsKey(session) && state != null &&
                    (state.getState() == PlaybackState.STATE_STOPPED || state.getState() == PlaybackState.STATE_ERROR)) {
                    sentence = ""; unit = ""; window = null; observedChunkId = ""; observedChunkOwner = new WeakReference<>(null);
                }
                schedule();
            });
        });
        after(MediaSession.class.getDeclaredMethod("setActive", boolean.class), chain -> main.post(this::schedule));
        after(MediaSession.class.getDeclaredMethod("release"), chain -> {
            MediaSession released = (MediaSession) chain.getThisObject();
            main.post(() -> { sessions.remove(released); if (sessions.isEmpty()) { sentence = ""; unit = ""; window = null; observedChunkId = ""; observedChunkOwner = new WeakReference<>(null); } });
        });
    }

    private void accept(Object controller, String id, String text) {
        accept(controller, id, text, null);
    }

    private void accept(Object controller, String id, String text, SentenceWindow nextWindow) {
        long count = callbacks.incrementAndGet();
        if (count == 1) {
            info("first sentence callback; versionResolved=" + versionResolved + " supported=" + supportedVersion
                + " hasText=" + (text != null && !text.isEmpty()));
            main.postDelayed(this::runtimeSummary, 1500);
        }
        if (!supportedVersion) return;
        String clean = SentenceSnapshot.clean(text);
        main.post(() -> {
            if (owner.get() != controller) {
                owner = new WeakReference<>(controller);
                generation++;
                unit = "";
                sentence = "";
            }
            String nextUnit = id == null ? "" : id;
            if (nextUnit.equals(unit) && clean.equals(sentence)) return;
            unit = nextUnit;
            sentence = clean;
            window = nextWindow;
            updates++;
            if (updates == 1 || updates % 25 == 0) info("sentence events=" + updates + " chars=" + clean.length());
            schedule();
        });
    }

    private void clearOwner(Object controller) {
        main.post(() -> {
            if (owner.get() != controller) return;
            sentence = ""; unit = ""; window = null; observedChunkId = ""; observedChunkOwner = new WeakReference<>(null);
            schedule();
        });
    }

    private void schedule() {
        if (queued) return;
        queued = true;
        main.postDelayed(() -> { queued = false; publish(); }, 70);
    }

    // ColorOS intentionally uses a vendor metadata key outside the SDK StringDef.
    @android.annotation.SuppressLint("WrongConstant")
    private void publish() {
        try {
            // No second session. Only a single active, playing/paused host session may own lyrics.
            MediaSession selected = null;
            int candidates = 0;
            for (Map.Entry<MediaSession, Record> entry : new ArrayList<>(sessions.entrySet())) {
                MediaSession session = entry.getKey();
                Record record = entry.getValue();
                if (record.original == null || record.identity.isEmpty() || !session.isActive()) continue;
                PlaybackState state = session.getController().getPlaybackState();
                if (state == null) continue;
                int s = state.getState();
                if (s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_PAUSED || s == PlaybackState.STATE_BUFFERING) {
                    selected = session; candidates++;
                }
            }
            if (candidates != lastCandidates) {
                lastCandidates = candidates;
                info("eligible media sessions=" + candidates);
            }
            if (candidates != 1) selected = null;
            for (Map.Entry<MediaSession, Record> entry : new ArrayList<>(sessions.entrySet())) {
                MediaSession session = entry.getKey();
                Record record = entry.getValue();
                if (record.original == null) continue;
                String desired = session == selected && !sentence.isEmpty() ? payload(record.original) : null;
                if (desired == null ? record.published == null : desired.equals(record.published)) continue;
                // Host-provided lyrics always win; only remove our own additions by restoring original.
                String existing = record.original.getString(KEY);
                if (existing != null && !existing.isEmpty()) continue;
                MediaMetadata outgoing = desired == null ? record.original :
                    new MediaMetadata.Builder(record.original).putString(KEY, desired).build();
                writing.set(true);
                try { session.setMetadata(outgoing); } finally { writing.remove(); }
                record.published = desired;
                String actual = session.getController().getMetadata().getString(KEY);
                if (desired != null && desired.equals(actual) && (!record.verified || updates % 25 == 0)) {
                    record.verified = true;
                    info("published/readback ok; mode=" + (window == null ? "current-sentence" : "sentence-window rows=" + window.lines.size() + " current=" + window.current) + "; chars=" + desired.length());
                }
            }
        } catch (Throwable e) { failure("publication", e); }
    }

    private String payload(MediaMetadata metadata) throws Exception {
        JSONObject json = new JSONObject();
        json.put("songName", string(metadata, MediaMetadata.METADATA_KEY_TITLE));
        json.put("artist", string(metadata, MediaMetadata.METADATA_KEY_ARTIST));
        json.put("songId", string(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID));
        json.put("lyricType", 0);
        json.put("lyric", window == null ? SentenceSnapshot.lrc(sentence) : window.lrc());
        if (window != null) {
            json.put("displayMode", "sentence-window-v1");
            json.put("currentLine", window.current);
        }
        json.put("noLyric", false);
        json.put("id", "");
        json.put("provider", TARGET);
        json.put("source", TARGET + "-readify-3.1.0-current-sentence");
        json.put("trackKey", ProviderTrackKey.build(
                string(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID),
                string(metadata, MediaMetadata.METADATA_KEY_TITLE),
                string(metadata, MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)));
        json.put("sessionGeneration", generation);
        return json.toString();
    }

    private static String string(MediaMetadata metadata, String key) {
        String value = metadata.getString(key);
        return value == null ? "" : value;
    }
    private static String identity(MediaMetadata metadata) {
        if (metadata == null) return "";
        String id = string(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID);
        String title = string(metadata, MediaMetadata.METADATA_KEY_TITLE);
        if (id.isEmpty() && title.isEmpty()) return "";
        return id + "|" + title + "|" + string(metadata, MediaMetadata.METADATA_KEY_ARTIST);
    }
    private void info(String message) { log(Log.INFO, TAG, message); }
    private void failure(String stage, Throwable error) {
        if (errors++ < 8) log(Log.WARN, TAG, stage + " failed: " + error.getClass().getSimpleName());
    }
}
