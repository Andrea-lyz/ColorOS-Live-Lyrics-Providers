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
