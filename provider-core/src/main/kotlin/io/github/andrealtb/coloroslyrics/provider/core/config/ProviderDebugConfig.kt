/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.core.config

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import io.github.andrealtb.coloroslyrics.provider.core.mode.RuntimeMode

enum class ProviderId(val configKey: String) {
    NETEASE("netease"),
    QQ("qq"),
    QQHD("qqhd"),
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

object ProviderDebugConfig {

    const val PREFS_NAME_SUFFIX = "_provider_debug_prefs"
    const val KEY_DEBUG_ENABLED = "provider_debug_logging_enabled"
    const val META_DEBUG_ENABLED = "io.github.andrealtb.coloroslyrics.PROVIDER_DEBUG_ENABLED"
    const val RES_DEBUG_ENABLED = "provider_debug_enabled"

    fun resolve(
        mode: RuntimeMode,
        provider: ProviderId,
        rootSource: ProviderDebugSource? = null,
        embeddedSource: ProviderDebugSource? = null
    ): Boolean = runCatching {
        when (mode) {
            RuntimeMode.ROOT_MODULE -> rootSource?.read(provider)
            RuntimeMode.NPATCH_EMBEDDED -> embeddedSource?.read(provider)
            RuntimeMode.UNKNOWN -> false
        } ?: false
    }.getOrDefault(false)

    fun configureDiagnostics(
        mode: RuntimeMode,
        provider: ProviderId,
        rootSource: ProviderDebugSource? = null,
        embeddedSource: ProviderDebugSource? = null
    ): Boolean {
        val enabled = resolve(mode, provider, rootSource, embeddedSource)
        StructuredDiagnostics.configureForRuntime(mode, enabled)
        return enabled
    }

    fun sharedPreferencesSource(moduleContext: Context): ProviderDebugSource = ProviderDebugSource { provider ->
        moduleContext.getSharedPreferences(
            "${provider.configKey}$PREFS_NAME_SUFFIX",
            Context.MODE_PRIVATE
        ).getBoolean(KEY_DEBUG_ENABLED, false)
    }

    fun setRootDebugEnabled(moduleContext: Context?, provider: ProviderId, enabled: Boolean): Boolean {
        if (moduleContext == null) return false
        return runCatching {
            moduleContext.getSharedPreferences(
                "${provider.configKey}$PREFS_NAME_SUFFIX",
                Context.MODE_PRIVATE
            ).edit().putBoolean(KEY_DEBUG_ENABLED, enabled).commit()
        }.getOrDefault(false)
    }

    fun embeddedMarkerSource(hostContext: Context): ProviderDebugSource = ProviderDebugSource {
        resolveEmbeddedMarker(
            manifestValue = readManifestMarker(hostContext),
            resourceValue = readResourceMarker(hostContext)
        )
    }

    internal fun resolveEmbeddedMarker(manifestValue: Boolean?, resourceValue: Boolean?): Boolean =
        manifestValue == true || resourceValue == true

    private fun readManifestMarker(context: Context): Boolean? = runCatching {
        val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        }
        if (appInfo.metaData?.containsKey(META_DEBUG_ENABLED) == true) {
            appInfo.metaData.getBoolean(META_DEBUG_ENABLED, false)
        } else {
            null
        }
    }.getOrNull()

    private fun readResourceMarker(context: Context): Boolean? = runCatching {
        val resourceId = context.resources.getIdentifier(RES_DEBUG_ENABLED, "bool", context.packageName)
        if (resourceId == 0) null else context.resources.getBoolean(resourceId)
    }.getOrNull()
}
