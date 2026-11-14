package io.github.andrealtb.coloroslyrics.provider.universal

import android.content.Context
import io.github.andrealtb.coloroslyrics.provider.runtimediagnostics.RuntimeDiagnosticProtocol

/** Diagnostic revision only; failure must never suppress a normal source-config update. */
internal object UniversalDiagnosticRevision {
    @Synchronized fun next(context: Context): Long = runCatching {
        val prefs = context.getSharedPreferences("runtime_diagnostic_revision", Context.MODE_PRIVATE)
        val previous = runCatching { prefs.getLong("revision", -1L) }.getOrDefault(-1L)
        val value = RuntimeDiagnosticProtocol.nextRevision(previous, System.currentTimeMillis())
        if (prefs.edit().putLong("revision", value).commit()) value else -1L
    }.getOrDefault(-1L)
}
