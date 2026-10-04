/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        google()
        gradlePluginPortal()
        maven { url = uri("https://api.xposed.info/") }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        google()
        maven { url = uri("https://api.xposed.info/") }
    }
}

rootProject.name = "ColorOS-Live-Lyrics-Providers"

// 4.0 Infrastructure & Parser Modules
include(":provider-core")
// v4.1 libxposed API 102 shared runtime: only enters the hook path of target players.
include(":provider-hook-api102")
// v4.1 libxposed service shared layer: Provider module App and Debug config write side.
include(":provider-settings-api102")
include(":reflection-core")
include(":parser-lrc")
include(":parser-qrc")
include(":parser-yrc")
include(":parser-krc")
include(":parser-ttml")
include(":player-salt")
include(":player-cone")
include(":player-lx")
include(":player-poweramp")
include(":player-metrolist")
include(":player-kugou")
include(":player-qq")
include(":player-netease")
include(":player-apple")
include(":player-spotify")
include(":player-qishui")

// Compatibility helpers still used by the v5 KuWo and NetEase modules.
include(":share:extensions-kt")
include(":share:extensions-android")
include(":share:lrckit")
include(":share:yrckit")
include(":kuwo-music")

// Universal Player is part of the v5 release matrix.
include(":universal-provider")

// Experimental API 102 Readify adapter; not part of the release matrix yet.
include(":player-readify")

// Dynamic artwork source (artwork protocol v1). Independent optional app, not a lyric Provider:
// it is built and released on its own tasks below, never as part of the v5 lyric matrix.
// artwork-contract mirrors the canonical module in the Bridge repository.
include(":artwork-contract")
include(":artwork-provider-am")
