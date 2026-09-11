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
