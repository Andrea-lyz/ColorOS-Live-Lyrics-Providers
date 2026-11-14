/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

import com.android.build.api.dsl.LibraryExtension

plugins {
    alias(libs.plugins.android.library)
}

// Read-only "is this module enabled in the LSPosed manager" endpoint for the Bridge store
// (5.0 plan §14.9). Module App process only; never part of the hooked player path.
configure<LibraryExtension> {
    namespace = "io.github.andrealtb.coloroslyrics.provider.modulestatus"
    compileSdk {
        version = release(rootProject.extra.get("compileSdkVersion") as Int)
    }

    defaultConfig {
        minSdk = 27
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.libxposed.service)

    testImplementation(libs.junit)
}
