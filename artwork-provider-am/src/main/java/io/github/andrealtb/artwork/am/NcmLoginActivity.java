package io.github.andrealtb.artwork.am;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

/** Foreground-only QR login. Each poll has its own short network task; leaving stops polling. */
public final class NcmLoginActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ImageView image;
    private TextView state;
    private TextView retry;
    private ProgressBar busy;
    private NcmApi api;
    private NcmApi.Qr qr;
    private AmNetwork.Task task;
    private int generation;
    private boolean resumed;
    private long created;
    private long epoch;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setDecorFitsSystemWindows(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(dp(18) + bars.left, dp(12) + bars.top, dp(18) + bars.right, dp(24) + bars.bottom);
            return insets;
        });
        TextView back = AmUi.tonalButton(this, getString(R.string.binding_back), getColor(R.color.am_accent));
        back.setOnClickListener(view -> finish());
        content.addView(back, new LinearLayout.LayoutParams(AmUi.WRAP, AmUi.WRAP));
        content.addView(AmUi.text(this, getString(R.string.netease_login_title), 24, getColor(R.color.am_text), true), AmUi.marginTop(this, 20));
        content.addView(AmUi.text(this, getString(R.string.netease_scan_hint), 14, getColor(R.color.am_text_secondary), false), AmUi.marginTop(this, 8));
        LinearLayout card = AmUi.card(this);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        image = new ImageView(this);
        image.setContentDescription(getString(R.string.netease_qr_description));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int size = Math.max(dp(120), Math.min(dp(244), getResources().getDisplayMetrics().widthPixels - dp(76)));
        card.addView(image, new LinearLayout.LayoutParams(size, size));
        // Keep the disclosure immediately below the QR; it is present before any login response.
        TextView risk = AmUi.text(this, getString(R.string.netease_disclaimer), 12.5f, getColor(R.color.am_warn), false);
        card.addView(risk, AmUi.marginTop(this, 14));
        content.addView(card, AmUi.marginTop(this, 18));
        busy = new ProgressBar(this);
        content.addView(busy, AmUi.marginTop(this, 16));
        state = AmUi.text(this, "", 14, getColor(R.color.am_text_secondary), false);
        state.setGravity(Gravity.CENTER);
        state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(state, AmUi.marginTop(this, 12));
        retry = AmUi.primaryButton(this, getString(R.string.netease_qr_refresh));
        retry.setOnClickListener(view -> begin());
        content.addView(retry, AmUi.marginTop(this, 16));
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getColor(R.color.am_bg));
        scroll.addView(content);
        setContentView(scroll);
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (!NcmSession.enabled(this)) { finish(); return; }
        if (NcmSession.read(this) != null) { setResult(RESULT_OK); finish(); return; }
        if (qr == null || System.currentTimeMillis() - created >= 180_000) begin();
        else poll(generation);
    }
    @Override protected void onPause() { resumed = false; stop(); super.onPause(); }
    @Override protected void onDestroy() { stop(); io.shutdownNow(); super.onDestroy(); }
    private void stop() {
        generation++;
        main.removeCallbacksAndMessages(null);
        if (task != null) task.cancel();
    }
    private void begin() {
        stop();
        if (!resumed || !NcmSession.enabled(this)) return;
        int current = generation;
        epoch = NcmSession.epoch();
        long loginEpoch = epoch;
        api = new NcmApi(getApplicationContext(), () -> NcmSession.enabled(this)
                && NcmSession.epoch() == loginEpoch && AmSettings.connected(this));
        qr = null;
        image.setImageDrawable(null);
        retry.setVisibility(View.GONE);
        show(R.string.netease_qr_loading, true);
        task = new AmNetwork.Task();
        AmNetwork.Task active = task;
        io.execute(() -> {
            try {
                NcmApi.Qr fresh = api.qr(active);
                Bitmap bitmap = bitmap(fresh.url());
                ui(current, () -> {
                    qr = fresh; created = System.currentTimeMillis(); image.setImageBitmap(bitmap);
                    show(R.string.netease_qr_waiting, false); poll(current);
                });
            } catch (Exception failure) { ui(current, () -> failed(failure)); }
        });
    }
    private void poll(int current) {
        if (!resumed || current != generation || qr == null) return;
        if (System.currentTimeMillis() - created >= 180_000) { expired(); return; }
        NcmApi.Qr scanned = qr;
        NcmApi loginApi = api;
        long loginEpoch = epoch;
        task = new AmNetwork.Task();
        AmNetwork.Task active = task;
        io.execute(() -> {
            try {
                NcmApi.QrState reply = loginApi.check(scanned, active);
                if (reply.code() == 803) {
                    if (!NcmProtocol.loggedIn(reply.cookie())) throw new AmFailure(Status.RETRY_LATER, "netease_login_cookie_missing", 30_000);
                    NcmSession.Session session = new NcmSession.Session(reply.cookie(), "", "", System.currentTimeMillis(), loginEpoch);
                    try { session = loginApi.account(session, active); }
                    catch (AmFailure failure) { if (failure.status == Status.NETWORK_BLOCKED || failure.reason.equals("cancelled")) throw failure; }
                    active.check();
                    if (!NcmSession.save(getApplicationContext(), session, loginEpoch)) throw new AmFailure(Status.NETWORK_BLOCKED, "netease_source_disabled");
                    ui(current, () -> { show(R.string.netease_login_success, false); setResult(RESULT_OK); finish(); });
                    return;
                }
                ui(current, () -> {
                    if (reply.code() == 800) { expired(); return; }
                    if (reply.code() != 801 && reply.code() != 802) { failed(new IllegalStateException()); return; }
                    show(reply.code() == 802 ? R.string.netease_qr_confirm : R.string.netease_qr_waiting, false);
                    main.postDelayed(() -> poll(current), 2000);
                });
            } catch (Exception failure) { ui(current, () -> failed(failure)); }
        });
    }
    private void expired() {
        image.setImageDrawable(null); qr = null;
        show(R.string.netease_qr_expired, false); retry.setVisibility(View.VISIBLE);
    }
    private void failed(Exception failure) {
        image.setImageDrawable(null); qr = null;
        show(failure instanceof AmFailure f && f.reason.equals("network_policy") ? R.string.binding_network : R.string.netease_login_failed, false);
        retry.setVisibility(View.VISIBLE);
    }
    private void ui(int current, Runnable action) { main.post(() -> { if (resumed && current == generation && !isDestroyed()) action.run(); }); }
    private void show(int text, boolean loading) { state.setText(text); busy.setVisibility(loading ? View.VISIBLE : View.GONE); }
    private int dp(int value) { return AmUi.dp(this, value); }
    private static Bitmap bitmap(String text) throws Exception {
        var matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 720, 720,
                Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 4));
        int[] pixels = new int[matrix.getWidth() * matrix.getHeight()];
        for (int y = 0; y < matrix.getHeight(); y++) for (int x = 0; x < matrix.getWidth(); x++)
            pixels[y * matrix.getWidth() + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
        return Bitmap.createBitmap(pixels, matrix.getWidth(), matrix.getHeight(), Bitmap.Config.ARGB_8888);
    }
}
