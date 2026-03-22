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
