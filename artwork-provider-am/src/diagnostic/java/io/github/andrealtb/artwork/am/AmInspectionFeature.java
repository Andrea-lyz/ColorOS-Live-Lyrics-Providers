package io.github.andrealtb.artwork.am;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.function.IntConsumer;

/** Extra media collection and probes are compiled exclusively into the diagnostic variant. */
final class AmInspectionFeature {
    private static final int EXPORT_INSPECTION = 4102;
    private static final int IMPORT_INSPECTION = 4103;

    static void addControls(Activity activity, LinearLayout card, ExecutorService io, IntConsumer toast) {
        card.addView(AmUi.text(activity, activity.getString(R.string.inspection_summary), 13, activity.getColor(R.color.am_text_tertiary), false), AmUi.marginTop(activity, 14));
        TextView exportInspection = AmUi.tonalButton(activity, activity.getString(R.string.inspection_export), activity.getColor(R.color.am_accent));
        exportInspection.setOnClickListener(view -> {
            if (!AmVideoInspection.get(activity).hasSample()) { toast.accept(R.string.inspection_no_sample); return; }
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip").putExtra(Intent.EXTRA_TITLE, "artwork-inspection-" + System.currentTimeMillis() + ".zip");
            try { activity.startActivityForResult(intent, EXPORT_INSPECTION); }
            catch (ActivityNotFoundException error) { toast.accept(R.string.diagnostics_export_failed); }
        });
        card.addView(exportInspection, AmUi.marginTop(activity, 8));
        TextView importInspection = AmUi.tonalButton(activity, activity.getString(R.string.inspection_import), activity.getColor(R.color.am_muted));
        importInspection.setOnClickListener(view -> {
            if (AmVideoInspection.get(activity).hasSample()) { toast.accept(R.string.inspection_existing); return; }
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*");
            try { activity.startActivityForResult(intent, IMPORT_INSPECTION); }
            catch (ActivityNotFoundException error) { toast.accept(R.string.diagnostics_export_failed); }
        });
        card.addView(importInspection, AmUi.marginTop(activity, 8));
        TextView clearInspection = AmUi.tonalButton(activity, activity.getString(R.string.inspection_clear), activity.getColor(R.color.am_muted));
        clearInspection.setOnClickListener(view -> io.execute(() -> {
            try { toast.accept(AmVideoInspection.get(activity).clear() ? R.string.inspection_cleared : R.string.inspection_busy); }
            catch (Exception error) { toast.accept(R.string.diagnostics_export_failed); }
        }));
        card.addView(clearInspection, AmUi.marginTop(activity, 8));
    }

    static boolean handleResult(Activity activity, int requestCode, Uri destination, ExecutorService io, IntConsumer toast) {
        if (requestCode == IMPORT_INSPECTION) {
            io.execute(() -> {
                try (java.io.InputStream in = activity.getContentResolver().openInputStream(destination)) {
                    if (in == null) throw new java.io.IOException("inspection_input");
                    toast.accept(AmVideoInspection.get(activity).importSample(in) ? R.string.inspection_imported : R.string.inspection_existing);
                } catch (Exception error) { toast.accept(R.string.inspection_import_failed); }
            });
            return true;
        }
        if (requestCode == EXPORT_INSPECTION) {
            io.execute(() -> {
                try (java.io.OutputStream out = activity.getContentResolver().openOutputStream(destination, "wt")) {
                    if (out == null) throw new java.io.IOException("inspection_destination");
                    toast.accept(AmVideoInspection.get(activity).export(out) ? R.string.inspection_exported : R.string.inspection_exported_pending);
                } catch (Exception error) { toast.accept(R.string.diagnostics_export_failed); }
            });
            return true;
        }
        return false;
    }

    static void capture(Context context, File source, AmHls.Variant variant, AmHls.FilePlan plan, AmFailure failure) {
        AmVideoInspection.get(context).capture(source, variant, plan, failure);
    }
}
