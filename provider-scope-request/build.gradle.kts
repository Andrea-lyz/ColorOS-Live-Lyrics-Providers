/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

import com.android.build.api.dsl.LibraryExtension

plugins {
    alias(libs.plugins.android.library)
}

// Management protocol v1 (5.0 plan §7.2, §14.13): a dedicated Provider requests ITS OWN LSPosed
// scope for compiled hosts. Module App process only; never part of the hooked player path.
configure<LibraryExtension> {
    namespace = "io.github.andrealtb.coloroslyrics.provider.scoperequest"
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
    // The service holder every Provider App already feeds (shared Application or Readify).
    implementation(project(":provider-module-status"))

    testImplementation(libs.junit)
}
