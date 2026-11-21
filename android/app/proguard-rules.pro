# R8 full mode is the AGP 8 default. Keep only what reflection/serialization needs.

# kotlinx.serialization: keep generated serializers for our @Serializable models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class io.spatia.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class io.spatia.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Strip verbose/debug logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
