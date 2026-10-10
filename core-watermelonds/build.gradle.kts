import com.android.build.gradle.internal.attributes.VariantAttr

plugins {
    id("com.android.library")
}

val variantAttr: Attribute<VariantAttr> = VariantAttr.ATTRIBUTE

android {
    namespace = "com.noryan.romcore.core.watermelonds"
    compileSdk = 37
    defaultConfig {
        minSdk = 30
        // WatermelonDS declares two flavor dimensions: version (playStore/gitHub) and build (prod/nightly).
        // gitHub = adrenotools (custom Vulkan driver) enabled; prod = stable channel.
        missingDimensionStrategy("version", "gitHub")
        missingDimensionStrategy("build", "prod")
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { jniLibs.useLegacyPackaging = true }
}

dependencies {
    implementation(project(":romcore-api"))
    // WatermelonDS (../watermelonds-src, :app is a library) via includeBuild in settings.gradle.kts.
    // Native lib: libmelonDS-android-frontend.so (+ liblibrashader.so) — unique among RomCore cores; package me.magnum.melonds.
    // Pin WatermelonDS's flavored variant HERE (not via the consumer's missingDimensionStrategy, which would have to be
    // repeated in :app): gitHub = adrenotools (custom Vulkan drivers) on, prod = stable channel.
    debugImplementation("me.magnum:app:embedded") { attributes { attribute(variantAttr, objects.named(VariantAttr::class.java, "gitHubProdDebug")) } }
    releaseImplementation("me.magnum:app:embedded") { attributes { attribute(variantAttr, objects.named(VariantAttr::class.java, "gitHubProdRelease")) } }
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.startup:startup-runtime:1.2.0")
    api("androidx.work:work-runtime:2.11.2") // RomCoreHiltApplication is a Configuration.Provider
    // Hilt itself (plugin + compiler + the @HiltAndroidApp root) must live in the :app module; see RomCoreHiltApplication.
    implementation("com.google.dagger:hilt-android:2.60.1")
}
