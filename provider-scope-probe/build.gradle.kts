/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

import groovy.json.JsonSlurper
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
}

// This isolated module deliberately does not apply the production Provider convention.
// Its only host list comes from the existing Salt matrix entry, not from an Intent or network.
val matrixFile = rootProject.layout.projectDirectory.file("release/v5-provider-matrix.json")
val matrix = JsonSlurper().parseText(providers.fileContents(matrixFile).asText.get()) as Map<*, *>
val sample = (matrix["providers"] as List<*>).filterIsInstance<Map<*, *>>()
    .single { it["module"] == "player-salt" }
val allowedHosts = (sample["scopes"] as List<*>).map { it as String }.sorted()
check(allowedHosts.isNotEmpty() && allowedHosts.all {
    it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) &&
        it !in setOf("android", "com.android.systemui")
}) { "Unsafe probe host list in Provider matrix" }
val staticScopeValue = providers.gradleProperty("scopeProbeStaticScope").orNull
    ?: matrix["xposedStaticScope"].toString()
check(staticScopeValue in setOf("true", "false")) { "scopeProbeStaticScope must be true or false" }
val hostsCsv = allowedHosts.joinToString(",")
val scopeContents = allowedHosts.joinToString("\n", postfix = "\n")
val moduleContents = """
    minApiVersion=102
    targetApiVersion=102
    staticScope=$staticScopeValue
    exceptionMode=protective
    autoHotReload=false
""".trimIndent() + "\n"
abstract class GenerateScopeProbeResources : DefaultTask() {
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:Input abstract val scopes: Property<String>
    @get:Input abstract val metadata: Property<String>

    @TaskAction fun generate() {
        val xposed = outputDirectory.get().dir("META-INF/xposed").asFile
        xposed.mkdirs()
        xposed.resolve("module.prop").writeText(metadata.get())
        xposed.resolve("scope.list").writeText(scopes.get())
        xposed.resolve("java_init.list").writeText(
            "io.github.andrealtb.coloroslyrics.scopeprobe.ScopeProbeModuleEntry\n"
        )
    }
}
val generateScopeProbeResources by tasks.registering(GenerateScopeProbeResources::class) {
    outputDirectory.set(layout.buildDirectory.dir("generated/scopeProbeResources"))
    scopes.set(scopeContents)
    metadata.set(moduleContents)
}

android {
    namespace = "io.github.andrealtb.coloroslyrics.scopeprobe"
    compileSdk { version = release(rootProject.extra["compileSdkVersion"] as Int) }
    defaultConfig {
        applicationId = "io.github.andrealtb.coloroslyrics.scopeprobe"
        minSdk = 27
        targetSdk = rootProject.extra["targetSdkVersion"] as Int
        versionCode = 1
        versionName = "0.1-m0"
        buildConfigField("String", "ALLOWED_HOSTS", "\"$hostsCsv\"")
        buildConfigField("boolean", "STATIC_SCOPE", staticScopeValue)
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        debug { versionNameSuffix = if (staticScopeValue == "true") "-static" else "-dynamic" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// No Release APK is produced, even when someone explicitly asks for one.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
    onVariants(selector().all()) { variant ->
        variant.sources.resources?.addGeneratedSourceDirectory(generateScopeProbeResources) { it.outputDirectory }
    }
}

dependencies {
    implementation(project(":provider-settings-api102"))
    implementation(libs.libxposed.service)
    implementation(libs.androidx.core.ktx)
    compileOnly(libs.libxposed.api)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}
