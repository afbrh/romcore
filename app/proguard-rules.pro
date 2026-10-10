# Cores are registered reflectively by name (CoreBootstrap).
-keep class com.noryan.romcore.core.** { *; }
-keep class com.noryan.romcore.api.** { *; }
-keep class com.noryan.romcore.RomCoreApp { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn **
-ignorewarnings
