# R8 is disabled for the demo build (see app/build.gradle.kts). These rules are kept so that
# shrinking can be switched on once the app has been validated on physical devices.

# --- Signal Protocol (libsignal) -------------------------------------------------------------
# libsignal binds Java classes/methods from Rust through JNI, so nothing may be renamed.
-keep class org.signal.libsignal.** { *; }
-keep interface org.signal.libsignal.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# --- Room ------------------------------------------------------------------------------------
-keep class com.offlinemesh.messenger.core.db.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { <init>(); }

# --- kotlinx.serialization -------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.offlinemesh.messenger.**$$serializer { *; }
-keepclassmembers class com.offlinemesh.messenger.** {
    *** Companion;
}
-keepclasseswithmembers class com.offlinemesh.messenger.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- SQLCipher / zxing -----------------------------------------------------------------------
-keep class net.zetetic.database.** { *; }
-keep class net.sqlcipher.** { *; }
-dontwarn net.zetetic.database.**
-keep class com.google.zxing.** { *; }
