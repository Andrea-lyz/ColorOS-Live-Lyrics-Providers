package io.github.andrealtb.coloroslyrics.provider.runtimediagnostics;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.graphics.Color;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real Provider settings entry, not another test APK. Reading never wakes a player or requests scope. */
public final class RuntimeDiagnosticsActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Future<?> loading;
    private RuntimeDiagnosticsClient client;
    private TextView state;
    private Button refresh;
    private String installedId = "unknown";
    private List<String> targets = Collections.emptyList();
    private String exported = "{\"schema\":\"cll-provider-diagnostics-v1\",\"coverage\":\"not_collected\",\"processes\":[]}";
    private boolean started;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) exported = saved.getString("pending_export", exported);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(true);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightNavigationBars(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.WHITE);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom);
            return insets;
        });
        TextView title = new TextView(this); title.setText("Provider 自身运行诊断"); title.setTextSize(22); title.setTextColor(Color.BLACK); root.addView(title);
        state = new TextView(this); state.setTextSize(13); state.setTextColor(Color.DKGRAY); state.setTextIsSelectable(true);
        state.setText("正在读取本模块身份和编译期宿主范围…"); root.addView(state);
        refresh = new Button(this); refresh.setText("刷新（只读，可重复，无需清数据）"); refresh.setEnabled(false); refresh.setOnClickListener(v -> query()); root.addView(refresh);
        Button copy = new Button(this); copy.setText("复制诊断摘要"); copy.setOnClickListener(v -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Provider runtime diagnostics", state.getText()));
            Toast.makeText(this, "已复制；不含歌词和媒体身份", Toast.LENGTH_SHORT).show();
        }); root.addView(copy);
        Button save = new Button(this); save.setText("导出此页诊断 JSON"); save.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "provider-runtime-diagnostics.json");
            startActivityForResult(intent, 7);
        }); root.addView(save);
        ScrollView scroll = new ScrollView(this); scroll.addView(root); setContentView(scroll);
        Context app = getApplicationContext();
        loading = worker.submit(() -> {
            String identity = "unknown";
            try {
                String source = app.getPackageManager().getApplicationInfo(app.getPackageName(), 0).sourceDir;
                try (ZipFile apk = new ZipFile(source)) {
                    java.util.zip.ZipEntry entry = apk.getEntry("META-INF/cll/build-identity.properties");
                    if (entry != null) try (InputStream input = apk.getInputStream(entry)) { identity = RuntimeDiagnosticProtocol.readIdentity(input); }
                }
            } catch (Exception ignored) { }
            String capturedIdentity = identity;
            List<String> declared = RuntimeDiagnosticProtocol.declaredTargets(RuntimeDiagnosticsActivity.class);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                installedId = capturedIdentity; targets = declared; refresh.setEnabled(!targets.isEmpty());
                if (targets.isEmpty()) state.setText("编译期宿主范围不可读：unknown。不会查询任意外来应用。\n已安装 buildId：" + installedId);
                else if (started) query();
            });
        });
    }
    @Override protected void onSaveInstanceState(Bundle out) { out.putString("pending_export", exported); super.onSaveInstanceState(out); }
    @Override protected void onStart() { super.onStart(); started = true; if (!targets.isEmpty()) query(); }
    @Override protected void onStop() { started = false; if (client != null) client.close(); super.onStop(); }
    @Override protected void onDestroy() { if (loading != null) loading.cancel(true); worker.shutdownNow(); super.onDestroy(); }

    private void query() {
        if (!started || targets.isEmpty()) return;
        if (client != null) client.close();
        state.setText("只读查询已注册进程中（最多 2.5 秒）…\n没有回包时为 unknown，不启动播放器。\n已安装 buildId：" + installedId);
        client = new RuntimeDiagnosticsClient(this, installedId, targets, collector -> {
            if (isDestroyed() || !started) return;
            state.setText(collector.format());
            exported = encode(collector);
        });
        client.start();
    }
    private static String encode(RuntimeDiagnosticProtocol.Collector collector) {
        try {
            JSONObject root = new JSONObject(); root.put("schema", "cll-provider-diagnostics-v1");
            root.put("module", collector.module); root.put("installedBuildId", collector.installedBuildId);
            root.put("capturedAtElapsed", SystemClock.elapsedRealtime());
            root.put("rejectedReplies", collector.rejected); root.put("omittedReplies", collector.omitted);
            root.put("coverage", "registered_process_self_reports_current_user_only");
            JSONArray rows = new JSONArray();
            for (Bundle reply : collector.replies()) {
                JSONObject row = new JSONObject();
                for (String key : new String[]{"target", "process", "processEpoch", "observedBuildId", "framework", "installerStage", "configObservation", "evidenceScope"}) row.put(key, reply.getString(key, "unknown"));
                for (String key : new String[]{"moduleObservedAt", "replyObservedAt", "configRevision", "configObservedAt", "frameworkProperties"}) row.put(key, reply.getLong(key, -1));
                row.put("api", reply.getInt("api", -1)); row.put("debugEnabled", reply.getBoolean("debugEnabled", false)); rows.put(row);
            }
            root.put("processes", rows); return root.toString(2);
        } catch (Exception ignored) { return "{\"schema\":\"cll-provider-diagnostics-v1\",\"coverage\":\"serialization_failed\"}"; }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 7 || result != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData(); String contents = exported;
        worker.submit(() -> {
            boolean success = false;
            try (java.io.OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output != null) { output.write(contents.getBytes(StandardCharsets.UTF_8)); success = true; }
            } catch (Exception ignored) { }
            boolean saved = success;
            runOnUiThread(() -> { if (!isDestroyed()) Toast.makeText(this, saved ? "诊断已导出" : "诊断导出失败", Toast.LENGTH_SHORT).show(); });
        });
    }
}
