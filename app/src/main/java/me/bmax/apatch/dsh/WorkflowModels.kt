package me.bmax.apatch.dsh

import org.json.JSONArray
import org.json.JSONObject

/**
 * 自动化工作流的**数据模型**（声明式，不是代码）。
 *
 * ## 为什么是数据而不是脚本
 *
 * 工作流由容器里的 AI 生成、由宿主 App 执行。如果定义里允许出现脚本，那么「用户点过一次
 * 批准」就等于「授予了任意执行权」—— 批准时给他看的流程图也就成了摆设（图上是三个节点，
 * 实际跑的是别的东西）。所以这里只允许**有限的节点类型 + 固定的 actionType**：校验器能把
 * 定义完整读懂，批准弹窗能把「它到底会做什么」如实画出来。
 *
 * ## 节点类型的出处
 *
 * `trigger / execute / condition / logic / extract` 这套划分参考 Operit 的工作流 API
 * （`Tools.Workflow` 的节点联合类型），`position` 同理 —— 蓝图式编辑器的摆位必须落盘，
 * 否则每次打开都由算法重排，用户刚摆好的图会自己跳。变量系统（`${name}` 插值 + `extract`
 * 写入）参考 TouchTool 的「全局/局部变量，实现数据传递」：没有它，前一步的输出只能靠
 * 「stdout 含不含某串」判断，做不了真正的数据传递。
 *
 * `wait` 是本仓自己加的一类：DSH 冷启动、容器里 apt/pnpm 这类「就是要等」的场合，写成
 * 一个节点比塞进 shell 里 `sleep` 更好 —— 前者可取消、可在状态查询里看见，后者只能干等。
 */
internal object WorkflowSchema {
    /**
     * 定义格式版本。改结构时用它做迁移，而不是猜。
     *
     * ## v1 → v2 变的是什么
     *
     * v2 给连线加了**种类**：`flow`（控制流，与原语义一致）与 `data`（数据边：把上游
     * 某个输出的值喂给下游某个参数）。v1 的每条边在语义上都是控制流，所以迁移就是
     * 「原样补上 `dataType = flow`」—— 没有任何一条 v1 定义的**行为**会改变。
     *
     * 为什么不把数据传递继续塞进 `config`（比如让下游写 `${step:x}` 自己引用）：
     * 那需要用户在字符串里手写引用，写错了只有运行时才知道；数据边是**看得见的一条线**，
     * 校验器也能在保存时就把「类型对不上 / 来源不存在」点出来。
     */
    /**
     * 当前定义格式版本。
     *
     * **3 = 接线模型重写**（参数从"节点上的一袋字符串"变成"带类型、可接线的针脚"）。
     * 2 及更早的定义一律判为旧格式并作废（用户明确"不必兼容旧流程，只要提醒已失效"）：
     * 它们没有针脚类型、没有 result 出口，读写规则也不一样，硬迁会把语义悄悄改掉。
     */
    /** 每个运行都现算、不允许用户遮蔽的变量名。 */
    val SYSTEM_VARIABLES = setOf(
        "run_id", "workflow_id", "workflow_name",
        "dsh_port", "dsh_url", "dsh_state",
    )

    /** 流程内变量个数上限（防一份定义塞几百个）。 */
    const val MAX_VARIABLES = 64

    const val SCHEMA_VERSION = 3

    /** 认得的最老版本：比它更老的定义不猜，直接判不合法。 */
    /**
     * 最低可执行版本。**抬到 3**：只有当前格式能跑。
     *
     * 旧流程的文件**不删**，只是标为失效 —— 用户还能看到它、导出它，
     * 但运行按钮点不动，AI 侧也会收到"该流程已失效"的明确结论。
     */
    const val MIN_SUPPORTED_VERSION = 3

    /** 硬上限：AI 生成的定义有可能失控，这些数字是「比这更离谱的一律拒绝」的线。 */
    const val MAX_NODES = 64
    const val MAX_EDGES = 256
    const val MAX_ID_CHARS = 32
    const val MAX_NAME_CHARS = 80
    const val MAX_CMD_CHARS = 8_192
    const val MAX_VALUE_CHARS = 4_096

    /** 单节点超时的上下限：1 秒 ~ 1 小时。 */
    const val MIN_TIMEOUT_MS = 1_000L
    const val MAX_TIMEOUT_MS = 3_600_000L

    /** `wait` 节点的上限：24 小时（再长就不该用一个节点等）。 */
    const val MAX_WAIT_MS = 86_400_000L

    /** 运行日志每条截断 + 单个运行保留的日志条数上限。 */
    const val MAX_LOG_LINE_CHARS = 4_096
    const val MAX_LOG_LINES = 5_000

    /** 一个节点保留的输出尾部（状态查询里回给 AI 的那一段）。 */
    const val OUTPUT_TAIL_CHARS = 8_192

