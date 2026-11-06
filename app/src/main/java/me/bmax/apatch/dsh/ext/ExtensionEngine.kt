package me.bmax.apatch.dsh.ext

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * 扩展系统的编排器：唯一负责「加载哪些、加载成没成、怎么全部关掉」。
 *
 * 加载策略三条：
 * 1. **安全模式短路**：熔断后一个都不加载（[ExtensionSafetyGuard]）；
 * 2. **失败隔离**：单个扩展加载/onLoad 失败只记日志并跳过，不影响其它扩展与宿主启动；
 * 3. **全量卸载**：停用 / 移除 / 熔断时把所有注册（SPI/Hook/UI/启动管线）一并撤掉，
 *    否则会留下指向已卸载扩展的悬空回调 —— 那是崩溃的常见来源。
 */
object ExtensionEngine {

    private val loaded = ConcurrentHashMap<String, DshExtension>()

    /** 本次进程是否已经 bootstrap 过（宿主 onCreate 只该调一次）。 */
    @Volatile
    private var bootstrapped = false

    /** 已加载的扩展 id（界面/诊断用）。 */
    val loadedIds: Set<String> get() = loaded.keys.toSet()

    /**
     * 启动时加载所有「已启用且没被熔断」的扩展。
     *
     * 由 APatchApp.onCreate 在 [ExtensionSafetyGuard.checkAndEnforce] 之后调用。
     * 全程不抛异常：扩展系统坏掉也不能让 App 起不来。
     */
    fun bootstrap(ctx: Context) {
        if (bootstrapped) return
        bootstrapped = true
        runCatching {
            // 诊断日志：扩展「装了但没生效」时，这几行是唯一能区分
            // 「没启用 / 熔断拦了 / 加载失败 / 加载了但界面没接」的依据。
            val all = ExtensionRegistry.listInstalled(ctx)
            ExtLog.info(
                "bootstrap：已安装 " + all.size + " 个（" +
                    all.joinToString { it.id + (if (it.enabled) "=启用" else "=停用") } + "）",
            )
            if (ExtensionSafetyGuard.isSafeMode(ctx)) {
                // 只进 logcat：dsh.log 属于 dsh 运行时自己的启动日志，用户会去读它
                ExtLog.log(
                    "安全模式，跳过全部第三方扩展；原因=" +
                        ExtensionSafetyGuard.reason.ifEmpty {
                            ExtensionSafetyGuard.findKillSwitch(ctx)?.absolutePath ?: "(未知)"
                        },
                )
                return
            }
            val records = all.filter { it.enabled && it.installed }
            if (records.isEmpty()) {
                ExtLog.info("没有启用的扩展")
                ExtensionBootPipeline.markBootstrapped(ctx)
                return
            }
            for (rec in records) {
                val ext = ExtensionLoader.boot(ctx, rec) ?: continue
                loaded[rec.id] = ext
            }
            ExtensionBootPipeline.markBootstrapped(ctx)
            ExtLog.info("已加载 " + loaded.size + " 个扩展")
            // 启动管线第一段就在此时跑（APP_CREATE 阶段）
            ExtensionBootPipeline.run(ExtensionBootPipeline.Stage.APP_CREATE)
        }.onFailure {
            ExtLog.log("bootstrap 异常（已忽略）：" + (it.message ?: it.javaClass.simpleName))
        }
    }

    /** 卸载单个扩展（停用/移除时先调它，再改注册表）。 */
    fun unload(ctx: Context, id: String) {
        runCatching {
            loaded.remove(id)?.let { ext ->
                runCatching { ext.onUnload() }.onFailure {
                    ExtLog.log("[" + id + "] onUnload 抛异常（已忽略）")
                }
            }
            ExtensionServiceRegistry.unregisterAll(id)
            ExtensionHookRegistry.unregisterAll(id)
            ExtensionUiRegistry.unregisterAll(id)
            ExtensionBootPipeline.unregisterAll(id)
            ExtLog.info("已卸载：" + id)
        }
    }

    /** 卸载全部（安全模式 / 用户一键禁用时用）。 */
    fun unloadAll(ctx: Context) {
        for (id in loaded.keys.toList()) unload(ctx, id)
        ExtensionServiceRegistry.clearAll()
        ExtensionHookRegistry.clearAll()
        ExtensionUiRegistry.clearAll()
        ExtensionBootPipeline.clearAll()
    }

    /**
     * 用户点了「禁用全部扩展并重启」/ 崩溃页自救按钮。
     *
     * 先落标志文件（保证下次冷启动仍然安全），再卸载内存里的全部扩展。
     */
    fun disableAllAndLatch(ctx: Context, why: String) {
        runCatching {
            ExtensionSafetyGuard.latch(ctx, why, writeMarker = true)
            unloadAll(ctx)
        }
    }

    /** 重新启用：清掉标志文件，等下次冷启动重新加载（运行期不做热加载 —— 会引入状态不一致）。 */
    fun reEnable(ctx: Context) {
        ExtensionSafetyGuard.clearSafeMode(ctx)
        ExtLog.info("已允许扩展，重启 App 后生效")
    }
}
