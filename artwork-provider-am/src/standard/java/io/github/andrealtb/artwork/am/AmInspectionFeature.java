package io.github.andrealtb.artwork.am;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.widget.LinearLayout;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.function.IntConsumer;

/** Standard builds keep text diagnostics only; no raw media collector or native probes are packaged. */
final class AmInspectionFeature {
    static void addControls(Activity activity, LinearLayout card, ExecutorService io, IntConsumer toast) {}
    static boolean handleResult(Activity activity, int requestCode, Uri destination, ExecutorService io, IntConsumer toast) { return false; }
    static void capture(Context context, File source, AmHls.Variant variant, AmHls.FilePlan plan, AmFailure failure) {}
}