    /**
     * 这个节点**真的会被读取**的参数键。
     *
     * 这是校验器与画布共用的唯一一份：数据边只能把值喂进这些键，别的键写了没人读，
     * 而"写了没人读"是静默失效 —— 用户看到一条线，运行时那个参数还是空的。
     *
     * 放在 DSL 层（而不是 UI 层）是因为**执行器**才是最终的读取方：
     * 界面允许编辑的字段可以比它多（例如 name/description 这类元数据），
     * 但数据边只能接到这些键上。
     */
    fun dataInputKeysOf(node: WorkflowNode): List<String> = when (node.type) {
        WorkflowNodeType.TRIGGER -> listOf("requireDsh", "autostartDsh")
        WorkflowNodeType.EXECUTE -> when (node.actionType) {
            WorkflowActionType.NATIVE ->
                WorkflowActionCatalog.byId(node.config("bridge"))
                    ?.params
                    ?.map { "p_" + it.key }
                    .orEmpty()
            WorkflowActionType.NOTIFY -> listOf("title", "text")
            // null = action 写错了，校验器已经会报；这里给 shell 的键让它至少能接上。
            else -> listOf("cmd")
        }
        WorkflowNodeType.CONDITION -> listOf("source", "op", "value")
        WorkflowNodeType.LOGIC -> listOf("op", "sources")
        WorkflowNodeType.EXTRACT -> listOf("source", "mode", "path", "pattern", "var")
        WorkflowNodeType.WAIT -> listOf("ms", "untilDsh")
    }

    /**
     * 按节点类型推导引脚表（画布 / 节点卡的渲染依据）。
     *
     * **只做展示**：不写回定义、不影响校验与执行、也不改变落盘格式 —— 引脚是「同一份 DSL
     * 的另一种画法」，不是第二套模型。规则来自 DSL 的既有语义：
     *  - trigger：只有出口；`requireDsh=1` 时它本来就是「DSH 起不来走假」，所以给真假两条。
     *  - execute：入口 + 出口 + 参数（shell/dsh 是 `cmd`，notify 是 `text`）。
     *  - condition / logic：入口 + 真假两条出口。
     *  - extract：入口 + 出口 + 变量输出。
     *  - wait：入口 + 出口。
     */
    /** 按节点 id 取它的针脚表（找不到节点返回空）。 */
    fun pinsOf(def: WorkflowDefinition, nodeId: String): List<WorkflowPinSpec> =
        def.node(nodeId)?.let { defaultPinsFor(it) }.orEmpty()

    fun defaultPinsFor(node: WorkflowNode): List<WorkflowPinSpec> {
        val execIn = WorkflowPinSpec("in", "", WorkflowPinKind.EXEC_IN)
        val execOut = WorkflowPinSpec("out", "", WorkflowPinKind.EXEC_OUT)
        // 每个节点都有「这一步的结果」出口 —— 这是"把获取到的参数接到别处"的入口。
        //
        // 参考实现里每个 Action 的每个输出参数天然都能拉线，所以上一版只有 extract
        // 能往外给值，等于逼用户为了传一个值专门插一个 extract 节点。
        // 这里给每个节点一个统一的 result 出口（类型 ANY：任何下游都能接）。
        val resultPin = listOf(WorkflowPinSpec("result", "结果", WorkflowPinKind.DATA_OUT, dataType = WorkflowPinType.ANY.id))
        val yes = WorkflowPinSpec("out_true", "成立", WorkflowPinKind.EXEC_TRUE)
        val no = WorkflowPinSpec("out_false", "不成立", WorkflowPinKind.EXEC_FALSE)
        return when (node.type) {
            WorkflowNodeType.TRIGGER ->
                if (node.config("requireDsh") == "1") listOf(
                    WorkflowPinSpec("out", "就绪", WorkflowPinKind.EXEC_TRUE),
                    WorkflowPinSpec("out_false", "未就绪", WorkflowPinKind.EXEC_FALSE),
                ) else listOf(execOut)

            WorkflowNodeType.EXECUTE -> listOf(execIn, execOut) + resultPin + when (node.actionType) {
                WorkflowActionType.SHELL, WorkflowActionType.DSH -> listOf(
                    WorkflowPinSpec("cmd", "命令", WorkflowPinKind.DATA_IN, defaultValue = node.config("cmd")),
                )

                WorkflowActionType.NOTIFY -> listOf(
                    WorkflowPinSpec("text", "通知", WorkflowPinKind.DATA_IN, defaultValue = node.config("text")),
                )

                // 原生动作的数据入口 = 目录声明的参数，与参数编辑器同一份来源。
                // 两处各写一份的话，画布上会有"有针脚、编辑器里没这个键"的错位。
                WorkflowActionType.NATIVE -> {
                    val spec = WorkflowActionCatalog.byId(node.config("bridge"))
                    spec?.params?.map { p ->
                        WorkflowPinSpec(
                            id = p.key,
                            label = actionParamLabel(p),
                            kind = WorkflowPinKind.DATA_IN,
                            defaultValue = node.config(spec.configKey(p)),
                        )
                    }.orEmpty()
                }

                null -> emptyList()
            }

            WorkflowNodeType.CONDITION, WorkflowNodeType.LOGIC -> listOf(execIn, yes, no) + resultPin

            WorkflowNodeType.EXTRACT -> listOf(
                execIn,
                execOut,
                WorkflowPinSpec("var", "变量", WorkflowPinKind.DATA_OUT, defaultValue = node.config("var")),
            ) + resultPin

            WorkflowNodeType.WAIT -> listOf(execIn, execOut) + resultPin
        }
    }
}

/**
 * 引脚标签：目录里的参数名，取当前语言。
 *
 * 这里不是 @Composable 上下文（引脚规格是纯数据），所以直接读应用资源；
 * 读不到时退回参数键名 —— 引脚只是辅助标记，不该因为少一条翻译就让画布崩掉。
 */
private fun actionParamLabel(p: WorkflowActionCatalog.Param): String =
    runCatching { me.bmax.apatch.apApp.resources.getString(p.labelRes) }.getOrDefault(p.key)

/** 节点类型。`id` 是落盘值，不要改。 */
internal enum class WorkflowNodeType(val id: String) {
    /** 唯一入口：声明这个工作流在什么前提下才跑（DSH 要不要先起来）。 */
    TRIGGER("trigger"),

