pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "RomCore"
include(":app", ":romcore-api")
// Each core agent adds its own line here when its module first builds:
// include(":core-retroarch", ":core-ppsspp", ":core-dolphin", ":core-primehack", ":core-cemu",
//         ":core-eden", ":core-azahar", ":core-watermelonds", ":core-armsx2")

// :core-eden (Switch). Eden's android project is consumed as a composite build (its :app was converted to a library).
include(":core-eden")
includeBuild("../eden-src/src/android") {
    name = "eden"
    dependencySubstitution { substitute(module("dev.eden_emu:app")).using(project(":app")) }
}

// :core-cemu (Wii U). SSimco's Cemu android project (cemu-src, local library-converted build) as a composite build.
include(":core-cemu")
includeBuild("../cemu-src/src/android") {
    name = "cemu"
    dependencySubstitution { substitute(module("info.cemu:app")).using(project(":app")) }
}

// :core-watermelonds (Nintendo DS/DSi). WatermelonDS (melonDS-android fork) consumed as a composite build; its :app is a library.
// Local patches (RomCoreBridge seam, JDK17/arm64-only) live in ../watermelonds-src (local commits, never pushed upstream).
include(":core-watermelonds")
includeBuild("../watermelonds-src") {
    name = "watermelonds"
    dependencySubstitution { substitute(module("me.magnum:app")).using(project(":app")) }
}

// :core-armsx2 (PS2). ARMSX2 (PCSX2 ARM fork, GPLv3): its android project (../armsx2-src/platforms/android, :app converted to a
// library) is consumed as a composite build. Explicit name: several included builds end in ".../android".
include(":core-armsx2")
includeBuild("../armsx2-src/platforms/android") {
    name = "armsx2"
    dependencySubstitution { substitute(module("com.armsx2:app")).using(project(":app")) }
}

// :core-dolphin (GameCube/Wii). Mainline Dolphin: native code from ../dolphin-src (CMake, branch `romcore`),
// Java layer package-relocated into this module. See core-dolphin/README-dolphin.md.
include(":core-dolphin")

// :core-azahar (3DS). Azahar (Citra lineage, GPL-2.0-only): ../azahar-src/src/android (:app already a library) as a composite build.
include(":core-azahar")
includeBuild("../azahar-src/src/android") {
    name = "azahar"
    dependencySubstitution { substitute(module("org.azahar_emu:app")).using(project(":app")) }
}

// :core-retroarch (libretro host). Local RetroArch build (retroarch-src, with the romcore JNI bridge patch) as a composite build.
include(":core-retroarch")
includeBuild("../retroarch-src/pkg/android/phoenix") {
    name = "retroarch"
    dependencySubstitution { substitute(module("com.retroarch:phoenix")).using(project(":")) }
}

// :core-primehack (GameCube/Wii, Metroid Prime trilogy). PrimeHack (shiiion/dolphin) via a local RomCore-patched clone
// (../primehack-romcore-src; made by core-primehack/tools/relocate_primehack.py + core-primehack/patches). Module coordinates
// romcore.primehack:app deliberately differ from org.dolphinemu:app so :core-dolphin can substitute its own.
include(":core-primehack")
includeBuild("../primehack-romcore-src/Source/Android") {
    name = "primehack"
    dependencySubstitution { substitute(module("romcore.primehack:app")).using(project(":app")) }
}

// :core-ppsspp (PSP). PPSSPP (GPL-2.0+) native + Java built in-module from ../ppsspp-src (branch `romcore`); see core-ppsspp/patches.
include(":core-ppsspp")

// :core-dusklight (GameCube, The Legend of Zelda: Twilight Princess ONLY). Dusklight (CC0-1.0, decomp-based native port)
// from ../dusklight-src (local branch `romcore-local`, never pushed): its platforms/android module is a library
// consumed as a composite build; native lib libdusklight_main.so, SDL3 Java package dev.twilitrealm.dusksdl.
// Native build: see core-dusklight/README-dusklight.md (cmake --preset android-arm64 in ../dusklight-src, NDK 29).
include(":core-dusklight")
includeBuild("../dusklight-src/platforms/android") {
    name = "dusklight"
    dependencySubstitution { substitute(module("dev.twilitrealm:app")).using(project(":app")) }
}

// :core-wwhd (Wii U: The Wind Waker HD only). Native runtime from ../wwhd-src (CMake, local branch `romcore`), no game code in the library;
// the game is translated on the device from the user's own .wua. See core-wwhd/README-wwhd.md.
include(":core-wwhd")
