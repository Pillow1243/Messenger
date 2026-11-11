# Keep Room entities and DAOs
-keep class com.offlinemesh.messenger.core.db.** { *; }

# Keep mesh packets for kotlinx.serialization
-keep class com.offlinemesh.messenger.core.mesh.** { *; }
-keepattributes *Annotation*, InnerClasses
-dontnote org.whispersystems.**

# Keep Signal Protocol (reflection-free but keep for safety)
-keep class org.whispersystems.** { *; }

# Keep BouncyCastle
-keep class org.bouncycastle.** { *; }