    /** 做一件事：按 [WorkflowActionType] 执行。 */
    EXECUTE("execute"),

    /** 判断：给出 true / false 两条出边。 */
    CONDITION("condition"),

    /** 逻辑组合：把若干条件的结论 and/or/not 起来。 */
    LOGIC("logic"),

    /** 取值：从某一步的输出里抽一个值写进运行变量，供后续 `${name}` 使用。 */
    EXTRACT("extract"),

    /** 等待：固定时长，或等 DSH 变成某个状态。 */
    WAIT("wait");

    companion object {
        fun of(id: String?): WorkflowNodeType? = entries.firstOrNull { it.id == id }
    }
}

/** `execute` 节点能做的事。只有这三件 —— 宿主能替它做、且能被审计的事。 */
internal enum class WorkflowActionType(val id: String) {
    /** 容器里执行一条 shell 命令（DSH 不要求已启动）。 */
    SHELL("shell"),

    /** 与 [SHELL] 同一条路，但**要求 DSH 已运行**：没起来则按 trigger 的策略拉起并等待。 */
    DSH("dsh"),

    /** 发一条通知（长流程跑完/需要人时用；文案支持 `${var}`）。 */
    NOTIFY("notify"),

    /**
     * 调一个**原生能力端点**（见 [WorkflowActionCatalog]）。
     *
     * 为什么单列一类而不是让用户写 `dsh-native ...` 的 shell：shell 那条路只到得了容器里，
     * 端点却要经过宿主桥（`DshNativeBridge.handle`）才有权限与能力门禁。写成 shell 的话，
     * 用户以为拿到了能力、实际要么没权限、要么失败原因埋在几十行日志后面。
     *
     * config：`bridge` = 动作 id；`p_<参数名>` = 该动作的参数（见 catalog 的 Param）。
     */
    NATIVE("native");

    companion object {
        fun of(id: String?): WorkflowActionType? = entries.firstOrNull { it.id == id }
    }
}

/** 节点失败（或超时）之后怎么办。 */
internal enum class WorkflowOnError(val id: String) {
    /** 整个运行失败。默认：静默继续会让「跑完了」变成一句假话。 */
    STOP("stop"),

    /** 记下失败，继续走默认出边。 */
    CONTINUE("continue"),

    /** 跳过这个节点的后续（走默认出边），只记 SKIPPED。 */
    SKIP("skip");

    companion object {
        fun of(id: String?): WorkflowOnError? = entries.firstOrNull { it.id == id }
    }
}

/** 运行状态。 */
internal enum class WorkflowRunState(val id: String) {
    QUEUED("queued"),

    /** 在等 DSH 起来（trigger 要求）。 */
    WAITING_DSH("waiting_dsh"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELED("canceled"),

    /**
     * 进程被杀/重启时还挂着的运行。
     *
     * 不谎报成功也不谎报失败：我们**不知道**那条命令后来怎么样了。状态查询必须能把它和
     * 「真的失败」区分开（前者要用户自己去看设备上发生了什么）。
     */
    INTERRUPTED("interrupted");

    val terminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == CANCELED || this == INTERRUPTED
}

/** 单个节点的执行结果。 */
internal enum class WorkflowStepState(val id: String) {
    PENDING("pending"),
    RUNNING("running"),
    OK("ok"),
    FAILED("failed"),
    TIMEOUT("timeout"),
    SKIPPED("skipped"),
    CANCELED("canceled");
}

/**
 * 引脚种类。**纯展示语义**：不落盘、不参与校验、也不改变执行。
 *
 * 画布用它把「控制流」与「数据」分开画（点击助手的 ExecutePin / ValuePin 分法）：
 * 控制流是入口/出口/真假分支，数据是参数与可被 `${var}` 引用的输出。
 *
 * 为什么放在模型里而不是画布文件里：预览、编辑器、节点卡三处都要按类型推导同一张引脚表，
 * 各画各的就会漂移（预览与编辑器已经因为坐标推导分家漂过一次）。
 */
/**
 * 针脚的数据类型。**接线合法性只看这一张表**（对齐 TouchTool 的 PinBase.linkToAble /
 * linkFromAble：同类才能接）。
 *
 * 参考实现用的是"按实现类 instance 判断"，等价于"类型相同"。这里换成显式类型表，
 * 因为它能表达参考实现表达不了的一件事：**[ANY] 接受一切**。
 * 参考实现靠 PinAny 这种特殊针脚来做同样的事。
 */
internal enum class WorkflowPinType(val id: String) {
    TEXT("text"),
    NUMBER("number"),
    BOOL("bool"),
    CHOICE("choice"),
    /** 接受一切：用于"把这个节点的整体输出接走"这类场景。 */
    ANY("any");

    /**
     * 我能不能接上对方的针脚（我是**输出端**，[other] 是输入端）。
     *
     * 照抄参考实现的三条：
     *  1. 同类可以接；
     *  2. 任一端是 ANY 可以接（ANY 是"什么都接受/什么都能给"）；
     *  3. 其余一律不行 —— **不做隐式转换**。文本喂数字看起来能用，但运行期会静默变成
     *     一段文字，validator 还判合法，那是更难查的 bug。
     */
    fun accepts(other: WorkflowPinType): Boolean =
        this == other || this == ANY || other == ANY

