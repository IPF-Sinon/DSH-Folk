package me.bmax.apatch.dsh

/**
 * 工作流定义的**校验器**：把一份可能失控的 JSON 收敛成「宿主敢执行」的东西。
 *
 * ## 为什么校验这么重
 *
 * 这份定义是容器里的 AI 写的，而它接下来会以**用户的批准**为名、在容器里连着跑一长串
 * 命令。批准弹窗给用户看的是流程图 —— 如果校验器放过了环、放过了指向不存在节点的边、
 * 或者放过了 condition 节点上写死的值，那么「图上画的东西」和「实际会执行的东西」就会
 * 分叉，批准也就失去了意义。
 *
 * 所以这里的规则都是**能读懂**这一条服务的：类型白名单、按类型限定的键、唯一的入口、
 * 无环、可达、条件分支必须成对。看不懂的定义一律拒绝，并指出是哪一条、哪个节点。
 *
 * 校验**不执行任何东西**，也不访问设备：它是纯函数，便于单测与反向验证。
 */
internal object WorkflowValidator {

    /** 校验结果：errors 是「为什么不行」的人类可读短句（会原样回给 AI）。 */
    data class Result(val ok: Boolean, val errors: List<String>) {
        companion object {
            val OK = Result(true, emptyList())
            fun fail(vararg msg: String) = Result(false, msg.toList())
        }
    }

    /** 运行期注入的变量名，工作流不能自己覆盖它们。 */
    val RESERVED_VARS = setOf(
        "run_id", "workflow_id", "workflow_name", "last_output", "dsh_port", "dsh_url", "dsh_state",
    )

    private val ID_RE = Regex("^[A-Za-z][A-Za-z0-9_-]{0,31}$")
    private val VAR_RE = Regex("^[A-Za-z][A-Za-z0-9_]{0,31}$")

    /**
     * @param allowedCaps 允许声明的能力 id 集合（来自 [DshNativeBridge.Cap]）。传空集合表示
     *   这一版还不支持声明能力，任何非空 declaredCaps 都会被拒。
     */
    /**
     * 只查环：给**编辑页连线时**做即时判断用。
     *
     * 为什么不复用 [validate]：那条路要求整份定义都合法（节点 id、config、能力声明…），
     * 而用户正在编辑的中间状态**天然不合法**（比如刚建好还没填命令的节点）。连线时只需要回答
     * 「这条边会不会成环」，用整份校验会把「还没填完」误报成错误，反而挡住正在做的事。
     *
     * @return 成环时返回环上的节点序列（含首尾重复的那个），否则 null。
     */
    fun cycleIn(def: WorkflowDefinition): List<String>? =
        findCycle(def).takeIf { it.isNotEmpty() }?.let {
            // findCycle 返回的是 ["存在环：a -> b -> a"] 这种可读文案；这里把环拆回序列，
            // 让调用方能自己决定怎么呈现（编辑页只需要判断真假 + 高亮节点）。
            it.first().removePrefix("存在环：").split(" -> ")
        }

    /**
     * 把一条边加进定义，**前提是它不会成环**。
     *
     * 为什么要有这个函数而不是让编辑页各自判断：连边有三条入口（从针脚拉出落在卡片上、
     * 落空白后从菜单建节点并连、以及 addNode 的 afterId 路径）。只要有一条忘了判，环就会
     * 从那里漏进去 —— 而环一旦落盘，执行器的拓扑调度会死循环。所以判据只有这一处。
     *
     * @return 加边后的定义；成环则返回原定义（不改动），由调用方给用户反馈。
     */
    fun addEdgeIfAcyclic(def: WorkflowDefinition, fromId: String, toId: String, whenExpr: String = ""): WorkflowDefinition {
        if (fromId == toId) return def
        if (def.edges.any { it.from == fromId && it.to == toId && it.whenExpr == whenExpr }) return def
        val next = def.copy(edges = def.edges + WorkflowEdge(fromId, toId, whenExpr))
        return if (cycleIn(next) != null) def else next
    }

