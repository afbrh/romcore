plugins {
    id("com.android.library")
}

// :core-shipwright (N64: The Legend of Zelda: Ocarina of Time only). Ship of Harkinian (a decomp-based native port, NOT an emulator)
// through the community Android fork please-be-nice/Shipwright-android (base SoH 9.2.3), consumed as a composite build of
// ../shipwright-src/Android (a library module; local patches in core-shipwright/patches, never pushed). Native library libsoh.so +
// libSDL2.so (SDL2 Java package relocated to org.libsdl.appsoh, no clash with Eden/Azahar's org.libsdl.app).
android {
    namespace = "com.noryan.romcore.core.shipwright"
    compileSdk = 37
    defaultConfig {
        minSdk = 30
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "VERSION_NAME", "\"Ship of Harkinian 9.2.3 (android fork, romcore patches)\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
    api(project(":romcore-api"))
    api("com.dishii:app")   // substituted by includeBuild("../shipwright-src/Android") in settings.gradle.kts
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