    companion object {
        fun of(raw: String): WorkflowPinType =
            entries.firstOrNull { it.id == raw } ?: TEXT
    }
}

internal enum class WorkflowPinKind {
    /** 控制流入口：谁把控制流交给我。 */
    EXEC_IN,
    /** 控制流出口：接着跑谁。 */
    EXEC_OUT,
    /** 条件成立分支。 */
    EXEC_TRUE,
    /** 条件不成立分支。 */
    EXEC_FALSE,
    /** 数据输入（参数）。未连线时画布就地把值编辑掉。 */
    DATA_IN,
    /** 数据输出（可被后续 `${var}` 引用，或接到别的节点的数据输入）。 */
    DATA_OUT,
}

/**
 * 一个引脚的展示规格。`id` 只在画布内唯一，**不落盘**。
 *
 * 两个默认值不是可选项：`dataType` 让画布能按类型选控件，`defaultValue` 让未连线时
 * 有东西可显示；都带默认参数，所以旧的构造点一个都不用改。
 */
internal data class WorkflowPinSpec(
    val id: String,
    val label: String,
    val kind: WorkflowPinKind,
    val dataType: String = "string",
    val defaultValue: String = "",
) {
    /** 类型化的声明。字符串字段是为存盘稳定，判定一律走这里。 */
    val type: WorkflowPinType get() = WorkflowPinType.of(dataType)

    /** 这个针脚是"出去"的一侧吗（数据出口 / 控制流出口）。 */
    val isOut: Boolean
        get() = kind == WorkflowPinKind.DATA_OUT ||
            kind == WorkflowPinKind.EXEC_OUT ||
            kind == WorkflowPinKind.EXEC_TRUE ||
            kind == WorkflowPinKind.EXEC_FALSE

    /**
     * 输入端是单线：接一根新的就把旧的顶掉。
     *
     * 对齐参考实现 `Pin.isSingleLink()` —— 它的实现是 `isVertical() ? out : !out`，
     * 也就是说**纵向布局下输出端单线，横向布局下输入端单线**。这里画布是纵向的
     * （数据从上往下流），所以输入端单线。
     *
     * 为什么必须是单线：一个参数同时被两根线喂，取值就成了"谁最后到算谁的"，
     * 用户看到的和实际跑的会是两回事。输出端可以分叉（一个结果喂给多个下游），
     * 输入端不行。
     */
    val singleLink: Boolean get() = !isOut

    /** 能不能从 [source] 接到我身上（我是输入端）。 */
    fun canLinkFrom(source: WorkflowPinSpec): Boolean {
        if (isOut) return false
        if (kind == WorkflowPinKind.EXEC_IN) return source.isOut && !source.isDataSide
        return source.isDataSide && type.accepts(source.type)
    }

    /** 这个针脚属数据侧吗（区别于控制流）。 */
    val isDataSide: Boolean
        get() = kind == WorkflowPinKind.DATA_IN || kind == WorkflowPinKind.DATA_OUT
}

/**
 * 一个节点。
 *
 * [config] 是**按类型限定的**键值：校验器逐类型检查必填键与取值（见 [WorkflowValidator]）。
 * 用 map 而不是给每种节点建一个 data class：落盘/回读只走一条路径，AI 生成的定义也不会
 * 因为多一个未知字段而整份作废 —— 未知键在校验期被点名，而不是在解析期炸掉。
 */
/**
 * 卡片展开档位。语义逐条对齐 TouchTool 的 `Action.ExpandType` + `PinView.expand`：
 *
 * - [NONE]：只显示**已连线**的参数行；没连线的全隐藏。
 * - [HALF]：显示所有非 `hide` 的参数行（未连线的也显示）。
 * - [FULL]：全部显示。
 *
 * 关键细节（照抄，不能省）：**连了线的参数行在任何档位都可见**。
 * 否则用户切到 NONE 会以为接线丢了 —— 参考实现里那句
 * `if (pin.getLinks().isEmpty())` 就是为这个存在的。
 */
internal enum class WorkflowExpandType(val id: String) {
    NONE("none"),
    HALF("half"),
    FULL("full");

