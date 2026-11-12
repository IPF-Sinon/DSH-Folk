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

    /**
     * 加一条数据边，**前提是它不会自我引用**。
     *
     * 与 [addEdgeIfAcyclic] 分开是因为判据不同：数据边不参与控制流，所以"成环"对它
     * 不成立（它只从上游流向下游），真正会出问题的是**同一个节点把值喂给自己** ——
     * 那一步里"上游的值"和"本节点的参数"是同一个东西，永远算不出来。
     *
     * 已有同名同引脚的数据边时直接返回原定义：重复连一条线在界面上看不出差别，
     * 但校验器会报"重复的数据边"，用户却找不到是哪两条。
     */
    /**
     * 接一根数据线。**输入端是单线**：新线接上就把同一目标针脚上的旧线顶掉。
     *
     * 对齐参考实现 `Pin.addLink` 里的 `if (isSingleLink()) clearLinks(task)` ——
     * 它接新线前先把自己这个针脚上所有旧线清干净。
     *
     * 为什么必须清：一个参数同时被两根线喂，取值就成了"谁最后到算谁的"，
     * 用户看到的接线图和实际跑出来的结果会是两回事。
     *
     * 另外这里**拒掉类型不匹配**的接线（[WorkflowPinType.accepts]）。参考实现的
     * linkAble 在拖动结束前就判掉了，接不上根本不会建立连接；这里在数据层再判一次，
     * 因为 AI 也能直接改 JSON 提交定义。
     */
    /**
     * 语义是「多个来源串起来」的参数键（与 WorkflowEngine.MULTI_SOURCE_PINS 同一套）。
     *
     * 两处必须一致：这里决定"接新线时清不清旧线"，引擎那边决定"写回时覆盖还是追加"。
     * 只改一边的后果是静默的 —— 界面上线还在，跑起来却只有一个来源参与运算。
     */
    private val MULTI_SOURCE_PINS = setOf("sources")

    fun addDataEdge(
        def: WorkflowDefinition,
        fromId: String,
        fromPin: String,
        toId: String,
        toPin: String,
        dataType: String = "string",
    ): WorkflowDefinition {
        if (fromId == toId) return def
        if (fromPin.isBlank() || toPin.isBlank()) return def

        val fromSpec = WorkflowSchema.pinsOf(def, fromId).firstOrNull { it.id == fromPin }
        val toSpec = WorkflowSchema.pinsOf(def, toId).firstOrNull { it.id == toPin }
        // 认不出针脚的两端不连线：宁可什么都不做，也不建立一条运行期解不开的线。
        if (fromSpec != null && toSpec != null && !toSpec.canLinkFrom(fromSpec)) return def

        val dup = def.edges.any {
            it.kind == WorkflowEdgeKind.DATA && it.from == fromId && it.to == toId &&
                it.fromPin == fromPin && it.toPin == toPin
        }
        if (dup) return def

        // 输入端单线：清掉同一目标针脚上的旧线（控制流线不动）。
        //
        // **例外：多来源参数不清**。`logic.sources` 的语义就是"多个来源串起来"，
        // 接到它上面的每根线都要保留；照单线清掉的话，接第二根时第一根消失，
        // 用户永远只能接一个来源。与引擎侧 applyDataEdges 的追加分支是同一套规则。
        val multiSource = toPin in MULTI_SOURCE_PINS
        val withoutOld = if (multiSource) def.edges else def.edges.filterNot {
            it.kind == WorkflowEdgeKind.DATA && it.to == toId && it.toPin == toPin
        }

        val edge = WorkflowEdge(
            from = fromId,
            to = toId,
            whenExpr = "",
            kind = WorkflowEdgeKind.DATA,
            fromPin = fromPin,
            toPin = toPin,
            dataType = dataType,
        )
        return def.copy(edges = withoutOld + edge)
    }


    fun validate(def: WorkflowDefinition, allowedCaps: Set<String>): Result {
        val errors = mutableListOf<String>()

        // 版本：v3 起 MIN_SUPPORTED == SCHEMA_VERSION（接线模型重写，旧流程不兼容），
        // 所以"更老的版本"不再被 migrateToCurrent 抬上来，而是走到这里被判失效。
        //
        // 措辞要**说出人话**：用户看到的不是"版本号不对"，而是"这是旧格式，已失效"，
        // 并且知道文件没被删。AI 侧同样需要这句，否则它会以为只是参数写错了。
        if (def.schemaVersion < WorkflowSchema.MIN_SUPPORTED_VERSION) {
            errors += "流程定义是旧格式（v${def.schemaVersion}），已失效，不能再运行或编辑；" +
                "文件仍保留，可导出备份。当前只支持 v${WorkflowSchema.SCHEMA_VERSION} " +
                "（参数已改为可接线的针脚模型）。"
        } else if (def.schemaVersion > WorkflowSchema.SCHEMA_VERSION) {
            errors += "schemaVersion 比当前版本更新（收到 ${def.schemaVersion}，" +
                "当前 ${WorkflowSchema.SCHEMA_VERSION}），本版本无法执行"
        }
        // 流程内变量：名字必须是合法的 ${...} 键，且不能遮蔽系统变量。
        //
        // 遮蔽是最坑的一种：用户声明一个 dsh_port，运行期 seedVariables 里系统值会赢，
        // 于是"我明明填了却没生效"，校验器不报就没人知道原因。
        val declaredVars = def.variables.keys
        for (v in declaredVars) {
            if (!v.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                errors += "变量名「$v」不合法（字母/数字/下划线，且不以数字开头）"
            }
            if (v in WorkflowSchema.SYSTEM_VARIABLES) {
                errors += "变量名「$v」与系统变量同名，运行期会被系统值覆盖（换个名字）"
            }
        }
        if (declaredVars.size > WorkflowSchema.MAX_VARIABLES) {
            errors += "变量过多（${declaredVars.size} > ${WorkflowSchema.MAX_VARIABLES}）"
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

        // ── 边：控制流与数据边分开查 ──
        //
        // 两者要求完全不同，混在一个循环里会互相污染判据：数据边**不允许**带 when
        // （它不决定走哪条路），而控制流边不看引脚。放在同一处的话，一条带 when 的数据边
        // 会被当成"非判断节点却带分支"报错，而它其实只是想把值喂进去。
        val ids = def.nodes.map { it.id }.toSet()
        val flowKeys = mutableSetOf<String>()
        val dataKeys = mutableSetOf<String>()
        for (e in def.edges) {
            if (e.from !in ids) errors += "连线起点不存在：${e.from}"
            if (e.to !in ids) errors += "连线终点不存在：${e.to}"
            if (e.from == e.to) errors += "节点不能连到自己：${e.from}"
            if (e.kind == WorkflowEdgeKind.DATA) {
                if (e.whenExpr.isNotBlank()) {
                    errors += "数据边不能带 when（${e.from} -> ${e.to}）"
                }
                if (e.fromPin.isBlank() || e.toPin.isBlank()) {
                    errors += "数据边必须写明两端的引脚（${e.from} -> ${e.to}）"
                }
                if (!dataKeys.add("${e.from}:${e.fromPin}->${e.to}:${e.toPin}")) {
                    errors += "重复的数据边：${e.from}:${e.fromPin} -> ${e.to}:${e.toPin}"
                }
                errors += validateDataWiring(def, e)
            } else {
                if (!flowKeys.add("${e.from}->${e.to}")) errors += "重复连线：${e.from} -> ${e.to}"
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
        //
        // 查的是**实际会用到的**能力（手工声明 + 动作目录推出来的），不是只查手工那一份：
        // 只查前者的话，一个只用界面上那些卡片的流程可以一项都不声明，而它明明要用无障碍。
        for (cap in def.effectiveCaps) {
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
                    WorkflowActionType.NATIVE -> {
                        // 动作必须**在目录里**：bridge 是执行期唯一的入口，写错了就是运行时
                        // 才发现「这个节点什么都不做」。必填参数同样在这里点出来 ——
                        // 等到运行时才报 400，用户看到的是日志里一行 http_400。
                        val spec = WorkflowActionCatalog.byId(n.config("bridge"))
                        if (spec == null) {
                            out += "节点 ${n.id} 的 bridge 动作不存在：${n.config("bridge")}"
                        } else {
                            for (p in spec.params) {
                                val v = n.config(spec.configKey(p))
                                if (p.required && v.isBlank()) {
                                    out += "节点 ${n.id} 缺少动作「${spec.id}」的参数 ${p.key}"
                                }
                                if (p.kind == WorkflowActionCatalog.ParamKind.CHOICE &&
                                    v.isNotBlank() && p.choices.isNotEmpty() && v !in p.choices
                                ) {
                                    out += "节点 ${n.id} 的参数 ${p.key} 只能是 ${p.choices.joinToString(" / ")}"
                                }
                                if (p.kind == WorkflowActionCatalog.ParamKind.NUMBER && v.isNotBlank() &&
                                    v.toLongOrNull() == null
                                ) {
                                    out += "节点 ${n.id} 的参数 ${p.key} 不是数字：$v"
                                }
                            }
                        }
                    }

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
     * 一条数据边的两端是否成立。
     *
     * 数据边的语义是「上游的某个值 → 下游的某个参数」，所以两端各有约束：
     *
     * - **起点**：`<nodeId>` 或 `step:<nodeId>` 两种写法都认（画布上从卡片拉出来时用的是
     *   前者，手写定义更接近后者）。必须指向**当前定义里存在**的节点 —— 指向不存在的节点
     *   在执行期只会得到空串，而空串是合法的参数值，于是这类错误会完全静默。
     * - **终点**：`toPin` 必须是下游节点**真的有的那个参数键**（见 [dataInputKeys]）。
     *   写错了同样是静默的：值被塞进一个谁也不读的键。
     *
     * 另外，数据边**不能成环**：A 的值喂给 B、B 的值再喂回 A，执行顺序上无法同时满足。
     * 这一条用不着 DFS —— 数据边只连"上游 → 下游"，只要不出现自我引用就够了；
     * 真正的环由控制流的 findCycle 负责。
     */
    private fun validateDataWiring(def: WorkflowDefinition, e: WorkflowEdge): List<String> {
        val out = mutableListOf<String>()
        val srcId = e.fromPin.removePrefix("step:").removePrefix("var:")
        val known = e.fromPin.startsWith("var:") || e.fromPin.removePrefix("step:") in def.nodes.map { it.id }
        if (!known) {
            out += "数据边的起点「${e.fromPin}」不在定义里（应当是本流程的节点 id 或 var:<名字>）"
        }
        if (e.fromPin.removePrefix("step:") == e.to) {
            out += "节点 ${e.to} 不能把自己的值喂给自己（${e.fromPin} -> ${e.toPin}）"
        }
        if (srcId.isNotBlank() && srcId == e.to) {
            out += "数据边回到了自己：${e.to}"
        }
        val target = def.node(e.to)
        if (target != null) {
            val keys = WorkflowSchema.dataInputKeysOf(target)
            if (e.toPin !in keys) {
                out += "节点 ${e.to} 没有参数「${e.toPin}」（可用：${keys.joinToString(" / ").ifBlank { "无" }}）"
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
