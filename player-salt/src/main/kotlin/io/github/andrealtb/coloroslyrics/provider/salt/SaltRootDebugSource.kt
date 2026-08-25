package io.github.andrealtb.coloroslyrics.provider.salt

import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderDebugConfig
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderDebugSource

internal object SaltRootDebugSource {
    private const val MODULE_PACKAGE = "io.github.andrealtb.coloroslyrics.provider.salt"

    fun create(): ProviderDebugSource = ProviderDebugSource { provider ->
        runCatching {
            val type = Class.forName("de.robv.android.xposed.XSharedPreferences")
            val prefsName = "${provider.configKey}${ProviderDebugConfig.PREFS_NAME_SUFFIX}"
            val prefs = type.getConstructor(String::class.java, String::class.java)
                .newInstance(MODULE_PACKAGE, prefsName)
            runCatching { type.getMethod("makeWorldReadable").invoke(prefs) }
            type.getMethod("reload").invoke(prefs)
            type.getMethod(
                "getBoolean", String::class.java, Boolean::class.javaPrimitiveType
            ).invoke(prefs, ProviderDebugConfig.KEY_DEBUG_ENABLED, false) as? Boolean
        }.getOrNull() ?: false
    }
}
