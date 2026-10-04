/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

import com.android.build.api.dsl.LibraryExtension

plugins {
    alias(libs.plugins.android.library)
}

// Mirror of the canonical artwork protocol v1 contract, which lives in the Bridge repository
// (artwork-contract/). Everything under src/ must stay byte-identical to that module;
// scripts/verify-artwork-contract.ps1 enforces it whenever a Bridge checkout is available.

configure<LibraryExtension> {
    namespace = "io.github.andrealtb.artwork.contract"
    compileSdk {
        version = release(rootProject.extra.get("compileSdkVersion") as Int)
    }
    defaultConfig { minSdk = 27 }
    buildFeatures { aidl = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
}

dependencies { testImplementation(libs.junit) }