    fun validate(def: WorkflowDefinition, allowedCaps: Set<String>): Result {
        val errors = mutableListOf<String>()

        if (def.schemaVersion != WorkflowSchema.SCHEMA_VERSION) {
            errors += "schemaVersion 只支持 ${WorkflowSchema.SCHEMA_VERSION}，收到 ${def.schemaVersion}"
        }
        if (def.name.isBlank()) errors += "name 不能为空"
        if (def.name.length > WorkflowSchema.MAX_NAME_CHARS) {
            errors += "name 过长（> ${WorkflowSchema.MAX_NAME_CHARS}）"
        }
        if (def.nodes.isEmpty()) errors += "至少要有一个节点"
        if (def.nodes.size > WorkflowSchema.MAX_NODES) {
            errors += "节点过多（${def.nodes.size} > ${WorkflowSchema.MAX_NODES}）"
        }
        if (def.edges.size > WorkflowSchema.MAX_EDGES) {
            errors += "连线过多（${def.edges.size} > ${WorkflowSchema.MAX_EDGES}）"
        }

        // ── 节点自身 ──
        val seen = mutableSetOf<String>()
        for (n in def.nodes) {
            if (!ID_RE.matches(n.id)) {
                errors += "节点 id「${n.id}」不合法（字母开头，字母数字_-，≤ ${WorkflowSchema.MAX_ID_CHARS}）"
                continue
            }
            if (!seen.add(n.id)) errors += "节点 id 重复：${n.id}"
            if (n.timeoutMs < WorkflowSchema.MIN_TIMEOUT_MS || n.timeoutMs > WorkflowSchema.MAX_TIMEOUT_MS) {
                errors += "节点 ${n.id} 的 timeoutMs 越界（${n.timeoutMs}，允许 ${WorkflowSchema.MIN_TIMEOUT_MS}..${WorkflowSchema.MAX_TIMEOUT_MS}）"
            }
            errors += validateConfig(n)
        }

        // ── 入口 ──
        val triggers = def.nodes.filter { it.type == WorkflowNodeType.TRIGGER }
        if (triggers.size != 1) {
            errors += "必须且只能有一个 trigger 节点（现在 ${triggers.size} 个）"
        }
        if (def.nodes.none { it.type != WorkflowNodeType.TRIGGER }) {
            errors += "除了 trigger 之外没有任何节点：这个工作流什么都不做"
        }

        // ── 边 ──
        val ids = def.nodes.map { it.id }.toSet()
        val edges = mutableSetOf<String>()
        for (e in def.edges) {
            if (e.from !in ids) errors += "连线起点不存在：${e.from}"
            if (e.to !in ids) errors += "连线终点不存在：${e.to}"
            if (e.from == e.to) errors += "节点不能连到自己：${e.from}"
            if (!edges.add("${e.from}->${e.to}")) errors += "重复连线：${e.from} -> ${e.to}"
            val src = def.node(e.from)
            val wantBranch = e.whenExpr.isNotBlank()
            val branching = branchingNode(src)
            if (wantBranch && !branching) {
                errors += "只有 condition / logic（以及声明了 requireDsh 的 trigger）的出边能带 when，节点 ${e.from} 不行（值「${e.whenExpr}」）"
            }
            if (wantBranch && e.whenExpr != "true" && e.whenExpr != "false") {
                errors += "when 只认 true / false，收到「${e.whenExpr}」（${e.from} -> ${e.to}）"
            }
        }
        for (n in def.nodes) {
            if (branchingNode(n)) {
                val out = def.outgoing(n.id)
                val labelled = out.count { it.whenExpr == "true" || it.whenExpr == "false" }
                // condition / logic 必须有成对分支；trigger 只在「画了分支」时才要求成对 ——
                // 没画分支的 trigger（requireDsh 起不来）就是整个运行失败，这是合法写法。
                val mustPair = n.type == WorkflowNodeType.CONDITION ||
                    n.type == WorkflowNodeType.LOGIC ||
                    labelled > 0
                if (mustPair && (out.none { it.whenExpr == "true" } || out.none { it.whenExpr == "false" })) {
                    errors += "节点 ${n.id} 是判断节点，必须各有 true / false 一条出边"
                }
            }
        }

        // ── 结构：无环 + 从 trigger 可达 ──
        if (triggers.size == 1 && errors.none { it.startsWith("节点 id") }) {
            val entry = triggers.first().id
            if (def.edges.any { it.to == entry }) errors += "trigger 节点不能有入边（${entry}）"
            errors += findCycle(def)
            val reachable = reachableFrom(def, entry)
            for (n in def.nodes) {
                if (n.id !in reachable) errors += "节点 ${n.id} 从 trigger 出发到不了（顶层断链）"
            }
        }

        // ── 能力声明 ──
        for (cap in def.declaredCaps) {
            if (cap !in allowedCaps) errors += "声明的能力不认识或不可用：$cap"
        }

        return Result(errors.isEmpty(), errors)
    }

