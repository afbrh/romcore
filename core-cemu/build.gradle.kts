plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.noryan.romcore.core.cemu"
    compileSdk = 37
    defaultConfig { minSdk = 30 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
    // Cemu's Vulkan custom-driver loader dlopen()s libs from nativeLibraryDir by name.
    packaging { jniLibs.useLegacyPackaging = true }
}

dependencies {
    implementation(project(":romcore-api"))
    // Cemu (SSimco/Cemu android-port) built from /Users/ry/Desktop/claude/cemu-src via includeBuild
    // (see settings.gradle.kts). Brings libCemuAndroid.so + its Kotlin JNI bridge (info.cemu.cemu.*).
    api("info.cemu:app:embedded")

    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.datastore:datastore:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
