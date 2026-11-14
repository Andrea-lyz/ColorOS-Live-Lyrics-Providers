/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.core.config

import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticSink
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import io.github.andrealtb.coloroslyrics.provider.core.mode.RuntimeMode

enum class ProviderId(val configKey: String) {
    NETEASE("netease"),
    QQ("qq"),
    KUGOU("kugou"),
    KUWO("kuwo"),
    SPOTIFY("spotify"),
    LX("lx"),
    POWERAMP("poweramp"),
    SALT("salt"),
    CONE("cone"),
    QISHUI("qishui"),
    MUSICFREE("musicfree"),
    GRAMOPHONE("gramophone"),
    SYMFONIUM("symfonium"),
    METROLIST("metrolist"),
    APPLE("apple")
}

fun interface ProviderDebugSource {
    fun read(provider: ProviderId): Boolean?
}

interface ProviderDebugRevisionSource {
    fun observedRevision(): Long
}

data class ProviderDebugResolution(
    val enabled: Boolean,
    val reason: String
)

object ProviderDebugConfig {

    const val PREFS_NAME_SUFFIX = "_provider_debug_prefs"
    const val KEY_DEBUG_ENABLED = "provider_debug_logging_enabled"
    const val KEY_REVISION = "provider_debug_config_revision"

    fun prefsName(provider: ProviderId): String = "${provider.configKey}$PREFS_NAME_SUFFIX"

    fun resolve(
        mode: RuntimeMode,
        provider: ProviderId,
        rootSource: ProviderDebugSource? = null
    ): Boolean = resolveDetailed(mode, provider, rootSource).enabled

    fun resolveDetailed(
        mode: RuntimeMode,
        provider: ProviderId,
        rootSource: ProviderDebugSource? = null
    ): ProviderDebugResolution {
        if (mode != RuntimeMode.ROOT_MODULE) {
            return ProviderDebugResolution(false, "disabled:mode-${mode.name.lowercase()}")
        }
        if (rootSource == null) {
            return ProviderDebugResolution(false, "disabled:source-unavailable")
        }
        val raw = runCatching { rootSource.read(provider) }
            .getOrElse { error ->
                return ProviderDebugResolution(
                    false,
                    "disabled:read-${error.javaClass.simpleName}"
                )
            }
        return when (raw) {
            true -> ProviderDebugResolution(true, "enabled")
            false -> ProviderDebugResolution(false, "disabled")
            null -> ProviderDebugResolution(false, "disabled:source-unavailable")
        }
    }

    fun configureDiagnostics(
        mode: RuntimeMode,
        provider: ProviderId,
        rootSource: ProviderDebugSource? = null
    ): Boolean = applyDiagnostics(mode, provider, rootSource, frameworkSink = null).enabled

    fun applyDiagnostics(
        mode: RuntimeMode,
        provider: ProviderId,
        rootSource: ProviderDebugSource? = null,
        frameworkSink: DiagnosticSink? = null
    ): ProviderDebugResolution {
        val resolution = resolveDetailed(mode, provider, rootSource)
        StructuredDiagnostics.configureForRuntime(mode, resolution.enabled, frameworkSink)
        if (mode == RuntimeMode.ROOT_MODULE) {
            val revision = runCatching { (rootSource as? ProviderDebugRevisionSource)?.observedRevision() ?: -1L }.getOrDefault(-1L)
            val observed = resolution.reason == "enabled" || resolution.reason == "disabled"
            // Players call this before installing business hooks; an optional runtime
            // diagnostic write must never abort that installer or change the resolution.
            try {
                runtimeConfigRecorder(
                    resolution.enabled, if (observed) revision else -1L,
                    if (!observed) "UNAVAILABLE" else if (revision < 0L) "CONSUMED_NO_REVISION" else "CONSUMED")
            } catch (_: RuntimeException) {
            } catch (_: LinkageError) {
            }
        }
        return resolution
    }

    // Declared before runtimeConfigRecorder: object properties initialize in source order.
    internal val DEFAULT_RUNTIME_CONFIG_RECORDER: (Boolean, Long, String) -> Unit = { enabled, revision, observation ->
        io.github.andrealtb.coloroslyrics.provider.runtimediagnostics.ProviderRuntimeDiagnostics.configConsumed(
            enabled, revision, observation)
    }

    /** Test seam only; production always records into ProviderRuntimeDiagnostics. */
    @Volatile
    internal var runtimeConfigRecorder: (Boolean, Long, String) -> Unit = DEFAULT_RUNTIME_CONFIG_RECORDER
}
