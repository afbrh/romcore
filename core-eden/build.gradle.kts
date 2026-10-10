plugins {
    id("com.android.library")
}

android {
    namespace = "com.noryan.romcore.core.eden"
    compileSdk = 37
    defaultConfig {
        minSdk = 30
        // Eden's library declares an "edenVersion" flavor dimension (mainline/genshinSpoof/legacy/chromeOS).
        // "legacy" is the arm64 build with the widest device support (what RomRunner shipped and the one with an
        // existing native build cache in eden-src).
        missingDimensionStrategy("edenVersion", "legacy")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { jniLibs.useLegacyPackaging = true } // Eden needs extracted libs (libadrenotools driver loading)
}

dependencies {
    implementation(project(":romcore-api"))
    // Eden's android project (../eden-src/src/android, :app converted to a library) via includeBuild in settings.
    // Native lib: libyuzu-android.so (unique among RomCore cores); Java package org.yuzu.yuzu_emu (no relocation needed).
    implementation("dev.eden_emu:app:embedded")
    // Eden is compiled against Material 1.12.0; newer versions drop R.attr.colorPrimary -> NoSuchFieldError in
    // EmulationFragment (found by RomRunner). Strictly pinned so it propagates to the app.
    implementation("com.google.android.material:material") { version { strictly("1.12.0") } }
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
