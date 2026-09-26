plugins {
    alias(libs.plugins.android.application)
}

// Formal API 102 Provider convention: dependencies, R8 rules, and resource verification.
apply(from = rootProject.file("gradle/provider-app-convention.gradle.kts"))

android {
    namespace = "io.github.andrealtb.coloroslyrics.provider.readify"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int
    defaultConfig {
        applicationId = "io.github.andrealtb.coloroslyrics.provider.readify"
        minSdk = 27
        targetSdk = rootProject.extra["targetSdkVersion"] as Int
        versionCode = 2
        versionName = "1.0.1"
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
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.libxposed.modern.api)
}
