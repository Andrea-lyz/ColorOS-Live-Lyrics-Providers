package io.github.andrealtb.coloroslyrics.provider.readify;

import android.app.Application;
import io.github.andrealtb.coloroslyrics.provider.modulestatus.ModuleStatus;

/** Keeps the standalone Readify settings activity independent from other Provider modules. */
public final class ReadifyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Lets the Bridge store tell whether this module is enabled in the LSPosed manager.
        ModuleStatus.register();
    }
}
