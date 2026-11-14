plugins { alias(libs.plugins.android.library) }
android {
    namespace = "io.github.andrealtb.coloroslyrics.provider.runtimediagnostics"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int
    defaultConfig { minSdk = 27 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.16.1")
}
