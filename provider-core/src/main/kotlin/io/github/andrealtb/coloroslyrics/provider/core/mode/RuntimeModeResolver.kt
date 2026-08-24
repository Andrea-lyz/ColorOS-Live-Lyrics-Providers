/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.core.mode

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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

        val resolution = evaluate(context, hostPkg, procName)
        cachedResolution = resolution

        if (resolution.mode == RuntimeMode.UNKNOWN) {
            StructuredDiagnostics.logWarning("runtime-mode-unknown") {
                "RuntimeMode resolved to UNKNOWN for $hostPkg in process $procName (marker=${resolution.markerSource}). " +
                    "Failing closed - provider will not publish lyrics."
            }
        } else {
            StructuredDiagnostics.logInfo("runtime-mode-resolved") {
                "RuntimeMode resolved to ${resolution.mode} for $hostPkg ($procName) via ${resolution.markerSource}."
            }
        }

        return resolution
    }

    fun currentResolution(): RuntimeModeResolution? = cachedResolution

    private fun evaluate(context: Context?, hostPkg: String, procName: String): RuntimeModeResolution {
        // 1. Check NPatch embedded signals
        if (context != null) {
            // A. Check Manifest metadata
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
                    return RuntimeModeResolution(
                        mode = RuntimeMode.NPATCH_EMBEDDED,
                        hostPackage = hostPkg,
                        processName = procName,
                        markerSource = "manifest:meta-data:$META_NPATCH_MARKER"
                    )
                }
            } catch (_: Exception) {}

            // B. Check string resource marker
            try {
                val resId = context.resources.getIdentifier(RES_NPATCH_MARKER, "string", hostPkg)
                if (resId != 0) {
                    val value = context.resources.getString(resId)
                    if (value.isNotBlank()) {
                        return RuntimeModeResolution(
                            mode = RuntimeMode.NPATCH_EMBEDDED,
                            hostPackage = hostPkg,
                            processName = procName,
                            markerSource = "resource:string:$RES_NPATCH_MARKER=$value"
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Check Xposed / LSPosed hook active signal
        if (isXposedActive) {
            return RuntimeModeResolution(
                mode = RuntimeMode.ROOT_MODULE,
                hostPackage = hostPkg,
                processName = procName,
                markerSource = "xposed:hook-active"
            )
        }

        // 3. Fail closed to UNKNOWN
        return RuntimeModeResolution(
            mode = RuntimeMode.UNKNOWN,
            hostPackage = hostPkg,
            processName = procName,
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
