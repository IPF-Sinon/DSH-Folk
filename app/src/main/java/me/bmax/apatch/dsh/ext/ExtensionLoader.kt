package me.bmax.apatch.dsh.ext

import android.content.Context
import dalvik.system.DexClassLoader
import me.bmax.apatch.dsh.DshRuntime
import java.io.File

/**
 * 把扩展的 classes.dex 加载进来并实例化入口类。
 *
 * ## 两条容易踩的坑
 * 1. **Android 14+ 只读要求**（targetSdk 36 必然触发）：动态加载的 dex 若位于可写目录，
 *    系统直接抛 SecurityException（Writable dex file ... is not allowed）。所以加载前必须
 *    setReadOnly()；装扩展时也要保证解压出来的 dex 不带写权限。
 * 2. **父加载器必须是宿主的 classLoader**：否则扩展拿不到宿主的任何类（也就谈不上「同权」）。
 *    代价是扩展能看到宿主的全部类型 —— 这正是本设计的既定取舍（见 ExtensionSafetyGuard）。
 */
object ExtensionLoader {

    /**
     * 加载并实例化。任何一步失败都返回 null（调用方跳过该扩展，不影响其它扩展与宿主启动）。
     *
     * @param onFatal 失败时把原因交给调用方记日志（统一在这里拼会丢掉堆栈）。
     */
    fun load(ctx: Context, manifest: ExtensionManifest, onFatal: (String) -> Unit): DshExtension? {
        val entry = manifest.entryClass ?: return null
        val dex = ExtensionLayout.dexOf(ctx, manifest.id)
        if (!dex.isFile) {
            onFatal("缺 classes.dex：" + dex.absolutePath)
            return null
        }
        DshRuntime.appendLog(
            "[ext] " + manifest.id + " 准备加载：dex=" + dex.absolutePath +
                "（" + dex.length() + " 字节，可写=" + dex.canWrite() + "），入口类=" + entry,
        )
        // 只读：Android 14+ 的硬性要求，不设就一定加载失败
        runCatching { dex.setReadOnly() }
        val optimizeDir = File(ctx.codeCacheDir, "ext_" + manifest.id).apply { mkdirs() }
        val assetDir = ExtensionLayout.assetsOf(ctx, manifest.id)
        return runCatching {
            val classLoader = DexClassLoader(
                dex.absolutePath,
                optimizeDir.absolutePath,
                if (assetDir.isDirectory) assetDir.absolutePath else null,
                ctx.classLoader,
            )
            val clazz = classLoader.loadClass(entry)
            val instance = clazz.getDeclaredConstructor().newInstance()
            // as? 失败会让「扩展实现了接口但类型对不上」表现成「加载成功却什么都没发生」，
            // 所以这里把真实类型打出来（典型原因：扩展打包时自带了一份 :ext-api 的类）。
            if (instance !is DshExtension) {
                DshRuntime.appendLog(
                    "[ext] " + manifest.id + " 入口类没有实现 DshExtension，实际类型=" +
                        instance.javaClass.name + "，其接口=" +
                        instance.javaClass.interfaces.joinToString { it.name },
                )
                return null
            }
            instance
        }.onFailure {
            onFatal("加载失败（" + entry + "）：" + (it.message ?: it.javaClass.simpleName))
        }.getOrNull()
    }

    /**
     * 重新给扩展目录里的 dex 上只读权限（安装后调用一次）。
     *
     * 单独抽出来是因为「解压 → 装好 → 下次启动才加载」这条路径上，只读要在**安装时**就设好：
     * 用户中途可能用文件管理器改权限，启动时再设只能兜一次底。
     */
    fun sealDex(ctx: Context, id: String) {
        runCatching {
            val dex = ExtensionLayout.dexOf(ctx, id)
            if (dex.isFile) dex.setReadOnly()
        }
    }

    /** 加载一个已启用的扩展（含 manifest 校验与日志）。给 [ExtensionEngine] 用。 */
    internal fun boot(ctx: Context, rec: ExtensionRecord): DshExtension? {
        val mf = rec.manifest
        // minAppVersion 必须在**加载时**拦住，而不是只在市场里拦：
        // 扩展可能被用户手工拷进 files/extensions/、也可能是「市场里装完、App 后来被降级」。
        // 不拦的后果是运行时 NoSuchMethodError —— 扩展调的是新版本宿主才有的方法，
        // 而那个异常会出现在扩展自己的调用栈里，看起来像是「宿主崩了」。
        val required = mf.minAppVersion
        if (!required.isNullOrBlank() && !DshRuntime.appSatisfies(required)) {
            DshRuntime.appendLog(
                "[ext] " + mf.id + " 要求宿主 >= " + required + "，当前 " +
                    me.bmax.apatch.BuildConfig.VERSION_NAME + "，已跳过加载",
            )
            return null
        }
        val ext = load(ctx, mf) { why ->
            DshRuntime.appendLog("[ext] " + mf.id + " " + why)
        } ?: return null
        val host: DshExtensionHost = ExtensionHostImpl(mf.id, ctx.applicationContext)
        return runCatching {
            ext.onLoad(host)
            DshRuntime.appendLog("[ext] 已加载 " + mf.name + " v" + mf.version)
            ext
        }.onFailure {
            DshRuntime.appendLog(
                "[ext] " + mf.id + " onLoad 抛异常（已跳过）：" +
                    (it.message ?: it.javaClass.simpleName),
            )
            // onLoad 半途失败：把它的注册全部撤掉，避免留下悬空 Hook
            runCatching { ExtensionServiceRegistry.unregisterAll(mf.id) }
            runCatching { ExtensionHookRegistry.unregisterAll(mf.id) }
            runCatching { ExtensionUiRegistry.unregisterAll(mf.id) }
            null
        }.getOrNull()
    }
}
