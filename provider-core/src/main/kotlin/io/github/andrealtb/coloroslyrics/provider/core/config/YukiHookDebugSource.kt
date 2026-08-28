/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.core.config

import android.content.Context
import com.highcapable.yukihookapi.hook.factory.prefs

object YukiHookDebugSource {
    fun create(context: Context): ProviderDebugSource = ProviderDebugSource { provider ->
        runCatching {
            context.prefs(ProviderDebugConfig.prefsName(provider))
                .getBoolean(ProviderDebugConfig.KEY_DEBUG_ENABLED, false)
        }.getOrNull()
    }
}
