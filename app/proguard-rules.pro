# Keep kotlinx.serialization classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.cascadiacollections.bauhaus.**$$serializer { *; }
-keepclassmembers class com.cascadiacollections.bauhaus.** {
    *** Companion;
}
-keepclasseswithmembers class com.cascadiacollections.bauhaus.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# WorkManager: keep ListenableWorker subclasses with their two-arg constructor so
# WorkManager can instantiate them by class name stored in the WorkInfo database.
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# OkHttp: suppress warnings for optional TLS-provider classes that are absent on
# Android but referenced via optional class-loading paths in OkHttp 5.x.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Enums: preserve values() and valueOf() for WallpaperTarget, which is
# reconstructed from its persisted name string via WallpaperTarget.valueOf().
-keepclassmembers enum com.cascadiacollections.bauhaus.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Firebase Crashlytics: keep annotation attributes for symbolication
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# AboutLibraries: keep generated library metadata
-keep class com.mikepenz.aboutlibraries.** { *; }
