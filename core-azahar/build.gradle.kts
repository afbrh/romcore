plugins {
    id("com.android.library")
}

android {
    namespace = "com.noryan.romcore.core.azahar"
    compileSdk = 37
    defaultConfig {
        minSdk = 30
        // Azahar's library declares a "version" flavor dimension (vanilla/googlePlay); vanilla = sideload build.
        missingDimensionStrategy("version", "vanilla")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { jniLibs.useLegacyPackaging = true } // libadrenotools custom-driver loading needs extracted libs
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(project(":romcore-api"))
    // azahar-src/src/android (:app already converted to a library) via includeBuild in settings.gradle.kts.
    // Native lib: libcitra-android.so (unique among RomCore cores); Java package org.citra.citra_emu (no clash).
    // The dependency-level attribute pins Azahar's "version" flavor for the final app's runtime classpath too (a
    // library's missingDimensionStrategy does not propagate), so :app needs no missingDimensionStrategy for it.
    implementation("org.azahar_emu:app:embedded") {
        attributes {
            attribute(
                com.android.build.api.attributes.ProductFlavorAttr.of("version"),
                objects.named(com.android.build.api.attributes.ProductFlavorAttr::class.java, "vanilla"),
            )
        }
    }
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
