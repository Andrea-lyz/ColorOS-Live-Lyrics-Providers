/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.HostTestBuilder

plugins {
    alias(libs.plugins.android.application)
}

// Dynamic artwork source (artwork protocol v1 service). It is a plain Android app, not an
// LSPosed module, so the API 102 app convention intentionally does not apply here.

configure<ApplicationExtension> {
    namespace = "io.github.andrealtb.artwork.am"
    compileSdk {
        version = release(rootProject.extra.get("compileSdkVersion") as Int)
    }

    defaultConfig {
        applicationId = "io.github.andrealtb.artwork.am"
        minSdk = 30
        targetSdk = rootProject.extra.get("targetSdkVersion") as Int
        // versionCode stays monotonic over the 0.2.x integration builds.
        versionCode = 7
        versionName = "1.0.3"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("RELEASE_STORE_FILE") ?: "release.jks")
            storePassword = System.getenv("RELEASE_STORE_PASSWORD")
            keyAlias = System.getenv("RELEASE_KEY_ALIAS")
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        create("diagnostic") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".diagnostic"
            versionNameSuffix = "-diagnostic4"
            isDebuggable = false
            matchingFallbacks += listOf("debug")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            // Kept off: the provider re-packages MP4 through MediaExtractor/MediaMuxer and reads
            // org.json by name; shrinking would need rules we have not device-verified.
            isMinifyEnabled = false
        }
    }

    sourceSets {
        getByName("debug").java.directories.add("src/standard/java")
        getByName("release").java.directories.add("src/standard/java")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
}

configure<ApplicationAndroidComponentsExtension> {
    beforeVariants(selector().withBuildType("diagnostic")) { variant ->
        variant.hostTests.getValue(HostTestBuilder.UNIT_TEST_TYPE).enable = true
    }
}

dependencies {
    implementation(project(":artwork-contract"))
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
