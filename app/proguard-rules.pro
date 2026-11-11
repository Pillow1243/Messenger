# Preserve source file & line numbers for crash diagnostics while obfuscating/shrinking
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*

# Signal Protocol JNI & Rust native bridge (libsignal-android 0.86.5)
-keep class org.signal.libsignal.** { *; }
-keep interface org.signal.libsignal.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep @org.signal.libsignal.internal.CalledFromNative class * { *; }
-keepclassmembers class * {
    @org.signal.libsignal.internal.CalledFromNative <methods>;
    @org.signal.libsignal.internal.CalledFromNative <fields>;
}
-keep class rustls.** { *; }
-keep class com.offlinemesh.messenger.core.crypto.SignalStore { *; }

# WebRTC JNI (io.getstream:stream-webrtc-android)
-keep class org.webrtc.** { *; }
-keep interface org.webrtc.** { *; }
-dontwarn org.webrtc.**

# SQLCipher JNI
-keep class net.zetetic.** { *; }
-keep interface net.zetetic.** { *; }

# Room database & entities
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.paging.**

# kotlinx.serialization
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.offlinemesh.messenger.**$$serializer { *; }
-keepclassmembers class com.offlinemesh.messenger.** {
    *** Companion;
}
-keepclasseswithmembers class com.offlinemesh.messenger.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Firebase Messaging & Play Services Nearby
-keep class com.offlinemesh.messenger.push.MessengerFirebaseMessagingService { *; }
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# OkHttp / Okio / optional JVM TLS providers
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ZXing QR
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
