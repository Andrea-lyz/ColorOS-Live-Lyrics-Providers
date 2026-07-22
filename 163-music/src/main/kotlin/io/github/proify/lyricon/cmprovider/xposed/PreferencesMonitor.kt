/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.cmprovider.xposed

import android.content.SharedPreferences
import io.github.proify.extensions.android.ProviderDiagnostics
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.system.measureTimeMillis

class PreferencesMonitor(
    kitBridge: DexKitBridge?,
    callback: PreferenceCallback
) {
    private companion object {
        const val TAG = "CloudMusicProvider"

        /** Known preference accessors for the two supplied APK families. */
        val KNOWN_ACCESSORS = listOf(
            "com.netease.cloudmusic.utils.o2" to "a", // NetEase 9.0.40
            "com.netease.cloudmusic.utils.g3" to "a", // Honor 3.5.20
            "com.netease.cloudmusic.utils.c1" to "do" // historical modified build
        )
    }

    private var preferences: SharedPreferences? = null
    private val getPreferenceMethodData: MethodData?
    private var getPreferenceMethod: Method? = null

    init {
        val time = measureTimeMillis {
            getPreferenceMethodData = kitBridge?.let { bridge ->
                runCatching {
                    bridge.findClass {
                        searchPackages("com.netease.cloudmusic.utils")
                        matcher {
                            usingStrings(
                                "com.netease.cloudmusic.preferences",
                                "multiprocess_settings"
                            )
                        }
                    }.findMethod {
                        matcher {
                            returnType(SharedPreferences::class.java)
                            paramCount = 0
                            modifiers(Modifier.PUBLIC or Modifier.STATIC)
                            usingStrings("com.netease.cloudmusic.preferences")
                        }
                    }.singleOrNull()
                }.onFailure { error ->
                    ProviderDiagnostics.debug(TAG) {
                        "DexKit preference discovery failed: ${error.javaClass.simpleName}"
                    }
                }.getOrNull()
            }
        }
        ProviderDiagnostics.debug(TAG) {
            val mode = if (kitBridge == null) "known accessor" else "DexKit with fallback"
            "PreferencesMonitor initialization completed in ${time}ms ($mode)"
        }
    }

    private val sharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == "showLyricSetting") {
                callback.onTranslationOptionChanged(getTranslationType(sharedPreferences))
            }
        }

    fun update(classLoader: ClassLoader) {
        getPreferenceMethod = runCatching {
            getPreferenceMethodData?.getMethodInstance(classLoader)
        }.getOrNull() ?: findKnownAccessor(classLoader)
        preferences?.unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener)
        preferences = null
        ProviderDiagnostics.debug(TAG) {
            if (getPreferenceMethod != null) {
                "Preference accessor ready: ${getPreferenceMethod?.declaringClass?.name}." +
                    getPreferenceMethod?.name
            } else {
                "Preference accessor unavailable"
            }
        }
    }

    private fun findKnownAccessor(classLoader: ClassLoader): Method? {
        for ((className, methodName) in KNOWN_ACCESSORS) {
            val method = runCatching {
                Class.forName(className, false, classLoader)
                    .declaredMethods
                    .firstOrNull { candidate ->
                        candidate.name == methodName &&
                            candidate.parameterCount == 0 &&
                            Modifier.isPublic(candidate.modifiers) &&
                            Modifier.isStatic(candidate.modifiers) &&
                            SharedPreferences::class.java.isAssignableFrom(candidate.returnType)
                    }
                    ?.apply { isAccessible = true }
            }.getOrNull()
            if (method != null) return method
        }
        return null
    }

    private fun lazyGetSharedPreferences(): SharedPreferences? {
        if (preferences != null) return preferences
        preferences = runCatching {
            getPreferenceMethod?.invoke(null) as? SharedPreferences
        }.onFailure { error ->
            ProviderDiagnostics.debug(TAG) {
                "Preference accessor invocation failed: ${error.javaClass.simpleName}"
            }
        }.getOrNull()
        preferences?.registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener)
        return preferences
    }

    fun getTranslationType(preference: SharedPreferences? = this.lazyGetSharedPreferences()): Int =
        runCatching { preference?.getInt("showLyricSetting", -1) ?: -1 }.getOrDefault(-1)

    interface PreferenceCallback {
        fun onTranslationOptionChanged(type: Int)
    }
}
