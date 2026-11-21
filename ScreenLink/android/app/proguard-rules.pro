# R8 full mode is the AGP 8 default. ScreenLink uses no reflection or serialization frameworks.

# Strip verbose/debug/info logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
