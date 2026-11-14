package io.github.andrealtb.coloroslyrics.provider.runtimediagnostics;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.ResultReceiver;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** One cancellable read window. It only targets already registered receivers, never starts a host. */
public final class RuntimeDiagnosticsClient implements AutoCloseable {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final RuntimeDiagnosticProtocol.Collector collector;
    private Consumer<RuntimeDiagnosticProtocol.Collector> observer;
    private boolean closed, started;
    private final Runnable finish = this::finishQuery;
    private void finishQuery() {
        if (closed) return;
        collector.close();
        if (observer != null) observer.accept(collector);
    }
    public RuntimeDiagnosticsClient(Context context, String installedId, List<String> targets,
            Consumer<RuntimeDiagnosticProtocol.Collector> observer) {
        this.context = context.getApplicationContext();
        this.collector = new RuntimeDiagnosticProtocol.Collector(context.getPackageName(), UUID.randomUUID().toString(), installedId, targets);
        this.observer = observer;
    }
    public void start() {
        if (closed || started) return;
        started = true;
        ResultReceiver callback = platform(new ResultReceiver(main) {
            @Override protected void onReceiveResult(int code, Bundle data) {
                if (closed || code != 0 || data == null) return;
                if (collector.accept(data) && observer != null) observer.accept(collector);
            }
        });
        for (String target : collector.targets) {
            Intent intent = new Intent(ProviderRuntimeDiagnostics.action(collector.module)).setPackage(target)
                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(ProviderRuntimeDiagnostics.EXTRA_SCHEMA, 1)
                    .putExtra(ProviderRuntimeDiagnostics.EXTRA_MODULE, collector.module)
                    .putExtra(ProviderRuntimeDiagnostics.EXTRA_REQUEST, collector.requestId)
                    .putExtra(ProviderRuntimeDiagnostics.EXTRA_CALLBACK, callback);
            try { context.sendBroadcast(intent); } catch (RuntimeException ignored) { }
        }
        main.postDelayed(finish, 2500L);
    }
    static ResultReceiver platform(ResultReceiver callback) {
        Parcel parcel = Parcel.obtain();
        try {
            callback.writeToParcel(parcel, 0); parcel.setDataPosition(0);
            return ResultReceiver.CREATOR.createFromParcel(parcel);
        } finally { parcel.recycle(); }
    }
    @Override public void close() {
        closed = true; collector.close(); observer = null; main.removeCallbacks(finish);
    }
}
