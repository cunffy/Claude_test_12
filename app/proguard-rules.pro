# Anthropic SDK
-keep class com.anthropic.** { *; }
-dontwarn com.anthropic.**

# OkHttp
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-keep interface okhttp3.** { *; }
-dontwarn okio.**

# Kotlin reflection
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.jvm.**

# Porcupine
-keep class ai.picovoice.** { *; }
-dontwarn ai.picovoice.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers class * { @androidx.room.* <fields>; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-dontwarn dagger.**

# DataStore
-keep class androidx.datastore.** { *; }

# WorkManager
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Keep all application classes
-keep class com.personalai.craig.** { *; }
