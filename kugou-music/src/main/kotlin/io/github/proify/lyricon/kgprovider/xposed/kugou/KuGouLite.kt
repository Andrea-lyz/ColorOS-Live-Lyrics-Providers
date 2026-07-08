/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.kgprovider.xposed.kugou

class KuGouLite : KuGou() {
    override fun shouldStabilizeNoisyMetadataIdentity(): Boolean = false
    override fun shouldPublishMediaSessionLyricInfo(): Boolean = true
    override fun shouldUseCarLyricFallback(): Boolean = true
    override fun useOriginalApkLyricPipeline(): Boolean = false
    override fun shouldThrottleBridgePlaybackState(): Boolean = false
    override fun onAppCreate() = Unit
}
