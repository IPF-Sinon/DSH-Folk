-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.conscrypt.Conscrypt$Version
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.ConscryptHostnameVerifier
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE
-dontwarn java.beans.Introspector
-dontwarn java.beans.VetoableChangeListener
-dontwarn java.beans.VetoableChangeSupport
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.PropertyDescriptor

# Keep ini4j Service Provider Interface
-keep,allowobfuscation,allowoptimization class org.ini4j.spi.** { *; }

# 保留任何带 native 方法的类（DSH-Folk 自身不再有 JNI，但依赖库可能有）
-keepclasseswithmembernames class * {
    native <methods>;
}

# Shizuku 客户端 Provider（清单里按类名引用，不能被混淆掉）
-keep class rikka.shizuku.ShizukuProvider { *; }
-keep class moe.shizuku.api.BinderContainer { *; }

# Shizuku 用户服务：由 Shizuku 在**它自己的进程**里反射实例化，应用侧没有任何静态引用，
# 所以 R8 会把它当成没用的类删掉、或改掉类名与无参构造 —— 而在 release 里那表现出来
# 只是"绑定永远超时"，跟权限页显示的"Shizuku 一切正常"互相矛盾，几乎无法自查。
# 它也不能写进清单来"顺便"保住（用户服务不是框架服务，见 DshShizukuShellService 的 KDoc），
# 所以必须在这里显式保留。
-keep class me.bmax.apatch.dsh.DshShizukuShellService {
    <init>();
    *;
}

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Kotlin
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void check*(...);
    public static void throw*(...);
}

-repackageclasses
-allowaccessmodification
-overloadaggressively
-renamesourcefileattribute SourceFile
