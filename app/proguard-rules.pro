# Cores are registered reflectively by name (CoreBootstrap).
-keep class com.noryan.romcore.core.** { *; }
-keep class com.noryan.romcore.api.** { *; }
-keep class com.noryan.romcore.RomCoreApp { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn **
-ignorewarnings
# Azahar's SettingsFile reads/writes config.ini through ini4j, which instantiates its parser/handlers reflectively
# (org.ini4j.spi.IniParser/IniBuilder/... via ServiceFinder + META-INF services). R8 stripping them silently dropped
# every RomCore default (GL/4x/dual display).
-keep class org.ini4j.** { *; }
-keepclassmembers class * implements org.ini4j.spi.* { <init>(...); }