    companion object {
        fun of(raw: String): WorkflowExpandType =
            entries.firstOrNull { it.id == raw } ?: HALF
    }
}

internal data class WorkflowNode(
    val id: String,
    val type: WorkflowNodeType,
    val name: String = "",
    val description: String = "",
    /** 蓝图坐标：编辑器摆哪儿就存哪儿，预览与编辑共用。0/0 = 没摆过，由界面自动分层。 */
    val posX: Int = 0,
    val posY: Int = 0,
    val timeoutMs: Long = 120_000L,
    val onError: WorkflowOnError = WorkflowOnError.STOP,
    /**
     * 卡片展开档位。默认 HALF —— 与参考实现一致（读不到存盘值时也用 HALF）。
     * 语义见 [WorkflowExpandType]。
     */
    val expandType: WorkflowExpandType = WorkflowExpandType.HALF,
    /**
     * 锁定 = **不能拖动、不能被选中、不能删除**。
     *
     * 参考实现的 isLocked() 恰好就作用在这三处（CardLayoutHelper 拖动落位、
     * CardLayoutView 命中检测、BlueprintView 删除），这里照同样的三处生效，
     * 免得出现"锁了还能拖走"这种半锁状态。
     */
    val locked: Boolean = false,
    val config: Map<String, String> = emptyMap(),
) {
    /** 动作类型（只有 execute 节点有）。 */
    val actionType: WorkflowActionType? get() = WorkflowActionType.of(config["action"])

    fun config(key: String): String = config[key].orEmpty()

    /** 改一个参数并把改动写回 config。空串即删除该键 —— 校验器靠"键不存在"判定必填缺失。 */
    fun withConfig(key: String, value: String): WorkflowNode =
        copy(config = if (value.isEmpty()) config - key else config + (key to value))
}

/**
 * 一条连线。
 *
 * [whenExpr] 的取值只有三种：`""`（默认出边，前面没有条件命中时走它）、`"true"`、`"false"`
 * （来自 condition / logic 节点）。**不做表达式求值** —— 那等于把 DSL 变成脚本语言。
 */
internal data class WorkflowEdge(
    val from: String,
    val to: String,
    val whenExpr: String = "",
    /** 边的种类：[WorkflowEdgeKind.FLOW] 控制流 / [WorkflowEdgeKind.DATA] 传值。 */
    val kind: WorkflowEdgeKind = WorkflowEdgeKind.FLOW,
    /** 数据边的起点针脚（上游节点的一个 DATA_OUT，通常是 `step:<id>` 或 `var:<name>`）。 */
    val fromPin: String = "",
    /** 数据边的终点针脚（下游节点的一个 DATA_IN，即它 config 里的某个参数键）。 */
    val toPin: String = "",
    /** 数据边的值类型提示，只用于画布配色与校验提示，不参与求值。 */
    val dataType: String = "string",
) {
    /** 控制流边：参与"下一步走谁"的判定。 */
    val isFlow: Boolean get() = kind == WorkflowEdgeKind.FLOW
}

/**
 * 连线种类。
 *
 * 分这两种是因为它们在执行期的作用**完全不同**：控制流决定"接下来跑谁"，
 * 数据边只决定"某个参数从哪来"。混在一起的话，一个节点有两条出边时执行器无法判断
 * 哪条是"往下走"、哪条只是"喂个值" —— 这正是 v1 表达不了数据传递的根因。
 */
internal enum class WorkflowEdgeKind(val id: String) {
    FLOW("flow"),
    DATA("data");

