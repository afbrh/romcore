plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
}

// Mainline Dolphin (GameCube/Wii). Native build: ../../dolphin-src (branch `romcore`, see patches/ and
// README-dolphin.md) built with CMake into libdolphin_main.so. Java side: Dolphin's Android model/utility layer,
// package-relocated to com.noryan.romcore.core.dolphin.jni (also this module's R/BuildConfig namespace).
val dolphinSrc = file("../../dolphin-src")

android {
    namespace = "com.noryan.romcore.core.dolphin.jni"
    compileSdk = 37
    ndkVersion = "27.3.13750724"

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    defaultConfig {
        minSdk = 30
        buildConfigField("String", "GIT_HASH", "\"romcore\"")
        buildConfigField("String", "BRANCH", "\"romcore\"")
        buildConfigField("String", "VERSION_NAME", "\"romcore\"")
        buildConfigField("String", "APPLICATION_ID", "\"com.noryan.romcore\"")
        ndk { abiFilters += "arm64-v8a" }
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                arguments(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
                    "-DROMCORE_SKIP_ASSETS=ON",
                )
            }
        }
    }

    buildTypes {
        release { resValue("string", "app_name_suffixed", "Dolphin") }
        debug { resValue("string", "app_name_suffixed", "Dolphin Debug") }
    }

    externalNativeBuild {
        cmake {
            path = File(dolphinSrc, "CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = false }
    packaging { jniLibs.useLegacyPackaging = true } // libadrenotools loads its hook libs from the extracted nativeLibraryDir

    // Dolphin's Sys/ tree (shaders, GameSettings, wii resources...) is staged here and shipped as assets.
}

androidComponents {
    onVariants { v ->
        v.sources.assets?.addStaticSourceDirectory(layout.buildDirectory.dir("generated/dolphinAssets").get().asFile.path)
    }
}

val stageDolphinSys = tasks.register<Copy>("stageDolphinSys") {
    from(File(dolphinSrc, "Data/Sys")) { exclude("Themes/**") }
    into(layout.buildDirectory.dir("generated/dolphinAssets/Sys"))
}
tasks.matching { it.name.matches(Regex("merge.*Assets")) || it.name.matches(Regex("lintVitalAnalyze.*")) }
    .configureEach { dependsOn(stageDolphinSys) }

dependencies {
    implementation(project(":romcore-api"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
