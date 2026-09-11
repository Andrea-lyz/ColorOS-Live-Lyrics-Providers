plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.andrealtb.coloroslyrics.provider.readify"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int
    defaultConfig {
        applicationId = "io.github.andrealtb.coloroslyrics.provider.readify"
        minSdk = 28
        targetSdk = rootProject.extra["targetSdkVersion"] as Int
        versionCode = 1
        versionName = "1.0.0-dev"
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
    compileOnly(libs.libxposed.modern.api)
    testImplementation(libs.junit)
    testImplementation(libs.libxposed.modern.api)
}