    companion object {
        fun of(id: String?): WorkflowEdgeKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 这份定义**实际会用到的能力**。
 *
 * `declaredCaps` 是手工声明的那一份；NATIVE 节点还得按动作目录把 Cap 算进来 ——
 * 否则会出现最糟的组合：界面上明明摆着「点一下屏幕」，批准弹窗却没列无障碍，
 * 运行到那一步被门禁拦下（或者更糟：用户以为自己批准过这一项）。
 *
 * 两者取并集而不是只认目录：手工声明的能力还覆盖 shell / dsh 节点里直接调端点的写法。
 */
internal val WorkflowDefinition.effectiveCaps: List<String>
    get() = (
        declaredCaps +
            nodes.mapNotNull { n -> WorkflowActionCatalog.byId(n.config("bridge"))?.cap?.id }
        ).distinct()

/** 运行级变量：`extract` 节点写入，命令/通知文案里用 `${name}` 读。 */
internal data class WorkflowDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    /** 声明这次会用到哪些原生能力（[DshNativeBridge.Cap] 的 id）。批准弹窗照它列清单。 */
    val declaredCaps: List<String> = emptyList(),
    /** 声明里含危险命令（[PrivPolicy] 命中）。批准时必须单独勾选才免逐条确认。 */
    val declaresDangerous: Boolean = false,
    val nodes: List<WorkflowNode> = emptyList(),
    val edges: List<WorkflowEdge> = emptyList(),
    val schemaVersion: Int = WorkflowSchema.SCHEMA_VERSION,
    val revision: Int = 1,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
) {
    fun node(id: String): WorkflowNode? = nodes.firstOrNull { it.id == id }

    /** 入口节点（trigger）。校验器保证只有一个。 */
    val trigger: WorkflowNode? get() = nodes.firstOrNull { it.type == WorkflowNodeType.TRIGGER }

    /**
     * 控制流出边。**只返回 flow 边** —— 执行器的"下一步走谁"全靠它，
     * 把数据边也算进来的话，一个节点会给同一批参数各连一条出边，
     * 于是 `nextNode` 抓到的第一条可能根本不是用来走的。
     */
    fun outgoing(from: String): List<WorkflowEdge> = edges.filter { it.from == from && it.isFlow }

    /** 数据边：喂给 [nodeId] 某个参数的所有来源。 */
    fun dataInto(nodeId: String): List<WorkflowEdge> =
        edges.filter { it.to == nodeId && it.kind == WorkflowEdgeKind.DATA }

    /** 数据边：从 [nodeId] 出去的所有来源声明。 */
    fun dataFrom(nodeId: String): List<WorkflowEdge> =
        edges.filter { it.from == nodeId && it.kind == WorkflowEdgeKind.DATA }
}

/** 运行状态与逐节点结果（持久化，进程重启后仍可查）。 */
internal data class WorkflowStepResult(
    val nodeId: String,
    val type: WorkflowNodeType,
    val name: String,
    val state: WorkflowStepState,
    val exitCode: Int? = null,
    val startedAtMs: Long = 0L,
    val endedAtMs: Long = 0L,
    /** 输出尾部（有上限），状态查询默认回它。 */
    val outputTail: String = "",
    /** 失败/跳过/超时的原因（机器可读短句，如 `timeout` / `exit_1` / `canceled`）。 */
    val reason: String = "",
) {
    val durationMs: Long get() = if (endedAtMs > startedAtMs) endedAtMs - startedAtMs else 0L
}

internal data class WorkflowRun(
    val runId: String,
    val workflowId: String,
    val workflowName: String,
    val state: WorkflowRunState,
    val currentNodeId: String? = null,
    val steps: List<WorkflowStepResult> = emptyList(),
    /** 运行时使用的定义**快照**：用户之后改了定义，旧运行仍能看到当时跑的是什么。 */
    val definition: WorkflowDefinition? = null,
    val startedAtMs: Long = 0L,
    val endedAtMs: Long = 0L,
    val cancelReason: String = "",
    /** 用户批准时改动过的差异（没改就是空）。创建/运行结束时连同结果一起回给 AI。 */
    val diff: List<WorkflowPatch> = emptyList(),
    val variables: Map<String, String> = emptyMap(),
) {
    val running: Boolean get() = !state.terminal
}

/**
 * 一条结构化差异（patch）。
 *
 * 词汇与 Operit 的 `node_patches` 一致（add / remove / update / edge-add / edge-remove）：
 * 用户编辑后的 diff 由此表达；将来若加 `workflow_patch` 工具，同一套 op 直接复用。
 */
internal data class WorkflowPatch(
    val op: String,
    val target: String,
    val detail: String = "",
) {
    fun humanize(): String = when (op) {
        OP_ADD_NODE -> "新增节点 $target${if (detail.isBlank()) "" else "（$detail）"}"
        OP_REMOVE_NODE -> "删除节点 $target"
        OP_UPDATE_NODE -> "修改节点 $target：$detail"
        OP_ADD_EDGE -> "新增连线 $target"
        OP_REMOVE_EDGE -> "删除连线 $target"
        else -> "$op $target"
    }

    companion object {
        const val OP_ADD_NODE = "add_node"
        const val OP_REMOVE_NODE = "remove_node"
        const val OP_UPDATE_NODE = "update_node"
        const val OP_ADD_EDGE = "add_edge"
        const val OP_REMOVE_EDGE = "remove_edge"
    }
}

// ────────────────────────── JSON ──────────────────────────

internal fun WorkflowNode.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("type", type.id)
    .put("name", name)
    .apply { if (description.isNotBlank()) put("description", description) }
    .put("posX", posX)
    .put("posY", posY)
    .put("timeoutMs", timeoutMs)
    .put("onError", onError.id)
    .put("expandType", expandType.id)
    .apply { if (locked) put("locked", true) }
    .put("config", JSONObject(config as Map<*, *>))

internal fun nodeFromJson(o: JSONObject): WorkflowNode? {
    val id = o.optString("id").trim()
    val type = WorkflowNodeType.of(o.optString("type")) ?: return null
    val config = mutableMapOf<String, String>()
    o.optJSONObject("config")?.let { c ->
        for (k in c.keys()) config[k] = c.optString(k)
    }
    return WorkflowNode(
        id = id,
        type = type,
        name = o.optString("name"),
        description = o.optString("description"),
        posX = o.optInt("posX"),
        posY = o.optInt("posY"),
        timeoutMs = o.optLong("timeoutMs", 120_000L),
        onError = WorkflowOnError.of(o.optString("onError")) ?: WorkflowOnError.STOP,
        expandType = WorkflowExpandType.of(o.optString("expandType")),
        locked = o.optBoolean("locked"),
        config = config,
    )
}

/**
 * 落盘：**只有数据边才写 kind / 引脚 / 类型**。
 *
 * 控制流边是最常见的，也是 v1 就有的形态 —— 给它多写三个键只会让 dsh 的定义文件变啰嗦，
 * 而 `kind` 缺省就是 flow，读回来一模一样。这也让 v1 的 JSON 与 v2 的控制流边**逐字相同**，
 * 迁移因此不需要任何"改写旧文件"的动作。
 */
internal fun WorkflowEdge.toJson(): JSONObject = JSONObject()
    .put("from", from)
    .put("to", to)
    .put("when", whenExpr)
    .apply {
        if (kind == WorkflowEdgeKind.DATA) {
            put("kind", kind.id)
            put("fromPin", fromPin)
            put("toPin", toPin)
            put("dataType", dataType)
        }
    }

internal fun edgeFromJson(o: JSONObject): WorkflowEdge? {
    val from = o.optString("from").trim()
    val to = o.optString("to").trim()
    if (from.isEmpty() || to.isEmpty()) return null
    // kind 缺省 = flow：v1 的每条边都是控制流，v2 的控制流边也不写这个键。
    // 认不出来的 kind（未来版本新增的）同样退回 flow —— 旧代码读到它至少还能当普通边跑，
    // 而不是整份定义解析失败。
    val kind = WorkflowEdgeKind.of(o.optString("kind")) ?: WorkflowEdgeKind.FLOW
    return WorkflowEdge(
        from = from,
        to = to,
        whenExpr = o.optString("when"),
        kind = kind,
        fromPin = o.optString("fromPin"),
        toPin = o.optString("toPin"),
        dataType = o.optString("dataType").ifBlank { "string" },
    )
}

internal fun WorkflowDefinition.toJson(): JSONObject = JSONObject()
    .put("schemaVersion", schemaVersion)
    .put("id", id)
    .put("name", name)
    .put("description", description)
    .put("declaredCaps", JSONArray(declaredCaps))
    .put("declaresDangerous", declaresDangerous)
    .put("revision", revision)
    .put("createdAtMs", createdAtMs)
    .put("updatedAtMs", updatedAtMs)
    .put("nodes", JSONArray().apply { nodes.forEach { put(it.toJson()) } })
    .put("edges", JSONArray().apply { edges.forEach { put(it.toJson()) } })

/**
 * 把读到的版本号抬到当前版本 —— **只在仍然兼容的情况下**，否则原样返回，由校验器判失效。
 *
 * ## 版本史
 *
 * - v1→v2：边的种类（`kind` 缺省即 `flow`），一份 v1 的 JSON 用 v2 读法解析出来本来就是对的，
 *   所以那次"迁移"只是抬版本号。
 * - v2→v3：**接线模型重写**（参数变成带类型、可接线的针脚），这次**不能靠抬版本号蒙混**：
 *   旧定义没有针脚类型、没有 result 出口，接线规则也不同。用户已明确「不必兼容旧流程，
 *   只要提醒该流程检测为旧格式、已失效」。
 *
 * 所以 v3 起，[WorkflowSchema.MIN_SUPPORTED_VERSION] == [WorkflowSchema.SCHEMA_VERSION]：
 * 「抬版本」这条路自动关闭 —— 更老的定义原样返回旧版本号，校验器据此报失效。
 * 将来若再出现**向后兼容**的格式变化（只加字段、不改语义），把 MIN_SUPPORTED 往下放一格，
 * 这里就会重新开始抬版本号。**能不能迁，由这两条常量决定，不在这里写特例。**
 */
internal fun migrateToCurrent(raw: Int): Int = when {
    raw < WorkflowSchema.MIN_SUPPORTED_VERSION -> raw
    raw > WorkflowSchema.SCHEMA_VERSION -> raw
    else -> WorkflowSchema.SCHEMA_VERSION
}

/** 定义是否为旧格式（已失效，不能跑但也不删）。 */
internal fun WorkflowDefinition.isStaleFormat(): Boolean =
    schemaVersion < WorkflowSchema.MIN_SUPPORTED_VERSION

/** 解析定义；结构不合法（缺字段/类型不认识）时返回 null，由调用方回 `invalid_definition`。 */
internal fun definitionFromJson(raw: String): WorkflowDefinition? = runCatching {
    definitionFromJson(JSONObject(raw))
}.getOrNull()

internal fun definitionFromJson(o: JSONObject): WorkflowDefinition? {
    val id = o.optString("id").trim()
    val name = o.optString("name").trim()
    if (id.isEmpty() || name.isEmpty()) return null
    val nodes = mutableListOf<WorkflowNode>()
    val arr = o.optJSONArray("nodes") ?: return null
    for (i in 0 until arr.length()) {
        val n = arr.optJSONObject(i)?.let { nodeFromJson(it) } ?: return null
        nodes += n
    }
    val edges = mutableListOf<WorkflowEdge>()
    o.optJSONArray("edges")?.let { e ->
        for (i in 0 until e.length()) {
            val edge = e.optJSONObject(i)?.let { edgeFromJson(it) } ?: return null
            edges += edge
        }
    }
    val caps = mutableListOf<String>()
    o.optJSONArray("declaredCaps")?.let { c ->
        for (i in 0 until c.length()) caps += c.optString(i)
    }
    return WorkflowDefinition(
        id = id,
        name = name,
        description = o.optString("description"),
        declaredCaps = caps,
        declaresDangerous = o.optBoolean("declaresDangerous"),
        nodes = nodes,
        edges = edges,
        // 迁移在这里发生：读进来的旧版本先升到当前版本，再交给下游。
        // 直接拒绝 v1 会让用户升级一次 App 就丢掉所有已有流程 —— 而 v1→v2 的语义
        // 变化只有一个：边多了 kind，缺省即 flow，与 v1 行为完全相同。
        schemaVersion = migrateToCurrent(o.optInt("schemaVersion", WorkflowSchema.SCHEMA_VERSION)),
        revision = o.optInt("revision", 1),
        createdAtMs = o.optLong("createdAtMs"),
        updatedAtMs = o.optLong("updatedAtMs"),
    )
}

internal fun WorkflowStepResult.toJson(): JSONObject = JSONObject()
    .put("nodeId", nodeId)
    .put("type", type.id)
    .put("name", name)
    .put("state", state.id)
    .apply { exitCode?.let { put("exitCode", it) } }
    .put("startedAtMs", startedAtMs)
    .put("endedAtMs", endedAtMs)
    .put("durationMs", durationMs)
    .put("outputTail", outputTail)
    .put("reason", reason)

internal fun stepFromJson(o: JSONObject): WorkflowStepResult? {
    val nodeId = o.optString("nodeId")
    val type = WorkflowNodeType.of(o.optString("type")) ?: return null
    val state = WorkflowStepState.entries.firstOrNull { it.id == o.optString("state") } ?: return null
    return WorkflowStepResult(
        nodeId = nodeId,
        type = type,
        name = o.optString("name"),
        state = state,
        exitCode = if (o.has("exitCode")) o.optInt("exitCode") else null,
        startedAtMs = o.optLong("startedAtMs"),
        endedAtMs = o.optLong("endedAtMs"),
        outputTail = o.optString("outputTail"),
        reason = o.optString("reason"),
    )
}

internal fun WorkflowPatch.toJson(): JSONObject =
    JSONObject().put("op", op).put("target", target).put("detail", detail)

internal fun patchFromJson(o: JSONObject): WorkflowPatch =
    WorkflowPatch(o.optString("op"), o.optString("target"), o.optString("detail"))

internal fun WorkflowRun.toJson(includeDefinition: Boolean = true): JSONObject = JSONObject()
    .put("runId", runId)
    .put("workflowId", workflowId)
    .put("workflowName", workflowName)
    .put("state", state.id)
    .apply { currentNodeId?.let { put("currentNodeId", it) } }
    .put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
    .apply { if (includeDefinition) definition?.let { put("definition", it.toJson()) } }
    .put("startedAtMs", startedAtMs)
    .put("endedAtMs", endedAtMs)
    .put("cancelReason", cancelReason)
    .put("diff", JSONArray().apply { diff.forEach { put(it.toJson()) } })
    .put("variables", JSONObject(variables as Map<*, *>))

internal fun runFromJson(raw: String): WorkflowRun? = runCatching {
    val o = JSONObject(raw)
    val runId = o.optString("runId").trim()
    val workflowId = o.optString("workflowId").trim()
    if (runId.isEmpty()) return@runCatching null
    val state = WorkflowRunState.entries.firstOrNull { it.id == o.optString("state") }
        ?: return@runCatching null
    val steps = mutableListOf<WorkflowStepResult>()
    o.optJSONArray("steps")?.let { a ->
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { s -> stepFromJson(s)?.let { steps += it } }
    }
    val diff = mutableListOf<WorkflowPatch>()
    o.optJSONArray("diff")?.let { a ->
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { diff += patchFromJson(it) }
    }
    val vars = mutableMapOf<String, String>()
    o.optJSONObject("variables")?.let { v -> for (k in v.keys()) vars[k] = v.optString(k) }
    WorkflowRun(
        runId = runId,
        workflowId = workflowId,
        workflowName = o.optString("workflowName"),
        state = state,
        currentNodeId = o.optString("currentNodeId").takeIf { it.isNotBlank() },
        steps = steps,
        definition = o.optJSONObject("definition")?.let { definitionFromJson(it) },
        startedAtMs = o.optLong("startedAtMs"),
        endedAtMs = o.optLong("endedAtMs"),
        cancelReason = o.optString("cancelReason"),
        diff = diff,
        variables = vars,
    )
}.getOrNull()

/**
 * 两份定义之间的结构差异（批准前的编辑 → 批准后要回给 AI 的那份 diff）。
 *
 * 只比结构：节点/边的增删改。节点内的具体字段变化按 `name/type/timeout/onError/config`
 * 逐项列出，值一律写明「旧 → 新」—— 用户改了超时时间这种事，只说「修改了节点」等于没说。
 */
internal fun workflowDiff(before: WorkflowDefinition, after: WorkflowDefinition): List<WorkflowPatch> {
    val out = mutableListOf<WorkflowPatch>()
    val beforeNodes = before.nodes.associateBy { it.id }
    val afterNodes = after.nodes.associateBy { it.id }
    for ((id, node) in afterNodes) {
        val old = beforeNodes[id]
        if (old == null) {
            out += WorkflowPatch(WorkflowPatch.OP_ADD_NODE, id, "${node.type.id}/${node.name}")
            continue
        }
        val changes = mutableListOf<String>()
        if (old.type != node.type) changes += "类型 ${old.type.id}→${node.type.id}"
        if (old.name != node.name) changes += "名称「${old.name}」→「${node.name}」"
        if (old.timeoutMs != node.timeoutMs) changes += "超时 ${old.timeoutMs}→${node.timeoutMs}ms"
        if (old.onError != node.onError) changes += "失败策略 ${old.onError.id}→${node.onError.id}"
        for (k in (old.config.keys + node.config.keys)) {
            if (old.config[k].orEmpty() != node.config[k].orEmpty()) {
                changes += "$k「${old.config[k].orEmpty()}」→「${node.config[k].orEmpty()}」"
            }
        }
        if (old.posX != node.posX || old.posY != node.posY) changes += "位置调整"
        if (changes.isNotEmpty()) out += WorkflowPatch(WorkflowPatch.OP_UPDATE_NODE, id, changes.joinToString("；"))
    }
    for ((id, node) in beforeNodes) {
        if (!afterNodes.containsKey(id)) {
            out += WorkflowPatch(WorkflowPatch.OP_REMOVE_NODE, id, "${node.type.id}/${node.name}")
        }
    }
    fun edgeKey(e: WorkflowEdge) = "${e.from}->${e.to}" + if (e.whenExpr.isBlank()) "" else "[${e.whenExpr}]"
    val beforeEdges = before.edges.map { edgeKey(it) }.toSet()
    val afterEdges = after.edges.map { edgeKey(it) }.toSet()
    for (e in afterEdges - beforeEdges) out += WorkflowPatch(WorkflowPatch.OP_ADD_EDGE, e)
    for (e in beforeEdges - afterEdges) out += WorkflowPatch(WorkflowPatch.OP_REMOVE_EDGE, e)
    if (before.declaredCaps.toSet() != after.declaredCaps.toSet()) {
        out += WorkflowPatch(
            WorkflowPatch.OP_UPDATE_NODE,
            "declaredCaps",
            "${before.declaredCaps.joinToString(",")} → ${after.declaredCaps.joinToString(",")}",
        )
    }
    return out
}

/**
 * 变量插值：把命令/通知文案里的 `${name}` 换成运行变量的值。
 *
 * 未定义的变量**保持原样**（不换成空串）：静默替换成空串会让 `rm -rf ${dir}/` 变成
 * `rm -rf /`，而这种错误在日志里看不出来。
 */
internal fun interpolate(text: String, vars: Map<String, String>): String {
    if (text.isEmpty() || vars.isEmpty() || !text.contains("\${")) return text
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        if (text[i] == '$' && i + 1 < text.length && text[i + 1] == '{') {
            val end = text.indexOf('}', i + 2)
            if (end > 0) {
                val key = text.substring(i + 2, end)
                val v = vars[key]
                if (v != null) {
                    sb.append(v)
                    i = end + 1
                    continue
                }
            }
        }
        sb.append(text[i])
        i++
    }
    return sb.toString()
}
