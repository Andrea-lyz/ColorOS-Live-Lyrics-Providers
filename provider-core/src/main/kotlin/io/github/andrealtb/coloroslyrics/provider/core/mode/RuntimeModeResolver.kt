/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.core.mode

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics

/**
 * Resolves the provider runtime mode (ROOT_MODULE vs NPATCH_EMBEDDED vs UNKNOWN)
 * strictly once per process. Fails closed on UNKNOWN mode.
 */
object RuntimeModeResolver {

    private const val META_NPATCH_MARKER = "io.github.andrealtb.coloroslyrics.NPATCH_EMBEDDED"
    private const val RES_NPATCH_MARKER = "npatch_marker"

    @Volatile
    private var cachedResolution: RuntimeModeResolution? = null

    @Volatile
    private var isXposedActive: Boolean = false

    fun notifyXposedHookActive() {
        isXposedActive = true
    }

    @Synchronized
    fun resolve(context: Context?): RuntimeModeResolution {
        cachedResolution?.let { return it }

        val hostPkg = context?.packageName ?: "unknown.host"
        val procName = getProcessName(context)

        val resolution = evaluateSignals(collectSignals(context, hostPkg, procName))
        cachedResolution = resolution

        if (resolution.mode == RuntimeMode.UNKNOWN) {
            StructuredDiagnostics.logWarning(
                DiagnosticEvent(
                    component = "provider/core",
                    area = "bootstrap",
                    event = "RUNTIME_MODE_UNKNOWN",
                    mode = resolution.mode,
                    process = procName,
                    reason = resolution.markerSource,
                    message = "Provider transport is disabled."
                )
            )
        } else {
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = "provider/core",
                    area = "bootstrap",
                    event = "RUNTIME_MODE_RESOLVED",
                    mode = resolution.mode,
                    process = procName,
                    reason = resolution.markerSource
                )
            )
        }

        return resolution
    }

    fun currentResolution(): RuntimeModeResolution? = cachedResolution

    private fun collectSignals(context: Context?, hostPkg: String, procName: String): RuntimeModeSignals {
        var manifestMarker = false
        var resourceMarker: String? = null
        if (context != null) {
            try {
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getApplicationInfo(
                        hostPkg,
                        PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getApplicationInfo(hostPkg, PackageManager.GET_META_DATA)
                }
                if (appInfo.metaData?.getBoolean(META_NPATCH_MARKER, false) == true) {
                    manifestMarker = true
                }
            } catch (_: Exception) {}

            try {
                val resId = context.resources.getIdentifier(RES_NPATCH_MARKER, "string", hostPkg)
                if (resId != 0) {
                    val value = context.resources.getString(resId)
                    if (value.isNotBlank()) {
                        resourceMarker = value
                    }
                }
            } catch (_: Exception) {}
        }

        return RuntimeModeSignals(
            hostPackage = hostPkg,
            processName = procName,
            xposedActive = isXposedActive,
            manifestNpatchMarker = manifestMarker,
            resourceNpatchMarker = resourceMarker
        )
    }

    internal fun evaluateSignals(signals: RuntimeModeSignals): RuntimeModeResolution {
        val hasNpatchMarker = signals.manifestNpatchMarker || !signals.resourceNpatchMarker.isNullOrBlank()
        if (signals.xposedActive && hasNpatchMarker) {
            return RuntimeModeResolution(
                mode = RuntimeMode.UNKNOWN,
                hostPackage = signals.hostPackage,
                processName = signals.processName,
                markerSource = "conflict:xposed+npatch"
            )
        }

        if (hasNpatchMarker) {
            val source = if (signals.manifestNpatchMarker) {
                "manifest:meta-data:$META_NPATCH_MARKER"
            } else {
                "resource:string:$RES_NPATCH_MARKER=${signals.resourceNpatchMarker}"
            }
            return RuntimeModeResolution(
                mode = RuntimeMode.NPATCH_EMBEDDED,
                hostPackage = signals.hostPackage,
                processName = signals.processName,
                markerSource = source
            )
        }

        if (signals.xposedActive) {
            return RuntimeModeResolution(
                mode = RuntimeMode.ROOT_MODULE,
                hostPackage = signals.hostPackage,
                processName = signals.processName,
                markerSource = "xposed:hook-active"
            )
        }

        return RuntimeModeResolution(
            mode = RuntimeMode.UNKNOWN,
            hostPackage = signals.hostPackage,
            processName = signals.processName,
            markerSource = "none"
        )
    }

    private fun getProcessName(context: Context?): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val appProcessName = android.app.Application.getProcessName()
            if (!appProcessName.isNullOrBlank()) return appProcessName
        }
        return context?.packageName ?: "unknown.process"
    }

    internal fun resetForTesting() {
        cachedResolution = null
        isXposedActive = false
    }
}

internal data class RuntimeModeSignals(
    val hostPackage: String,
    val processName: String,
    val xposedActive: Boolean = false,
    val manifestNpatchMarker: Boolean = false,
    val resourceNpatchMarker: String? = null
)
