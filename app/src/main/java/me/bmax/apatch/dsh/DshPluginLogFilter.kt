package me.bmax.apatch.dsh

/**
 * 把 `dsh plugin …` 的原始输出过滤成「给人看的那一份」。
 *
 * 为什么需要它：pnpm 每装一个插件都会打一大段 peer 依赖 WARN（`missing peer
 * @deepseek-ai/cordis`、`react`、`@deepseek-ai/dsh-client-*` ……）以及 `Progress:`
 * 刷屏，而**那些 peer 本来就该是缺的** —— 它们由 dsh 运行时在组合层提供，不在 profile
 * 的 node_modules 里（上游自己也这么写）。原样铺到界面上，一次预装就是几百行红字，
 * 用户只会以为装坏了。
 *
 * 三条硬规矩：
 * 1. 只过滤**给界面看的流**；[DshPluginRepo.dshPlugin] 的返回值（原始输出）一个字不动 ——
 *    `repairIfLinkageBroken` 之类要靠它解析。
 * 2. 错误绝不静默：`ERR`、堆栈、失败原因一律原样放过。
 * 3. 退出码非 0 时不能让 `[DSH-Folk-exit] 1` 这种内部标记当唯一的失败线索 ——
 *    这里把它记进 [Summary.exitCode]，由调用方翻成一句人话。
 *
 * 这个类不持有 Context，也不产出面向用户的文案（文案由调用方用资源串拼），
 * 所以它是一条纯逻辑，能单独验。
 */
internal class DshPluginLogFilter {
    /** 收尾时要报给界面的东西。 */
    data class Summary(
        /** `dependencies: + a、b` 里那串包名（可能为空）。 */
        val packages: String,
        /** `Done in 3.4s` 里的耗时（可能为空）。 */
        val duration: String,
        /** 这次输出里有没有被折叠掉的 peer 依赖块。 */
        val peerBlockFolded: Boolean,
        /** dsh plugin 的退出码（缺标记时为 0）。 */
        val exitCode: Int,
    ) {
        val hasSomething: Boolean
            get() = packages.isNotEmpty() || exitCode != 0 || peerBlockFolded
    }

    private var inPeerBlock = false
    private var peerBlockFolded = false
    private val dependencyLines = mutableListOf<String>()
    private var doneLine = ""
    private var exitCode = 0

    /** 一条原始输出行；该显示的交回 [emit]。 */
    fun accept(line: String, emit: (String) -> Unit) {
        val trimmed = line.trim()
        when {
            trimmed.contains(PEER_HEAD) -> {
                inPeerBlock = true
                peerBlockFolded = true
            }
            inPeerBlock -> {
                // 块内一直丢，直到撞上正常的段首（那时这一行要按普通行处理）
                if (isBlockEnd(trimmed)) {
                    inPeerBlock = false
                    accept(line, emit)
                }
            }
            trimmed.startsWith("Progress:") -> Unit
            trimmed.startsWith(DshPluginRepo.EXIT_MARKER) -> {
                exitCode = trimmed.removePrefix(DshPluginRepo.EXIT_MARKER).trim().toIntOrNull() ?: 0
            }
            trimmed.startsWith("dependencies:") -> {
                dependencyLines += trimmed.removePrefix("dependencies:").trim()
            }
            trimmed.startsWith("Done in") -> {
                doneLine = trimmed.removePrefix("Done in").trim().removeSuffix(".")
            }
            trimmed.isEmpty() -> Unit
            else -> emit(line)
        }
    }

    /** 收尾：交出摘要（调用方翻成文案）。 */
    fun finish(): Summary = Summary(
        packages = dependencyLines.joinToString("、"),
        duration = doneLine,
        peerBlockFolded = peerBlockFolded,
        exitCode = exitCode,
    )

    private fun isBlockEnd(trimmed: String): Boolean =
        trimmed.isEmpty() ||
            trimmed.startsWith("dependencies:") ||
            trimmed.startsWith("Progress:") ||
            trimmed.startsWith("Done in") ||
            trimmed.startsWith("dsh:")

    private companion object {
        const val PEER_HEAD = "Issues with peer dependencies found"
    }
}
