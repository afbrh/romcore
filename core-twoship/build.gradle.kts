plugins {
    id("com.android.library")
}

// :core-twoship (N64: The Legend of Zelda: Majora's Mask only). 2 Ship 2 Harkinian (a decomp-based native port, NOT an emulator)
// through the community Android fork linkzenic/2ship2harkinian-Android (v5.0.1-android.3), consumed as a composite build of
// ../twoship-src/Android (a library module; local patches in core-twoship/patches, never pushed). Native libraries lib2ship.so +
// libSDL2twoship.so (SDL2 Java package relocated to org.libsdl.apptwoship: no clash with Eden/Azahar's org.libsdl.app or Ship of
// Harkinian's org.libsdl.appsoh / libSDL2.so / libsoh.so).
android {
    namespace = "com.noryan.romcore.core.twoship"
    compileSdk = 37
    defaultConfig {
        minSdk = 30
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "VERSION_NAME", "\"2 Ship 2 Harkinian 5.0.1-android.3 (romcore patches)\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
    api(project(":romcore-api"))
    api("com.twoshipfork:app")   // substituted by includeBuild("../twoship-src/Android") in settings.gradle.kts
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
