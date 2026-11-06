package me.bmax.apatch.dsh.ext

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 方法级拦截总线（Before / After / Around）。
 *
 * 三条约束都要守住：
 * 1. **没注册时零开销**：宿主热路径（文件桥每个请求、每次原生命令）都会经过这里，
 *    所以只做一次 map 查空即返回，不分配对象；
 * 2. **异常隔离**：扩展 handler 抛异常只记日志并跳过本次，绝不冒泡进宿主主流程；
 * 3. **熔断短路**：安全模式下一律不调用任何扩展 handler。
 *
 * 拦截点用字符串常量（[Point]）：宿主调用点很多，用枚举会让新增点位变成破坏性改动。
 */
object ExtensionHookRegistry {

    /** 拦截点常量（按子系统前缀分组，便于日志与文档检索）。 */
    object Point {
        const val RUNTIME_BEFORE_START = "runtime.beforeStart"
        const val RUNTIME_BUILD_ARGV = "runtime.buildArgv"
        const val RUNTIME_APPLY_ENV = "runtime.applyEnv"
        const val RUNTIME_PROBE_READY = "runtime.probeReady"
        const val BRIDGE_BEFORE_DISPATCH = "bridge.beforeDispatch"
        const val BRIDGE_AUDIT = "bridge.audit"
        const val STORAGE_CHECK_PATH = "storage.checkPathAllowed"
        const val NATIVE_BEFORE_INVOKE = "native.beforeInvoke"
        const val NATIVE_AFTER_INVOKE = "native.afterInvoke"
        const val SHELL_BEFORE_EXEC = "shell.beforeExec"
        const val SHELL_AFTER_EXEC = "shell.afterExec"
        const val A11Y_DUMP_TREE = "a11y.dumpTree"
        const val DISPLAY_START_SESSION = "display.startSession"
        const val WEBUI_CONFIGURE = "webui.onConfigureWebView"
        const val USERSCRIPTS_BUILD = "userscripts.buildInjections"
        const val PLUGIN_UPDATE = "plugins.update"
        const val WORKFLOW_EXECUTE_NODE = "workflow.executeNode"
        const val PROMPT_RENDER = "prompt.renderSystemPrompt"
        const val DIAGNOSTICS_REDACT = "diagnostics.redactText"
    }

    /** before/after 的统一签名：拿到一个可变的「上下文包」。 */
    fun interface Handler {
        fun run(ctx: MutableMap<String, Any?>)
    }

    /** around：可以改写入参并决定是否继续（返回 false = 拦下，宿主跳过默认实现）。 */
    interface Around {
        fun intercept(ctx: MutableMap<String, Any?>): Boolean
    }

    private val before = ConcurrentHashMap<String, CopyOnWriteArrayList<Pair<String, Handler>>>()
    private val after = ConcurrentHashMap<String, CopyOnWriteArrayList<Pair<String, Handler>>>()
    private val around = ConcurrentHashMap<String, CopyOnWriteArrayList<Pair<String, Around>>>()

    fun before(extensionId: String, point: String, handler: Handler) {
        if (ExtensionGate.isLatched()) return
        before.getOrPut(point) { CopyOnWriteArrayList() }.add(extensionId to handler)
    }

    fun after(extensionId: String, point: String, handler: Handler) {
        if (ExtensionGate.isLatched()) return
        after.getOrPut(point) { CopyOnWriteArrayList() }.add(extensionId to handler)
    }

    fun around(extensionId: String, point: String, interceptor: Around) {
        if (ExtensionGate.isLatched()) return
        around.getOrPut(point) { CopyOnWriteArrayList() }.add(extensionId to interceptor)
    }

    /** 是否有任何扩展关心这个点（宿主可用它跳过参数打包）。 */
    fun has(point: String): Boolean =
        before.containsKey(point) || after.containsKey(point) || around.containsKey(point)

    fun runBefore(point: String, payload: MutableMap<String, Any?>) {
        if (ExtensionGate.isLatched()) return
        val list = before[point] ?: return
        for ((id, handler) in list) {
            runCatching { handler.run(payload) }.onFailure { report(id, point, it) }
        }
    }

    fun runAfter(point: String, payload: MutableMap<String, Any?>) {
        if (ExtensionGate.isLatched()) return
        val list = after[point] ?: return
        for ((id, handler) in list) {
            runCatching { handler.run(payload) }.onFailure { report(id, point, it) }
        }
    }

    /** 跑 around 段。返回 false = 有扩展明确要求拦下。异常一律按「不拦」处理。 */
    fun runAround(point: String, payload: MutableMap<String, Any?>): Boolean {
        if (ExtensionGate.isLatched()) return true
        val list = around[point] ?: return true
        for ((id, interceptor) in list) {
            val proceed = runCatching { interceptor.intercept(payload) }.getOrElse {
                report(id, point, it)
                true
            }
            if (!proceed) return false
        }
        return true
    }

    fun unregisterAll(extensionId: String) {
        for (m in listOf(before, after)) {
            for (list in m.values) list.removeIf { it.first == extensionId }
        }
        for (list in around.values) list.removeIf { it.first == extensionId }
    }

    fun clearAll() {
        before.clear(); after.clear(); around.clear()
    }

    private fun report(id: String, point: String, e: Throwable) {
        ExtLog.log("[ext:" + id + "] hook " + point + " 抛异常（本次跳过）：" +
            (e.message ?: e.javaClass.simpleName))
    }
}
