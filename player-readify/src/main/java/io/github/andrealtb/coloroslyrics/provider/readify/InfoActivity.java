package io.github.andrealtb.coloroslyrics.provider.readify;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
public final class InfoActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setPadding(32, 48, 32, 32);
        text.setText("Readify Lyrics Provider\n\nEnable only for com.readin.app in LSPosed (API 102).\nSupports Readify 3.1.0 only.\n\nRequires Bridge sentence-window-v1 support for event-driven highlighting.\nChunk AI: up to two previous and two following cached sentences. Other TTS: current sentence.\n\nThis screen does not prove that hooks are loaded.");
        setContentView(text);
    }
}