    /** 按类型检查 config 的必填键与取值。 */    private fun validateConfig(n: WorkflowNode): List<String> {
        val out = mutableListOf<String>()
        fun need(key: String): String? {
            val v = n.config(key)
            if (v.isBlank()) out += "节点 ${n.id}（${n.type.id}）缺少 $key"
            return v.takeIf { it.isNotBlank() }
        }
        fun bounded(key: String, max: Int = WorkflowSchema.MAX_VALUE_CHARS) {
            val v = n.config(key)
            if (v.length > max) out += "节点 ${n.id} 的 $key 过长（> $max）"
        }
        fun bool(key: String) {
            val v = n.config(key)
            if (v.isNotBlank() && v != "1" && v != "0" && v != "true" && v != "false") {
                out += "节点 ${n.id} 的 $key 只能是 1/0（收到「$v」）"
            }
        }
        when (n.type) {
            WorkflowNodeType.TRIGGER -> {
                bool("autostartDsh")
                bool("requireDsh")
            }

            WorkflowNodeType.EXECUTE -> {
                val action = WorkflowActionType.of(n.config("action"))
                if (action == null) {
                    out += "节点 ${n.id} 的 action 不是 shell / dsh / notify（收到「${n.config("action")}」）"
                } else when (action) {
                    WorkflowActionType.SHELL, WorkflowActionType.DSH -> {
                        need("cmd")
                        bounded("cmd", WorkflowSchema.MAX_CMD_CHARS)
                    }
                    WorkflowActionType.NOTIFY -> {
                        need("text")
                        bounded("text", WorkflowSchema.MAX_VALUE_CHARS)
                    }
                }
            }

            WorkflowNodeType.CONDITION -> {
                val source = need("source") ?: ""
                val op = need("op") ?: ""
                when (op) {
                    "contains", "equals", "matches", "exists", "empty" -> Unit
                    "" -> Unit
                    else -> out += "节点 ${n.id} 的 op 不认识：$op"
                }
                if (source.startsWith("step:")) {
                    val ref = source.removePrefix("step:")
                    if (n.id == ref) out += "节点 ${n.id} 不能判断自己的输出"
                }
                if (op != "exists" && op != "empty") need("value")
                if (op == "matches" && n.config("value").isNotBlank()) {
                    runCatching { Regex(n.config("value")) }.onFailure {
                        out += "节点 ${n.id} 的 value 不是合法正则：${it.message}"
                    }
                }
            }

            WorkflowNodeType.LOGIC -> {
                val op = need("op") ?: ""
                if (op !in setOf("and", "or", "not")) out += "节点 ${n.id} 的 op 只能是 and / or / not"
                val srcs = n.config("sources").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (srcs.isEmpty()) out += "节点 ${n.id} 缺少 sources"
                if (srcs.size > 8) out += "节点 ${n.id} 的 sources 过多（> 8）"
                if (op == "not" && srcs.size != 1) out += "节点 ${n.id} 的 not 只接受一个 source"
            }

            WorkflowNodeType.EXTRACT -> {
                val source = need("source") ?: ""
                val mode = need("mode") ?: ""
                val varName = need("var") ?: ""
                if (mode !in setOf("json", "regex", "text")) {
                    out += "节点 ${n.id} 的 mode 只能是 json / regex / text"
                }
                if (mode == "json") need("path")
                if (mode == "regex") need("pattern").also {
                    if (n.config("pattern").isNotBlank()) {
                        runCatching { Regex(n.config("pattern")) }.onFailure {
                            out += "节点 ${n.id} 的 pattern 不是合法正则：${it.message}"
                        }
                    }
                }
                if (source.startsWith("step:")) {
                    val ref = source.removePrefix("step:")
                    if (n.id == ref) out += "节点 ${n.id} 不能从自己的输出取值"
                }
                if (varName.isNotBlank()) {
                    if (!VAR_RE.matches(varName)) out += "节点 ${n.id} 的 var「$varName」不是合法变量名"
                    if (varName in RESERVED_VARS) out += "节点 ${n.id} 的 var「$varName」是保留名"
                }
            }

            WorkflowNodeType.WAIT -> {
                val ms = n.config("ms")
                val until = n.config("untilDsh")
                if (ms.isBlank() && until.isBlank()) {
                    out += "节点 ${n.id}（wait）需要 ms 或 untilDsh"
                }
                if (ms.isNotBlank()) {
                    val v = ms.toLongOrNull()
                    when {
                        v == null -> out += "节点 ${n.id} 的 ms 不是数字：$ms"
                        v <= 0 -> out += "节点 ${n.id} 的 ms 必须为正"
                        v > WorkflowSchema.MAX_WAIT_MS -> out += "节点 ${n.id} 的 ms 越界（> ${WorkflowSchema.MAX_WAIT_MS}）"
                    }
                }
                if (until.isNotBlank() && until != "running" && until != "stopped") {
                    out += "节点 ${n.id} 的 untilDsh 只能是 running / stopped"
                }
            }
        }
        return out
    }

