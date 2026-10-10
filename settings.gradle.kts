pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "RomCore"
include(":app", ":romcore-api")
// Each core agent adds its own line here when its module first builds:
// include(":core-retroarch", ":core-ppsspp", ":core-dolphin", ":core-primehack", ":core-cemu",
//         ":core-eden", ":core-azahar", ":core-watermelonds", ":core-armsx2")
