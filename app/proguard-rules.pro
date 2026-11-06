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

# 虚拟屏 Binder 交接。服务端是 app_process 加载的 jar（**不经过 R8**），它把
# me.bmax.apatch.display.DisplayBinderContainer 塞进广播的 Intent extra，**类名是随 Parcel
# 一起过线的**。App 侧一旦被改名，还原时就是 ClassNotFoundException，交接永远等不到。
#
# 这条漏过一次，真机表现极具误导性：界面说「服务端进程没起来」，而实际上服务端起来了、
# 广播也按时到了，只是载荷读不出来 —— logcat 里才有 `Class not found when unmarshalling`。
# 与上面 Shizuku 那条是完全同一类问题（类名要过线），所以放在一起。
#
# 反例，别顺手加：IDisplayService **不需要** keep。它过线靠的是 AIDL 描述符字符串
# （writeInterfaceToken/enforceInterface 里的字面量）与按方法顺序编号的事务码，
# R8 不改字符串字面量，两边由同一份 .aidl 生成，所以混淆是安全的。
-keep class me.bmax.apatch.display.DisplayBinderContainer { *; }

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
# WebView 的 JS 桥：BlobBridge（下载）与 UserscriptBridge（通知）只由网页侧通过注入的对象调用，
# Kotlin 里没有调用点。AGP 默认规则已含 @JavascriptInterface 的 keepclassmembers，但本文件的
# -repackageclasses / -allowaccessmodification / -overloadaggressively 是「激进」档，
# 这里补一份显式规则做兜底 —— 丢了它 Release 包的表现是「下载与通知静默失效」，很难查
# （外部审查 P0-4）。
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class me.bmax.apatch.ui.DshWebUiActivity$BlobBridge { *; }
-keep class me.bmax.apatch.ui.DshWebUiActivity$UserscriptBridge { *; }
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

# ─────────────────────────────────────────────────────────────────────────────
# 扩展 API 必须**原样保留**：这是本仓库唯一被「外部编译产物」引用的包。
#
# 扩展是独立编译的 DEX，里面的引用写死了 `Lme/bmax/apatch/dsh/ext/DshExtension;` 这类名字。
# release 构建开了 isMinifyEnabled（beta 通道就是 release 变体），R8 会把整个包改名、
# 还会因为 -repackageclasses / -allowaccessmodification 把它们挪到别的包里。
# 一旦改名，ART 在链接扩展类时就找不到父类型 —— 表现是「Didn't find class <扩展入口类>」，
# 而扩展自己的 dex 完全正常（校验和、class_defs、入口类名都对得上）。
# 这个坑真机踩过：beta.236 ~ beta.240 的扩展全都加载不上，日志只说找不到入口类。
#
# 覆盖范围（整包，含成员）：DshExtension / DshExtensionHost / ExtensionLayout /
# ExtensionManifest / ExtensionFs / 三个注册中心 / ShellHost / ShellContent /
# RuntimeSummary / ExtensionGate / ExtLog / ExtensionShellTabs。
-keep class me.bmax.apatch.dsh.ext.** { *; }
-keep interface me.bmax.apatch.dsh.ext.** { *; }

# 宿主侧的实现也在同一个包里（AppExtensionFs / ExtensionHostImpl / ExtensionEngine 等）：
# 上面的规则已覆盖。这里额外保一下枚举/常量类不被内联掉 ——
# 扩展会读 ExtensionShellTabs.HOME 这类常量，内联后名字消失不影响功能，
# 但 ExtensionUiRegistry.Slot 的字符串常量是接口的一部分，保留更稳。
-keepclassmembers class me.bmax.apatch.dsh.ext.** {
    public static final java.lang.String *;
    public static final int *;
}

# 顺带：Compose 的 @Composable 函数被扩展以 lambda 形式传入，别让 R8 把它们裁掉。
-keepclassmembers class me.bmax.apatch.dsh.ext.** {
    *** Companion;
}
-keepclasseswithmembers class me.bmax.apatch.dsh.ext.** {
    kotlin.jvm.functions.Function* *;
}

