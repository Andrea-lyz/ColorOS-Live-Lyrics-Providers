package io.github.andrealtb.coloroslyrics.provider.readify;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;
import io.github.andrealtb.coloroslyrics.provider.scoperequest.ScopeRequestActivity;
import io.github.andrealtb.coloroslyrics.provider.scoperequest.ScopeRequestProtocol;
import java.io.InputStream;
import java.util.Set;
import java.util.UUID;
public final class InfoActivity extends Activity {
    private static final int REQUEST_SCOPE = 4102;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setPadding(32, 48, 32, 32);
        text.setText("Readify Lyrics Provider\n\nEnable only for com.readin.app in LSPosed (API 102).\nSupports Readify 3.1.0 only.\n\nRequires Bridge sentence-window-v1 support for event-driven highlighting.\nChunk AI: up to two previous and two following cached sentences. Other TTS: current sentence.\n\nThis screen does not prove that hooks are loaded.");
        text.append("\n\nInstalled buildId: " + BuildConfig.BUILD_ID
                + "\nInjected build: unknown (see provider/readify BUILD_ID log). Installation is not activation.");
        text.setTextIsSelectable(true);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL); root.addView(text);
        android.widget.Button diagnostic = new android.widget.Button(this);
        diagnostic.setText("Provider runtime diagnostics (read only)");
        diagnostic.setOnClickListener(v -> startActivity(new android.content.Intent(this,
                io.github.andrealtb.coloroslyrics.provider.runtimediagnostics.RuntimeDiagnosticsActivity.class)));
        root.addView(diagnostic);
        // Standalone front door for the scope request the Bridge store uses (5.0 plan §14.13).
        android.widget.Button scope = new android.widget.Button(this);
        scope.setText("Request player scope (LSPosed prompt)");
        scope.setOnClickListener(v -> requestOwnScope());
        root.addView(scope);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this); scroll.addView(root);
        setContentView(scroll);
    }

    private void requestOwnScope() {
        Set<String> hosts = java.util.Collections.emptySet();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(ScopeRequestProtocol.SCOPE_LIST_RESOURCE)) {
            if (in != null) hosts = ScopeRequestProtocol.INSTANCE.parseScopeList(in);
        } catch (java.io.IOException | RuntimeException ignored) {
            // Falls through to the empty-list message.
        }
        if (hosts.isEmpty()) {
            Toast.makeText(this, "No supported player to request", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, ScopeRequestActivity.class)
                .putExtra(ScopeRequestProtocol.EXTRA_PROTOCOL, ScopeRequestProtocol.VERSION)
                .putExtra(ScopeRequestProtocol.EXTRA_REQUEST_ID,
                        "self-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24))
                .putExtra(ScopeRequestProtocol.EXTRA_HOSTS, hosts.toArray(new String[0]));
        startActivityForResult(intent, REQUEST_SCOPE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SCOPE) return;
        String status = data == null ? null : data.getStringExtra(ScopeRequestProtocol.RESULT_STATUS);
        String text;
        if ("ALREADY_GRANTED".equals(status) || "GRANTED".equals(status)) {
            text = "Readify is in scope. Fully close and reopen Readify.";
        } else if ("NOT_GRANTED".equals(status)) {
            text = "Not approved (denied or \"never ask\"). You can select Readify in the LSPosed manager.";
        } else if ("PENDING".equals(status)) {
            text = "No answer from LSPosed yet. Handle the notification, then reopen this page.";
        } else if ("NO_SERVICE".equals(status)) {
            text = "Not connected to LSPosed: enable this module in LSPosed first.";
        } else {
            text = "The request failed. You can select Readify in the LSPosed manager.";
        }
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
