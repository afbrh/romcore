import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp") version "2.3.12"
    id("com.google.dagger.hilt.android") version "2.60.1"
}

android {
    namespace = "com.noryan.romcore"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.noryan.romcore"
        minSdk = 30
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.1"
        ndk { abiFilters += "arm64-v8a" }
        missingDimensionStrategy("edenVersion", "legacy")
    }
    // Release signing: key + passwords live outside the repo (~/.romcore-signing.properties); without it the release build is unsigned.
    val signingProps = Properties()
    File(System.getProperty("user.home"), ".romcore-signing.properties").takeIf { it.exists() }?.inputStream()?.use { signingProps.load(it) }
    signingConfigs {
        if (signingProps.isNotEmpty()) create("release") {
            storeFile = file(signingProps.getProperty("storeFile")); storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias"); keyPassword = signingProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        // R8 is ON in debug too: ARMSX2's Settings class fails bytecode verification un-shrunk (VerifyError).
        getByName("debug") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("release") {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { checkReleaseBuilds = false }   // lintVital trips over core-dolphin's asset staging task wiring
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // Several cores (adrenotools users: azahar, armsx2, eden, cemu, dolphin) load hook libs from nativeLibraryDir.
        jniLibs.useLegacyPackaging = true
        jniLibs.pickFirsts += listOf(
            "lib/arm64-v8a/libhook_impl.so", "lib/arm64-v8a/libmain_hook.so",
            "lib/arm64-v8a/libgsl_alloc_hook.so", "lib/arm64-v8a/libfile_redirect_hook.so",
            "lib/arm64-v8a/libc++_shared.so", "lib/arm64-v8a/libusb-1.0.so",
            "lib/arm64-v8a/libVkLayer_khronos_validation.so", "lib/arm64-v8a/libSPIRV-Tools-shared.so",
            "lib/arm64-v8a/libshaderc_shared.so",
        )
    }
}

dependencies {
    implementation(project(":romcore-api"))
    // Core modules are optional at build time: each is linked in only if its module exists in settings.gradle.kts.
    // CoreBootstrap.kt finds and registers them reflectively, so nothing here is referenced from code directly.
    // Override with -Promcore.cores=none or -Promcore.cores=dolphin,ppsspp to link a subset (e.g. while a core is mid-work).
    val wanted = (findProperty("romcore.cores") as String?)?.split(",")?.map { it.trim() }
    listOf("retroarch", "ppsspp", "dolphin", "primehack", "cemu", "eden", "azahar", "watermelonds", "armsx2", "dusklight", "shipwright", "wwhd").forEach { id ->
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
    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-android-compiler:2.60.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}

// Eden is compiled against Material 1.12.0 (newer drops R.attr.colorPrimary -> NoSuchFieldError); other cores ask for
// 1.13-1.14. One Material in the APK: force the old one.
configurations.configureEach {
    resolutionStrategy.force("com.google.android.material:material:1.12.0")
    // com.atlassian.commonmark:commonmark 0.13 and org.commonmark:commonmark 0.22 ship identical packages.
    exclude(group = "com.atlassian.commonmark")
}
