/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.core.config

import android.content.Context
import android.content.SharedPreferences
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

data class ProviderDebugResolution(
    val enabled: Boolean,
    val reason: String
)

data class OpenedModulePrefs(
    val prefs: SharedPreferences,
    val usingLsposedSharedPrefs: Boolean
)

object ProviderDebugConfig {

    const val PREFS_NAME_SUFFIX = "_provider_debug_prefs"
    const val KEY_DEBUG_ENABLED = "provider_debug_logging_enabled"

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
    ): Boolean = applyDiagnostics(mode, provider, rootSource).enabled

    fun applyDiagnostics(
        mode: RuntimeMode,
        provider: ProviderId,
        rootSource: ProviderDebugSource? = null
    ): ProviderDebugResolution {
        val resolution = resolveDetailed(mode, provider, rootSource)
        StructuredDiagnostics.configureForRuntime(mode, resolution.enabled)
        return resolution
    }

    /**
     * v4.1 variant used by libxposed API 102 entries: the framework sink is injected by the
     * modern runtime instead of constructing the legacy Xposed bridge sink here.
     */
    fun applyDiagnostics(
        mode: RuntimeMode,
        provider: ProviderId,
        rootSource: ProviderDebugSource?,
        frameworkSink: DiagnosticSink?
    ): ProviderDebugResolution {
        val resolution = resolveDetailed(mode, provider, rootSource)
        StructuredDiagnostics.configureForRuntime(mode, resolution.enabled, frameworkSink)
        return resolution
    }

    fun openModulePrefs(moduleContext: Context, provider: ProviderId): OpenedModulePrefs {
        val name = prefsName(provider)
        return try {
            @Suppress("DEPRECATION")
            OpenedModulePrefs(
                prefs = moduleContext.getSharedPreferences(name, Context.MODE_WORLD_READABLE),
                usingLsposedSharedPrefs = true
            )
        } catch (_: SecurityException) {
            OpenedModulePrefs(
                prefs = moduleContext.getSharedPreferences(name, Context.MODE_PRIVATE),
                usingLsposedSharedPrefs = false
            )
        }
    }

    fun sharedPreferencesSource(moduleContext: Context): ProviderDebugSource =
        ProviderDebugSource { provider ->
            openModulePrefs(moduleContext, provider).prefs.getBoolean(KEY_DEBUG_ENABLED, false)
        }

    fun setRootDebugEnabled(moduleContext: Context?, provider: ProviderId, enabled: Boolean): Boolean {
        if (moduleContext == null) return false
        return runCatching {
            openModulePrefs(moduleContext, provider)
                .prefs
                .edit()
                .putBoolean(KEY_DEBUG_ENABLED, enabled)
                .commit()
        }.getOrDefault(false)
    }

    fun readXposedSwitch(modulePackage: String, provider: ProviderId): Boolean = runCatching {
        val type = Class.forName("de.robv.android.xposed.XSharedPreferences")
        val prefs = type.getConstructor(String::class.java, String::class.java)
            .newInstance(modulePackage, prefsName(provider))
        runCatching { type.getMethod("makeWorldReadable").invoke(prefs) }
        type.getMethod("reload").invoke(prefs)
        type.getMethod(
            "getBoolean",
            String::class.java,
            Boolean::class.javaPrimitiveType
        ).invoke(prefs, KEY_DEBUG_ENABLED, false) as Boolean
    }.getOrDefault(false)
}
