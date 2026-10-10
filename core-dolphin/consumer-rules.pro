# Dolphin's native code (libdolphin_main.so) resolves these classes/fields/methods by name via JNI (IDCache.cpp,
# GCAdapter.cpp, Android ControllerInterface...). Never rename or strip them.
-keep class com.noryan.romcore.core.dolphin.jni.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
