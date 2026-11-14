package io.github.andrealtb.coloroslyrics.provider.runtimediagnostics;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import java.util.UUID;

/** In-process, metadata-only self observation. This is NOT framework attestation or lyric IPC. */
public final class ProviderRuntimeDiagnostics {
    public static final int SCHEMA = 1;
    public static final String EXTRA_REQUEST = "cll_diag_request";
    public static final String EXTRA_MODULE = "cll_diag_module";
    public static final String EXTRA_CALLBACK = "cll_diag_callback";
    public static final String EXTRA_SCHEMA = "cll_diag_schema";
    private static State state;
    private static BroadcastReceiver receiver;
    // Readify (every MediaSession construction) and Universal (every system_server setMetadata)
    // call register repeatedly. A failed registerReceiver may be retried, but only a few times so
    // a persistently failing platform call cannot recur on those paths.
    static final int MAX_REGISTRATION_ATTEMPTS = 3;
    private static int registrationAttempts;
    private static Context registeredContext;
    private ProviderRuntimeDiagnostics() { }

    public static String action(String module) { return module + ".action.READ_RUNTIME_DIAGNOSTICS"; }
    public static String permission(String module) { return module + ".permission.READ_RUNTIME_DIAGNOSTICS"; }

    public static synchronized void initialize(String module, String process, String buildId,
            String framework, int api, long properties) {
        if (!RuntimeDiagnosticProtocol.packageName(module) || process == null || process.length() > 256) return;
        if (state != null) return; // A duplicate callback cannot manufacture a new process epoch.
        state = new State(module, process, buildId, framework, api, properties,
                UUID.randomUUID().toString(), SystemClock.elapsedRealtime());
    }

    /** Called only after the normal configuration consumer has completed. Never reads preferences. */
    public static synchronized void configConsumed(boolean enabled, long revision, String observation) {
        if (state == null) return;
        state.debugEnabled = enabled;
        state.configRevision = revision;
        state.configObservation = observation == null ? "unknown" : observation;
        state.configObservedAt = SystemClock.elapsedRealtime();
    }
    public static synchronized void installerReturned(boolean success) {
        if (state != null) state.installerStage = success ? "INSTALLER_RETURNED" : "INSTALLER_FAILED";
    }

    /** Existing bootstrap/context capture calls this; no extra player hook or player launch is used. */
    public static synchronized void register(Context context) {
        if (context == null || state == null || receiver != null
                || registrationAttempts >= MAX_REGISTRATION_ATTEMPTS) return;
        try {
            final String target = context.getPackageName();
            if (!RuntimeDiagnosticProtocol.processMatches(target, state.process)) return;
            registrationAttempts++;
            // At Application.attach the host may override getApplicationContext() using state
            // initialized only in onCreate (real Salt failure). The Application IS the owner.
            Context application = null;
            if (!(context instanceof Application)) {
                try { application = context.getApplicationContext(); }
                catch (RuntimeException | LinkageError unavailable) { /* supplied context is still usable */ }
            }
            final Context owner = application == null ? context : application;
            final String module = state.module;
            BroadcastReceiver candidate = new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    if (intent == null || !action(module).equals(intent.getAction())) return;
                    try {
                        if (intent.getIntExtra(EXTRA_SCHEMA, -1) != SCHEMA
                                || !module.equals(intent.getStringExtra(EXTRA_MODULE))) return;
                        String request = intent.getStringExtra(EXTRA_REQUEST);
                        if (!RuntimeDiagnosticProtocol.requestId(request)) return;
                        @SuppressWarnings("deprecation")
                        ResultReceiver callback = intent.getParcelableExtra(EXTRA_CALLBACK);
                        if (callback == null) return;
                        Bundle response = snapshot(request, target);
                        if (response != null) callback.send(0, response);
                    } catch (RuntimeException ignoredFailure) {
                        // A malformed or dead callback must not affect the player's normal execution.
                    }
                }
            };
            IntentFilter filter = new IntentFilter(action(module));
            if (Build.VERSION.SDK_INT >= 33) {
                owner.registerReceiver(candidate, filter, permission(module), new Handler(Looper.getMainLooper()), Context.RECEIVER_EXPORTED);
            } else {
                owner.registerReceiver(candidate, filter, permission(module), new Handler(Looper.getMainLooper()));
            }
            receiver = candidate;
            registeredContext = owner;
        } catch (RuntimeException | LinkageError ignoredFailure) {
            // App queries will remain unknown. Never change installation, debug or lyric policy.
        }
    }

    static synchronized Bundle snapshot(String request, String target) {
        if (state == null) return null;
        Bundle result = new Bundle();
        result.putInt("schema", SCHEMA);
        result.putString("requestId", request);
        result.putString("module", state.module);
        result.putString("target", target);
        result.putString("process", state.process);
        result.putString("processEpoch", state.epoch);
        result.putString("observedBuildId", state.buildId);
        result.putString("framework", state.framework);
        result.putInt("api", state.api);
        result.putLong("frameworkProperties", state.properties);
        result.putLong("moduleObservedAt", state.loadedAt);
        result.putLong("replyObservedAt", SystemClock.elapsedRealtime());
        result.putLong("configRevision", state.configRevision);
        result.putLong("configObservedAt", state.configObservedAt);
        result.putString("configObservation", state.configObservation);
        result.putBoolean("debugEnabled", state.debugEnabled);
        result.putString("installerStage", state.installerStage);
        result.putString("evidenceScope", "IN_PROCESS_SELF_REPORT_NOT_LYRIC_EFFECT");
        return result;
    }

    static synchronized void resetForTest() {
        if (registeredContext != null && receiver != null) try { registeredContext.unregisterReceiver(receiver); } catch (RuntimeException ignored) { }
        state = null; receiver = null; registeredContext = null; registrationAttempts = 0;
    }
    private static final class State {
        final String module, process, buildId, framework, epoch;
        final int api;
        final long properties, loadedAt;
        long configRevision = -1L, configObservedAt = -1L;
        boolean debugEnabled;
        String configObservation = "NOT_OBSERVED", installerStage = "NOT_OBSERVED";
        State(String module, String process, String buildId, String framework, int api,
                long properties, String epoch, long loadedAt) {
            this.module = module; this.process = process; this.buildId = buildId;
            this.framework = framework; this.api = api; this.properties = properties;
            this.epoch = epoch; this.loadedAt = loadedAt;
        }
    }
}
