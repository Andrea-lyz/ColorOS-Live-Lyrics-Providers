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

val releaseSigningEnvironment = listOf(
    "RELEASE_STORE_FILE",
    "RELEASE_STORE_PASSWORD",
    "RELEASE_KEY_ALIAS",
    "RELEASE_KEY_PASSWORD"
)
val releaseArtifactTaskRequested = gradle.startParameter.taskNames.any { requestedTask ->
    requestedTask.substringAfterLast(':').lowercase() in setOf(
        "assemblev5matrixrelease",
        "assemblerelease",
        "bundlerelease",
        "packagerelease",
        "installrelease",
        "build"
    )
}
if (releaseArtifactTaskRequested) {
    val missingSigningEnvironment = releaseSigningEnvironment.filter { name ->
        System.getenv(name).isNullOrBlank()
    }
    check(missingSigningEnvironment.isEmpty()) {
        "Provider release signing is required; missing: ${missingSigningEnvironment.joinToString()}."
    }
}

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

tasks.register("testV5Matrix") {
    group = "verification"
    description = "Run core, parser, compatibility-kit, and v5 Provider unit tests."
    dependsOn(v5ProviderModules.map { "$it:testDebugUnitTest" })
    dependsOn(
        ":provider-core:testDebugUnitTest",
        ":provider-hook-api102:testDebugUnitTest",
        ":provider-settings-api102:testDebugUnitTest",
        ":reflection-core:testDebugUnitTest",
        ":share:extensions-android:testDebugUnitTest",
        ":share:extensions-kt:test",
        ":share:lrckit:test",
        ":share:yrckit:test",
        ":parser-lrc:test",
        ":parser-qrc:test",
        ":parser-yrc:test",
        ":parser-krc:test",
        ":parser-ttml:test"
    )
}
