/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.core.config

import org.junit.Test
import kotlin.test.assertFalse

class ProviderDebugConfigTest {

    private val allProviders = listOf(
        "netease",
        "qq",
        "qqhd",
        "kugou",
        "kuwo",
        "spotify",
        "lx",
        "poweramp",
        "salt",
        "qishui",
        "musicfree",
        "gramophone",
        "symfonium",
        "metrolist",
        "apple"
    )

    @Test
    fun defaultDebugSettingIsFalseForAllProviders() {
        for (provider in allProviders) {
            val isEnabled = ProviderDebugConfig.isDebugEnabled(null, provider)
            assertFalse(isEnabled, "Debug setting for $provider must default to false.")
        }
    }

    @Test
    fun nullContextFailsSafeWithoutThrowing() {
        assertFalse(ProviderDebugConfig.isDebugEnabled(null, "any_player"))
        assertFalse(ProviderDebugConfig.setDebugEnabled(null, "any_player", true))
    }
}
