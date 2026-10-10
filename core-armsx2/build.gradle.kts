plugins {
    id("com.android.library")
}

android {
    namespace = "com.noryan.romcore.core.armsx2"
    compileSdk = 37
    defaultConfig {
        minSdk = 30
        // ARMSX2's library declares a "store" flavor dimension (github/play). "github" is the sideload variant that has the
        // native build cache in armsx2-src (and MANAGE_EXTERNAL_STORAGE, which RomCore also requests).
        missingDimensionStrategy("store", "github")
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    // adrenotools (custom Vulkan driver loading) needs the hook .so files extracted to nativeLibraryDir.
    packaging { jniLibs.useLegacyPackaging = true }
}

dependencies {
    implementation(project(":romcore-api"))
    // ARMSX2's android project (../armsx2-src/platforms/android, :app converted to a library) via includeBuild in settings.
    // Native libs: libemucore_4k.so / libemucore_16k.so (unique among RomCore cores) + libshaderc_shared, libSPIRV-Tools-shared
    // (also shipped by azahar: identical sonames in different processes is fine, the packager picks one -- see report).
    // Java packages: com.armsx2, kr.co.iefriends.pcsx2, org.libsdl.app_armsx2 (SDL already relocated upstream-side).
    implementation("com.armsx2:app:embedded")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.compose.runtime:runtime:1.7.0")
}
