# Log calls are diagnostics only: R8 drops them and their string arguments (measured −196 B).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# JNI: the C side looks up SevenZip.extract by name
-keepclasseswithmembernames class de.regepower.dualfiles.SevenZip { native <methods>; }
