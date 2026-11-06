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
        ExtLog.info(
            manifest.id + " 准备加载：dex=" + dex.absolutePath +
                "（" + dex.length() + " 字节），入口类=" + entry,
        )
        val instance = instantiate(ctx, manifest, dex, entry, onFatal) ?: return null
        // as? 失败会让「扩展实现了接口但类型对不上」表现成「加载成功却什么都没发生」，
        // 所以这里把真实类型打出来（典型原因：扩展打包时自带了一份 :ext-api 的类）。
        if (instance !is DshExtension) {
            onFatal(
                "入口类没有实现 DshExtension，实际类型=" + instance.javaClass.name +
                    "，其接口=" + instance.javaClass.interfaces.joinToString { it.name },
            )
            return null
        }
        return instance
    }

    /**
     * 两条加载路径依次尝试，返回入口类的实例。
     *
     * ## 为什么需要两条
     * 真机（Android 16 / SDK 36）上踩到过：dex 完全正常（类定义在 class_defs 里、名字也对），
     * 但 `DexClassLoader.loadClass` 报 `Didn't find class ... DexPathList[[dex file ...]]` ——
     * 文件在路径里、类却找不到，说明 ART 把这个 dex **元素丢掉了**（只读要求 / odex 输出 /
     * 校验失败都会这样，而它们都不抛异常，只让元素静默消失）。
     *
     * 所以先走文件路径（能吃到 dexopt 缓存、启动更快），失败再走内存路径：
     * `InMemoryDexClassLoader` 完全不碰文件权限与 odex 目录，绕开上面整类问题。
     * 小 dex（扩展通常几十 KB）重新校验的开销可以忽略。
     */
    private fun instantiate(
        ctx: Context,
        manifest: ExtensionManifest,
        dex: File,
        entry: String,
        onFatal: (String) -> Unit,
    ): Any? {
        // Android 14+ 要求动态加载的 dex 只读。用 Os.chmod 而不是 File.setReadOnly：
        // 后者在这类目录上可能返回 false（我们以前直接忽略返回值，等于没设）。
        val sealed = runCatching {
            android.system.Os.chmod(dex.absolutePath, 0x124 /* 0444 */)
            true
        }.getOrDefault(false) || runCatching { dex.setReadOnly() }.getOrDefault(false)
        ExtLog.info(manifest.id + " dex 只读=" + sealed + "，canWrite=" + dex.canWrite())

        val optimizeDir = File(ctx.codeCacheDir, "ext_" + manifest.id).apply { mkdirs() }
        val assetDir = ExtensionLayout.assetsOf(ctx, manifest.id)

        // ① 文件路径
        val fileError = runCatching {
            val cl = DexClassLoader(
                dex.absolutePath,
                optimizeDir.absolutePath,
                if (assetDir.isDirectory) assetDir.absolutePath else null,
                ctx.classLoader,
            )
            cl.loadClass(entry).getDeclaredConstructor().newInstance()
        }.onFailure {
            ExtLog.info(manifest.id + " 文件加载失败，改用内存加载：" + (it.message ?: it.javaClass.simpleName))
        }.getOrNull()
        if (fileError != null) return fileError

        // ② 内存路径（顺带做一次自检：确认这个 dex 到底能不能吐出任何一个类）
        val memLoader: ClassLoader? = runCatching {
            val bytes = dex.readBytes()
            val buffer = java.nio.ByteBuffer.allocateDirect(bytes.size)
            buffer.put(bytes)
            buffer.position(0)
            dalvik.system.InMemoryDexClassLoader(buffer, ctx.classLoader) as ClassLoader
        }.getOrNull()
        if (memLoader != null) {
            runCatching {
                memLoader.loadClass(entry).getDeclaredConstructor().newInstance()
            }.onSuccess { return it }.onFailure { e ->
                // 自检：从同一个 loader 里试几个**肯定在 dex 里**的类名。
                // 全失败 = 这个 dex 整体没被 ART 接受；只有入口类失败 = 类名或类型解析的问题。
                // 两种情况的修法完全不同，所以这一行是必须的（真机上已经在这一点上卡过两轮）。
                // 两个通用探针，不硬编码任何具体扩展的内部类名：
                //  - 宿主自己的接口：必须经父加载器解析成功，失败说明父加载器没接上；
                //  - 入口类的 Kotlin 文件门面（entry + "Kt"）：同一份 dex、同一个 loader，
                //    它成功而入口类失败，就说明问题出在「这一个类」而不是「整个 dex」。
                val probes = listOf(
                    DshExtension::class.java.name,
                    entry + "Kt",
                )
                val probeResult = probes.joinToString { name ->
                    val ok = runCatching { memLoader.loadClass(name) }.isSuccess
                    name.substringAfterLast('.') + "=" + (if (ok) "OK" else "缺失")
                }
                ExtLog.log(
                    "两条加载路径都失败（" + entry + "）：" + (e.message ?: e.javaClass.simpleName) +
                        "；自检 " + probeResult +
                        "；dex 前 8 字节=" + dex.readBytes().take(8).joinToString("") {
                            "%02x".format(it)
                        },
                )
            }
        }
        onFatal("内存加载无法创建（" + entry + "）")
        return null
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
            ExtLog.log(
                mf.id + " 要求宿主 >= " + required + "，当前 " +
                    me.bmax.apatch.BuildConfig.VERSION_NAME + "，已跳过加载",
            )
            return null
        }
        val ext = load(ctx, mf) { why ->
            ExtLog.log(mf.id + " " + why)
        } ?: return null
        val host: DshExtensionHost = ExtensionHostImpl(mf.id, ctx.applicationContext)
        return runCatching {
            ext.onLoad(host)
            ExtLog.info("已加载 " + mf.name + " v" + mf.version)
            ext
        }.onFailure {
            ExtLog.log(
                mf.id + " onLoad 抛异常（已跳过）：" + (it.message ?: it.javaClass.simpleName),
            )
            // onLoad 半途失败：把它的注册全部撤掉，避免留下悬空 Hook
            runCatching { ExtensionServiceRegistry.unregisterAll(mf.id) }
            runCatching { ExtensionHookRegistry.unregisterAll(mf.id) }
            runCatching { ExtensionUiRegistry.unregisterAll(mf.id) }
            null
        }.getOrNull()
    }
}
