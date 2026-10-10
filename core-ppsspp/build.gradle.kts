plugins {
    id("com.android.library")
    id("com.google.protobuf") version "0.10.0"
}

// PPSSPP (PSP), GPL-2.0-or-later. Native + Java come from ../../ppsspp-src (branch `romcore`, patches mirrored in
// patches/). The stock android/src Java layer (org.ppsspp.ppsspp.*) is compiled in unchanged-by-copy; the game Activity
// is our subclass RomCorePpssppActivity running in android:process=":ppsspp". Native lib: libppsspp_jni.so (unique name),
// static C++ STL so no libc++_shared.so is shipped.
val ppssppSrc = file("../../ppsspp-src")

android {
    namespace = "org.ppsspp.ppsspp"   // R / BuildConfig package expected by the stock PPSSPP Java sources
    compileSdk = 37
    ndkVersion = "27.3.13750724"

    defaultConfig {
        minSdk = 30
        buildConfigField("String", "FLAVOR", "\"normal\"")
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                cppFlags("-DROMCORE=1")
                cFlags("-DROMCORE=1")
                arguments(
                    "-DANDROID=true",
                    "-DANDROID_TOOLCHAIN=clang",
                    "-DANDROID_CPP_FEATURES=",
                    "-DANDROID_STL=c++_static",
                    "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
                )
            }
        }
    }
    externalNativeBuild { cmake { path = File(ppssppSrc, "CMakeLists.txt") } }

    buildFeatures { buildConfig = true; aidl = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { abortOnError = false }
    packaging { jniLibs.useLegacyPackaging = true }

    sourceSets {
        getByName("main") {
            java.directories.add("../../ppsspp-src/android/src")
            aidl.directories.add("../../ppsspp-src/android/src")
            res.directories.add("../../ppsspp-src/android/res")
            res.directories.add("../../ppsspp-src/android/normal/res")
            res.directories.add("../../ppsspp-src/android/src/main/res")
            assets.directories.add("../../ppsspp-src/assets")
        }
    }
}

dependencies {
    api(project(":romcore-api"))
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.protobuf:protobuf-javalite:4.35.1")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:3.25.3" }
    generateProtoTasks { all().forEach { it.builtins { register("java") { option("lite") } } } }
}
