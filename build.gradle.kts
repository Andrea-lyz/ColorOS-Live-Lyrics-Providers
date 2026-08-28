/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.jetbrains.kotlin.jvm) apply false
}

extra["compileSdkVersion"] = 37
extra["targetSdkVersion"] = 37

val v5ProviderModules = listOf(
    ":player-salt",
    ":player-cone",
    ":kuwo-music",
    ":player-lx",
    ":player-poweramp",
    ":player-metrolist",
    ":player-kugou",
    ":player-qq",
    ":player-netease",
    ":player-apple",
    ":player-spotify",
    ":player-qishui"
)

tasks.register("assembleV5MatrixDebug") {
    group = "build"
    description = "Build every device-validated v5 Provider debug APK."
    dependsOn(v5ProviderModules.map { "$it:assembleDebug" })
}

tasks.register("assembleV5MatrixRelease") {
    group = "build"
    description = "Build every device-validated v5 Provider release APK."
    dependsOn(v5ProviderModules.map { "$it:assembleRelease" })
}
