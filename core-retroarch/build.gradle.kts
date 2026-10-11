plugins {
    id("com.android.library")
}

android {
    namespace = "com.noryan.romcore.core.retroarch"
    compileSdk = 37
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        minSdk = 30
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
    api(project(":romcore-api"))
    // Local build of libretro/RetroArch (retroarch-src, patched with the romcore bridge), composite-included in settings.
    api("com.retroarch:phoenix:embedded")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.runtime:runtime")
}
