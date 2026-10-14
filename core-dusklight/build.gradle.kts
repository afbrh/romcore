plugins {
    id("com.android.library")
}

// :core-dusklight (GameCube: The Legend of Zelda: Twilight Princess only). Dusklight (CC0-1.0) is consumed as a composite
// build of ../dusklight-src/platforms/android (a library module; local patches in core-dusklight/patches, never pushed).
// Native library: libdusklight_main.so (unique name; ships in the normal jniLibs dir). SDL3 Java package dev.twilitrealm.dusksdl.
android {
    namespace = "com.noryan.romcore.core.dusklight"
    compileSdk = 37
    defaultConfig {
        minSdk = 30
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "VERSION_NAME", "\"Dusklight b245c6b (romcore patches)\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
    api(project(":romcore-api"))
    api("dev.twilitrealm:app")   // substituted by includeBuild("../dusklight-src/platforms/android") in settings.gradle.kts
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
