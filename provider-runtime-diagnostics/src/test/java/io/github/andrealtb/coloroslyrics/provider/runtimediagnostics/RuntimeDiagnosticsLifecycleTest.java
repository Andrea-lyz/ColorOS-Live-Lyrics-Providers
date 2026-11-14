package io.github.andrealtb.coloroslyrics.provider.runtimediagnostics;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 34, manifest = Config.NONE)
public class RuntimeDiagnosticsLifecycleTest {
    private static final String MODULE = "io.github.example.provider";
    private static final String ID = "ColorOS-Live-Lyrics-Providers:player-salt:debug:" + "a".repeat(40) + ":dirty:" + "b".repeat(64) + ":resolver-1";
    @Before public void before() { ProviderRuntimeDiagnostics.resetForTest(); }
    @After public void after() { ProviderRuntimeDiagnostics.resetForTest(); }
    private void initialize() { ProviderRuntimeDiagnostics.initialize(MODULE, "com.example.player", ID, "LSPosed / 2.2.1", 102, 1L); }
    private static class FakeContext extends ContextWrapper {
        final String pkg;
        String requiredPermission;
        int flags;
        BroadcastReceiver receiver;
        final List<Intent> sent = new ArrayList<>();
        FakeContext(String pkg) { super(RuntimeEnvironment.getApplication()); this.pkg = pkg; }
        @Override public String getPackageName() { return pkg; }
        @Override public Context getApplicationContext() { return this; }
        @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
                String permission, Handler handler, int flags) {
            this.receiver = receiver; this.requiredPermission = permission; this.flags = flags; return null;
        }
        @Override public void unregisterReceiver(BroadcastReceiver receiver) { this.receiver = null; }
        @Override public void sendBroadcast(Intent intent) { sent.add(intent); }
        @Override public void startActivity(Intent intent) { throw new AssertionError("must not start any host"); }
        @Override public android.content.ComponentName startService(Intent intent) { throw new AssertionError("must not start service"); }
        @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) { throw new AssertionError("query must not reread configuration"); }
    }
    @Test public void registrationUsesPerModuleSignaturePermissionAndNeverWakesHost() {
        initialize(); FakeContext host = new FakeContext("com.example.player");
        ProviderRuntimeDiagnostics.register(host);
        assertEquals(MODULE + ".permission.READ_RUNTIME_DIAGNOSTICS", host.requiredPermission);
        assertEquals(Context.RECEIVER_EXPORTED, host.flags);
        assertTrue(host.sent.isEmpty());
    }
    /** Real Salt overrides this getter using state initialized after Application.attach. */
    private static final class EarlyHostApplication extends Application {
        int applicationContextCalls;
        BroadcastReceiver registered;
        String requiredPermission;
        @Override public String getPackageName() { return "com.example.player"; }
        @Override public Context getApplicationContext() {
            applicationContextCalls++;
            throw new NullPointerException("host application state not initialized until onCreate");
        }
        @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
                String permission, Handler handler, int flags) {
            registered = receiver; requiredPermission = permission; return null;
        }
        @Override public void unregisterReceiver(BroadcastReceiver receiver) { registered = null; }
    }
    @Test public void earlyApplicationNeverInvokesOverriddenApplicationContextGetter() {
        initialize(); EarlyHostApplication host = new EarlyHostApplication();
        ProviderRuntimeDiagnostics.register(host);
        assertEquals(0, host.applicationContextCalls);
        assertNotNull(host.registered);
        assertEquals(ProviderRuntimeDiagnostics.permission(MODULE), host.requiredPermission);
    }
    @Test public void malformedOptionalContextDoesNotEscapeIntoHostStartup() {
        initialize();
        FakeContext host = new FakeContext("com.example.player") {
            @Override public String getPackageName() { throw new IllegalStateException("optional diagnostic context unavailable"); }
        };
        ProviderRuntimeDiagnostics.register(host);
        assertNull(host.receiver);
    }
    /** Fails the first {@code failures} registerReceiver calls, then registers normally. */
    private static final class FlakyRegistrationContext extends FakeContext {
        int failures;
        int registerCalls;
        FlakyRegistrationContext(int failures) { super("com.example.player"); this.failures = failures; }
        @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
                String permission, Handler handler, int flags) {
            registerCalls++;
            if (failures-- > 0) throw new SecurityException("synthetic registration failure");
            return super.registerReceiver(receiver, filter, permission, handler, flags);
        }
    }
    @Test public void failedRegistrationIsRetriedOnALaterCall() {
        initialize(); FlakyRegistrationContext host = new FlakyRegistrationContext(1);
        ProviderRuntimeDiagnostics.register(host);
        assertNull(host.receiver);
        ProviderRuntimeDiagnostics.register(host);
        assertNotNull(host.receiver);
        ProviderRuntimeDiagnostics.register(host);
        assertEquals(2, host.registerCalls);
    }
    @Test public void persistentRegistrationFailureStopsAfterBoundedAttempts() {
        initialize(); FlakyRegistrationContext host = new FlakyRegistrationContext(Integer.MAX_VALUE);
        for (int i = 0; i < 10; i++) ProviderRuntimeDiagnostics.register(host);
        assertNull(host.receiver);
        assertEquals(ProviderRuntimeDiagnostics.MAX_REGISTRATION_ATTEMPTS, host.registerCalls);
    }
    @Test public void processMismatchDoesNotConsumeRegistrationAttempts() {
        initialize();
        for (int i = 0; i < 10; i++) ProviderRuntimeDiagnostics.register(new FakeContext("com.other.player"));
        FakeContext host = new FakeContext("com.example.player"); ProviderRuntimeDiagnostics.register(host);
        assertNotNull(host.receiver);
    }
    @Test public void onlyMatchingHostProcessCanRegister() {
        initialize(); FakeContext other = new FakeContext("com.other.player"); ProviderRuntimeDiagnostics.register(other);
        assertNull(other.receiver);
    }
    @Test public void duplicateLoadDoesNotReplaceObservedOldBuildOrEpoch() {
        initialize(); Bundle before = ProviderRuntimeDiagnostics.snapshot(UUID.randomUUID().toString(), "com.example.player");
        ProviderRuntimeDiagnostics.initialize(MODULE, "com.example.player", ID + "new", "new framework", 102, 1L);
        Bundle after = ProviderRuntimeDiagnostics.snapshot(UUID.randomUUID().toString(), "com.example.player");
        assertEquals(ID, after.getString("observedBuildId"));
        assertEquals(before.getString("processEpoch"), after.getString("processEpoch"));
    }
    @Test public void statusOnlyReflectsTheNormalConsumerObservation() {
        initialize();
        assertEquals(-1L, ProviderRuntimeDiagnostics.snapshot("unused", "com.example.player").getLong("configRevision"));
        ProviderRuntimeDiagnostics.configConsumed(true, 99L, "CONSUMED");
        Bundle b = ProviderRuntimeDiagnostics.snapshot("unused", "com.example.player");
        assertEquals(99L, b.getLong("configRevision")); assertTrue(b.getBoolean("debugEnabled"));
        assertEquals("NOT_OBSERVED", b.getString("installerStage"));
    }
    @Test public void nativeCallbackTransportDoesNotExposeModuleSubclass() {
        ResultReceiver callback = RuntimeDiagnosticsClient.platform(new ResultReceiver(null) {});
        assertEquals(ResultReceiver.class, callback.getClass());
    }
    @Test public void requestOnlyTargetsAlreadyRegisteredReceiversAndIsSingleWindow() {
        FakeContext app = new FakeContext(MODULE);
        RuntimeDiagnosticsClient client = new RuntimeDiagnosticsClient(app, ID, List.of("com.example.player"), ignored -> {});
        client.start(); client.start(); assertEquals(1, app.sent.size());
        Intent query = app.sent.get(0);
        assertEquals("com.example.player", query.getPackage());
        assertTrue((query.getFlags() & Intent.FLAG_RECEIVER_REGISTERED_ONLY) != 0);
        assertTrue(RuntimeDiagnosticProtocol.requestId(query.getStringExtra(ProviderRuntimeDiagnostics.EXTRA_REQUEST)));
        client.close();
    }
    @Test public void timeoutIsUnknownNotAnInactiveModuleAndCloseStopsCallbacks() {
        FakeContext app = new FakeContext(MODULE); final int[] callbacks = {0};
        RuntimeDiagnosticsClient client = new RuntimeDiagnosticsClient(app, ID, List.of("com.example.player"), result -> {
            callbacks[0]++; assertTrue(result.replies().isEmpty()); assertTrue(result.format().contains("unknown"));
        });
        client.start(); Shadows.shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS);
        assertEquals(1, callbacks[0]); client.close();
        RuntimeDiagnosticsClient closed = new RuntimeDiagnosticsClient(app, ID, List.of("com.example.player"), result -> callbacks[0]++);
        closed.start(); closed.close(); Shadows.shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS);
        assertEquals(1, callbacks[0]);
    }
    @Test public void malformedOrCrossModuleQueriesDoNotReply() {
        initialize(); FakeContext host = new FakeContext("com.example.player"); ProviderRuntimeDiagnostics.register(host);
        final int[] replies = {0}; ResultReceiver callback = new ResultReceiver(null) {
            @Override protected void onReceiveResult(int code, Bundle data) { replies[0]++; }
        };
        Intent bad = new Intent(ProviderRuntimeDiagnostics.action(MODULE))
            .putExtra(ProviderRuntimeDiagnostics.EXTRA_SCHEMA, 1)
            .putExtra(ProviderRuntimeDiagnostics.EXTRA_MODULE, "com.wrong.module")
            .putExtra(ProviderRuntimeDiagnostics.EXTRA_REQUEST, UUID.randomUUID().toString())
            .putExtra(ProviderRuntimeDiagnostics.EXTRA_CALLBACK, callback);
        host.receiver.onReceive(host, bad); assertEquals(0, replies[0]);
        bad.putExtra(ProviderRuntimeDiagnostics.EXTRA_MODULE, MODULE);
        host.receiver.onReceive(host, bad); assertEquals(1, replies[0]);
    }
}
