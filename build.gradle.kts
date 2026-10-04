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
    ":player-qishui",
    ":universal-provider",
    ":player-readify"
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

// Dynamic artwork source: an optional, independently installed app. It is deliberately outside
// the v5 lyric Provider matrix, so the matrix counts, bundle, and release jobs are unchanged.
val artworkProviderModules = listOf(
    ":artwork-contract",
    ":artwork-provider-am"
)
val artworkReleaseTaskRequested = gradle.startParameter.taskNames.any { requestedTask ->
    requestedTask.substringAfterLast(':').lowercase() in setOf(
        "assembleartworkproviderrelease"
    )
}
if (releaseArtifactTaskRequested || artworkReleaseTaskRequested) {
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


tasks.register("verifyArtworkContract") {
    group = "verification"
    description = "Check that the artwork contract mirror matches the Bridge module when available."
    val bridgeRoot = providers.environmentVariable("BRIDGE_REPO_ROOT").orNull
    val scriptPath = layout.projectDirectory.file("scripts/verify-artwork-contract.ps1").asFile
    val repoRoot = layout.projectDirectory.asFile
    val required = bridgeRoot != null
    doLast {
        val arguments = mutableListOf("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", scriptPath.absolutePath, "-RepoRoot", repoRoot.absolutePath)
        if (bridgeRoot != null) {
            arguments += listOf("-BridgeRepoRoot", bridgeRoot, "-Required")
        }
        val process = ProcessBuilder(listOf("pwsh") + arguments).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exit = process.waitFor()
        output.lines().forEach { line -> logger.lifecycle(line) }
        if (exit != 0) throw GradleException("Artwork contract mirror verification failed with exit=$exit: $output")
        logger.lifecycle("[CLL] component=build area=verification event=ARTWORK_CONTRACT_MIRROR_OK required=$required")
    }
}

tasks.register("testArtworkProvider") {
    group = "verification"
    description = "Run the artwork contract and dynamic artwork source unit tests."
    dependsOn(artworkProviderModules.map { "$it:testDebugUnitTest" })
}

tasks.register("assembleArtworkProviderDebug") {
    group = "build"
    description = "Build the dynamic artwork source debug APK."
    dependsOn(":artwork-provider-am:assembleDebug")
}

tasks.register("assembleArtworkProviderRelease") {
    group = "build"
    description = "Build the dynamic artwork source release APK."
    dependsOn(":artwork-provider-am:assembleRelease")
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
