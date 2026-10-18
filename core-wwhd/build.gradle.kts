plugins {
    id("com.android.library")
}

// :core-wwhd (Wii U: The Legend of Zelda: The Wind Waker HD only). The native runtime is WindWakerHD-Android-Plus (MPL-2.0), a static
// recompilation of the game with GX2 on Vulkan: sources in ../../wwhd-src (local branch `romcore`, patches in core-wwhd/patches),
// built with CMake into libwwhd.so exactly like the project's own "-PwwhdDeviceRecomp" APK: the library contains NO game code.
// The game's PowerPC code is translated from the user's own .wua on the device (LLVM, built once for Android into ../../wwhd-llvm/install
// with ../../wwhd-llvm/build-llvm.sh). See README-wwhd.md.
val wwhdSrc = file("../../wwhd-src")
val wwhdLlvm = file("../../wwhd-llvm/install")

android {
    namespace = "com.noryan.romcore.core.wwhd"
    compileSdk = 37
    ndkVersion = "27.3.13750724"

    buildFeatures { buildConfig = true }

    defaultConfig {
        minSdk = 30
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "VERSION_NAME", "\"WindWakerHD-Android-Plus 1.11.1 (romcore)\"")
        externalNativeBuild {
            cmake {
                targets("wwhd")
                arguments(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DWWHD_DEVICE_RECOMP=ON",
                    "-DWWHD_LLVM_DIR=${wwhdLlvm.absolutePath}",
                )
            }
        }
    }

    buildTypes {
        release { externalNativeBuild { cmake { arguments("-DCMAKE_BUILD_TYPE=Release") } } }
        // the runtime is unusably slow at -O0 (the project's own note): optimised with debug info
        debug { externalNativeBuild { cmake { arguments("-DCMAKE_BUILD_TYPE=RelWithDebInfo") } } }
    }

    externalNativeBuild {
        cmake {
            path = File(wwhdSrc, "CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
    api(project(":romcore-api"))
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
