# JNI bridge: native method names are bound by symbol, keep the class and members intact.
-keep class dev.pocketagent.core.llm.LlamaBridge { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Services are referenced from the manifest.
-keep class dev.pocketagent.service.** { *; }

# WorkManager workers are instantiated reflectively.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# OkHttp / Okio
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Jsoup optional dependency
-dontwarn com.google.re2j.**

# Tink (EncryptedSharedPreferences)
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
