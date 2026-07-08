/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("unused")

package io.github.proify.lyricon.kgprovider.xposed.kugou

open class KuGou : KuGouBase() {
    override fun shouldHookProcess(): Boolean {
        return processName.endsWith(":support") || processName.endsWith(".support")
    }

    override fun shouldStabilizeNoisyMetadataIdentity(): Boolean {
        return processName == "com.kugou.android.support" ||
            processName.startsWith("com.kugou.android:")
    }

    override fun shouldPublishMediaSessionLyricInfo(): Boolean = false

    override fun shouldUseCarLyricFallback(): Boolean = false

    override fun useOriginalApkLyricPipeline(): Boolean = true

    override fun shouldThrottleBridgePlaybackState(): Boolean = true

    override fun onAppCreate() = Unit
}