    /**
     * 这个节点会不会给出 true / false 两条分支。
     *
     * condition / logic 天然是；trigger 在声明 `requireDsh` 时也算 —— 「DSH 起不来」是它唯一
     * 能给出的否定结论，用户要的「未启动 DSH 时可条件判断进入其它流程」正是靠那条 false 边
     * 表达的（没画那条边就等价于整个运行失败）。
     */
    fun branchingNode(node: WorkflowNode?): Boolean = when (node?.type) {
        WorkflowNodeType.CONDITION, WorkflowNodeType.LOGIC -> true
        WorkflowNodeType.TRIGGER -> node.config("requireDsh").trim().lowercase() in
            setOf("1", "true", "yes", "on")
        else -> false
    }

    /** 找一条环（有则报出来）。DFS 三色法：白=没碰过，灰=在当前递归栈里，黑=已完成。 */
    private fun findCycle(def: WorkflowDefinition): List<String> {
        val adj = def.nodes.associate { it.id to def.outgoing(it.id).map { e -> e.to } }
        val state = mutableMapOf<String, Int>()
        val stack = mutableListOf<String>()
        var found: List<String>? = null

        fun dfs(id: String) {
            if (found != null) return
            state[id] = 1
            stack += id
            for (next in adj[id].orEmpty()) {
                when (state[next] ?: 0) {
                    1 -> {
                        val at = stack.indexOf(next)
                        found = stack.drop(at) + next
                        return
                    }
                    0 -> dfs(next)
                }
                if (found != null) return
            }
            stack.removeAt(stack.lastIndex)
            state[id] = 2
        }

        for (n in def.nodes) if ((state[n.id] ?: 0) == 0) dfs(n.id)
        return found?.let { listOf("存在环：" + it.joinToString(" -> ")) } ?: emptyList()
    }

    /** 从入口顺着边能走到的节点集合。 */
    fun reachableFrom(def: WorkflowDefinition, entry: String): Set<String> {
        val seen = mutableSetOf(entry)
        val queue = ArrayDeque(listOf(entry))
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (e in def.outgoing(cur)) {
                if (seen.add(e.to)) queue += e.to
            }
        }
        return seen
    }

    /**
     * 下一步该走哪条边。
     *
     * 判据顺序：先找 `when` 与 [branch] 相符的那条（判断节点用），再退回默认边（空 when）。
     * 判断节点没有对应分支的边时返回 null —— **不猜**：猜一条边继续跑，用户看到的执行路径
     * 就会和流程图不一样。
     */
    fun nextNode(def: WorkflowDefinition, from: String, branch: Boolean?): String? {
        val out = def.outgoing(from)
        if (branch != null) {
            out.firstOrNull { it.whenExpr == if (branch) "true" else "false" }?.let { return it.to }
        }
        return out.firstOrNull { it.whenExpr.isBlank() }?.to
    }
}
