plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp") version "2.3.12"
}

android {
    namespace = "com.noryan.romcore"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.noryan.romcore"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation(project(":romcore-api"))
    // Core modules are optional at build time: each is linked in only if its module exists in settings.gradle.kts.
    // CoreBootstrap.kt finds and registers them reflectively, so nothing here is referenced from code directly.
    // Override with -Promcore.cores=none or -Promcore.cores=dolphin,ppsspp to link a subset (e.g. while a core is mid-work).
    val wanted = (findProperty("romcore.cores") as String?)?.split(",")?.map { it.trim() }
    listOf("retroarch", "ppsspp", "dolphin", "primehack", "cemu", "eden", "azahar", "watermelonds", "armsx2").forEach { id ->
        if ((wanted == null || id in wanted) && rootProject.findProject(":core-$id") != null) implementation(project(":core-$id"))
    }

    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
