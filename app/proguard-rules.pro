-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# Hook handles and the coordinator are deliberate strong roots. Modern module entry
# instances are not guaranteed to remain strongly reachable after lifecycle callbacks.
-keep,allowobfuscation class io.github.yylsping.coolapkpurifier.** {
    *;
}

# DexKit is loaded from the framework-owned installed native library. Keep
# all descriptors because JNI registration and FlatBuffers query classes are
# referenced reflectively/natively.
-keep class org.luckypray.dexkit.** { *; }
-keep class com.google.flatbuffers.** { *; }
-dontwarn org.luckypray.dexkit.**

# These names describe HOST Kotlin objects, not the module's private copies.
# R8 otherwise rewrites Class.forName("kotlin.jvm.functions.Function1", hostLoader)
# to the module's renamed class, which the host loader cannot load.
-keep interface kotlin.jvm.functions.Function1 { *; }
-keep class kotlin.Unit { *; }
