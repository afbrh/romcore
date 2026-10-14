plugins {
    id("com.android.library")
}

// :core-primehack (GameCube/Wii; preferred for Metroid Prime / Prime 2 / Prime 3 / Trilogy).
// PrimeHack (shiiion/dolphin, GPLv2+) is consumed as a composite build of ../primehack-romcore-src, a local clone of
// ../primehack-src with RomCore patches (package relocated to com.noryan.romcore.core.primehack.jni, JNI lib renamed
// libprimehack_main.so, resources suffixed _ph, user/Sys dirs isolated). See core-primehack/README-INTEGRATION.md.
android {
    namespace = "com.noryan.romcore.core.primehack"
    compileSdk = 37
    defaultConfig { minSdk = 30 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
    api(project(":romcore-api"))
    api("romcore.primehack:app")   // substituted by includeBuild("../primehack-romcore-src/Source/Android") in settings.gradle.kts
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    testImplementation("junit:junit:4.13.2")
}
