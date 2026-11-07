package me.bmax.apatch.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.bmax.apatch.R
import me.bmax.apatch.dsh.WorkflowActionCatalog
import me.bmax.apatch.dsh.WorkflowActionType
import me.bmax.apatch.dsh.WorkflowNode
import me.bmax.apatch.dsh.WorkflowNodeType
import me.bmax.apatch.dsh.WorkflowOnError
import me.bmax.apatch.dsh.WorkflowSchema

/**
 * 节点参数的**字段表**：一张「这个类型有哪些键、各自长什么样」的声明。
 *
 * ## 为什么要有字段表
 *
 * 以前节点参数是一串 `OutlinedTextField`，键名直接写在代码里，于是有两个后果：
 *
 * 1. **校验器认的键与界面能改的键不是同一份**。validator 要求 `extract` 的
 *    `mode=json` 时必须有 `path`、`logic` 的 `op` 只能是 and/or/not —— 而界面上全是
 *    自由文本框，用户只能靠猜，猜错要到运行/保存时才被点名。
 * 2. 布尔、枚举、数值全走文本框：`requireDsh` 要手打 `1`，写 `yes` 就被判不合法。
 *
 * 现在只有这一份表：**键、控件种类、可选取值都从它来**，界面上不可能出现 validator 不认的键，
 * 也不会漏掉它要求的键。新增字段 = 在这里加一行 + validator 认它。
 *
 * ## 与引脚的关系
 *
 * `DATA_IN` 引脚也由这张表生成（见 [WorkflowSchema.defaultPinsFor] 的调用点）：
 * 参数就是数据入口，两者共用一份定义才不会出现"画布上有针脚、编辑器里没这个键"。
 */
internal object WorkflowNodeFields {

    /** 控件种类。决定界面渲染哪一种输入，以及写回时怎么规范化。 */
    enum class Kind {
        TEXT,
        MULTILINE,
        NUMBER,
        BOOL,
        CHOICE,
    }

    /** 一个可编辑字段。 */
    data class Field(
        val key: String,
        val labelRes: Int,
        val kind: Kind,
        /** [Kind.CHOICE] 的可选取值（写入 config 的原值）。 */
        val choices: List<String> = emptyList(),
        /** 校验器要求的可选值之外的说明，显示在字段下方。 */
        val hintRes: Int = 0,
        /** 只在满足条件时显示（例如 extract 的 path 只在 mode=json 时才有意义）。 */
        val visible: (WorkflowNode) -> Boolean = { true },
    )

    private fun text(key: String, labelRes: Int) = Field(key, labelRes, Kind.TEXT)
    private fun multiline(key: String, labelRes: Int) = Field(key, labelRes, Kind.MULTILINE)
    private fun number(key: String, labelRes: Int) = Field(key, labelRes, Kind.NUMBER)
    private fun bool(key: String, labelRes: Int) = Field(key, labelRes, Kind.BOOL)
    private fun choice(key: String, labelRes: Int, vararg values: String) =
        Field(key, labelRes, Kind.CHOICE, values.toList())

    /**
     * 某个节点当前**应该**显示的字段。
     *
     * 顺序即界面顺序：先「这一步做什么」，再「失败怎么办」，最后是类型专属参数。
     */
    fun fieldsFor(node: WorkflowNode): List<Field> = buildList {
        // 公共字段：所有类型都有。名字与描述是给人看的（批准弹窗、列表、日志都用它）。
        add(text("name", R.string.dsh_wf_field_name))
        add(text("description", R.string.dsh_wf_field_description))
        when (node.type) {
            WorkflowNodeType.TRIGGER -> {
                add(bool("requireDsh", R.string.dsh_wf_field_require_dsh))
                add(bool("autostartDsh", R.string.dsh_wf_field_autostart_dsh))
            }
            WorkflowNodeType.EXECUTE -> when (node.actionType) {
                // 原生动作：**参数字段完全来自动作目录**，界面不认识任何具体键名。
                // 目录里的 required/choices 直接决定这里出现哪些控件 —— 于是"卡片点了、
                // 参数却没处填"这种缺口不可能再出现。
                WorkflowActionType.NATIVE -> {
                    val spec = WorkflowActionCatalog.byId(node.config("bridge"))
                    if (spec == null) {
                        add(text("bridge", R.string.dsh_wf_field_bridge))
                    } else {
                        for (p in spec.params) {
                            add(
                                Field(
                                    key = spec.configKey(p),
                                    labelRes = p.labelRes,
                                    kind = when (p.kind) {
                                        WorkflowActionCatalog.ParamKind.NUMBER -> Kind.NUMBER
                                        WorkflowActionCatalog.ParamKind.CHOICE -> Kind.CHOICE
                                        WorkflowActionCatalog.ParamKind.BOOL -> Kind.BOOL
                                        WorkflowActionCatalog.ParamKind.TEXT -> Kind.TEXT
                                    },
                                    choices = p.choices,
                                ),
                            )
                        }
                    }
                }
                WorkflowActionType.NOTIFY -> {
                    add(text("title", R.string.dsh_wf_field_notify_title))
                    add(multiline("text", R.string.dsh_wf_field_notify_text))
                }
                else -> add(multiline("cmd", R.string.dsh_wf_field_cmd))
            }
            WorkflowNodeType.CONDITION -> {
                add(text("source", R.string.dsh_wf_field_cond_source))
                add(
                    choice(
                        "op", R.string.dsh_wf_field_cond_op,
                        "contains", "equals", "matches", "exists", "empty",
                    ).copy(hintRes = R.string.dsh_wf_hint_cond_op),
                )
                add(
                    text("value", R.string.dsh_wf_field_cond_value)
                        .copy(visible = { it.config("op") != "exists" && it.config("op") != "empty" }),
                )
            }
            WorkflowNodeType.LOGIC -> {
                add(
                    choice("op", R.string.dsh_wf_field_logic_op, "and", "or", "not")
                        .copy(hintRes = R.string.dsh_wf_hint_logic_sources),
                )
                add(multiline("sources", R.string.dsh_wf_field_logic_sources))
            }
            WorkflowNodeType.EXTRACT -> {
                add(text("source", R.string.dsh_wf_field_extract_source))
                add(choice("mode", R.string.dsh_wf_field_extract_mode, "text", "json", "regex"))
                add(text("path", R.string.dsh_wf_field_extract_path).copy(visible = { it.config("mode") == "json" }))
                add(
                    text("pattern", R.string.dsh_wf_field_extract_pattern)
                        .copy(visible = { it.config("mode") == "regex" }),
                )
                add(text("var", R.string.dsh_wf_field_extract_var))
            }
            WorkflowNodeType.WAIT -> {
                add(number("ms", R.string.dsh_wf_field_wait_ms))
                add(choice("untilDsh", R.string.dsh_wf_field_wait_until, "running", "stopped"))
            }
        }
        // 失败策略与超时：所有类型通用（校验器也对这些字段一体校验）。
        add(choice("onError", R.string.dsh_wf_field_on_error, *WorkflowOnError.entries.map { it.id }.toTypedArray()))
        add(number("timeoutMs", R.string.dsh_wf_field_timeout))
    }

    /**
     * 把用户输入的原始文本规范化成能落盘的值。
     *
     * 只有 `BOOL` 需要真做事：校验器接受 1/0/true/false，界面统一写 1/0，
     * 免得同一份定义里两种写法混用（人读也省一次判断）。
     */
    fun normalize(field: Field, raw: String): String = when (field.kind) {
        Kind.BOOL -> if (raw == "1" || raw == "true") "1" else "0"
        Kind.NUMBER -> raw.filter { it.isDigit() }
        else -> raw
    }

    /** 布尔字段的当前值。空 = 用 DSL 默认（各字段自己的默认见 validator）。 */
    fun boolOf(node: WorkflowNode, key: String): Boolean = when (node.config(key)) {
        "1", "true" -> true
        else -> false
    }
}

/**
 * 一个字段的编辑控件。
 *
 * 完全由 [WorkflowNodeFields.Field] 驱动：界面**不认识**任何具体的键名，
 * 所以「validator 多了一个要求、界面忘了加」这类缺口不可能再出现。
 */
@Composable
internal fun NodeFieldRow(
    node: WorkflowNode,
    field: WorkflowNodeFields.Field,
    onChange: (WorkflowNode) -> Unit,
) {
    if (!field.visible(node)) return
    val value = node.config(field.key)
    fun write(raw: String) {
        onChange(node.copy(config = node.config + (field.key to WorkflowNodeFields.normalize(field, raw))))
    }
    when (field.kind) {
        WorkflowNodeFields.Kind.BOOL -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(field.labelRes), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = WorkflowNodeFields.boolOf(node, field.key),
                    onCheckedChange = { write(if (it) "1" else "0") },
                )
            }
        }
        WorkflowNodeFields.Kind.CHOICE -> {
            Text(
                stringResource(field.labelRes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (choice in field.choices) {
                    FilterChip(
                        selected = value == choice,
                        onClick = { write(choice) },
                        label = { Text(choice) },
                    )
                }
            }
            if (field.hintRes != 0) {
                Text(
                    stringResource(field.hintRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        WorkflowNodeFields.Kind.MULTILINE -> Column {
            OutlinedTextField(
                value = value,
                onValueChange = { write(it) },
                label = { Text(stringResource(field.labelRes)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            if (field.hintRes != 0) {
                Text(
                    stringResource(field.hintRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        WorkflowNodeFields.Kind.NUMBER, WorkflowNodeFields.Kind.TEXT -> Column {
            OutlinedTextField(
                value = value,
                onValueChange = { write(it) },
                label = { Text(stringResource(field.labelRes)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (field.hintRes != 0) {
                Text(
                    stringResource(field.hintRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 该类型的数据输入键。
 *
 * **委托给 [WorkflowSchema.dataInputKeysOf]**：那一份是执行器的读取依据，校验器查数据边时
 * 用的也是它。界面若在这里自己再筛一遍（比如把 name/description 剔掉），两份判据就会
 * 慢慢分叉 —— 而分叉的后果是"画布上能连、校验器说没有这个参数"。
 */
internal fun dataInputKeys(node: WorkflowNode): List<String> =
    WorkflowSchema.dataInputKeysOf(node)

/** 造一个带正确默认值的空节点（目录点选后直接用）。 */
internal fun defaultConfigFor(type: WorkflowNodeType, action: WorkflowActionType? = null): Map<String, String> =
    when (type) {
        WorkflowNodeType.TRIGGER -> mapOf("requireDsh" to "0", "autostartDsh" to "1")
        WorkflowNodeType.EXECUTE -> when (action) {
            WorkflowActionType.NATIVE -> mapOf("action" to "native")
            WorkflowActionType.NOTIFY -> mapOf("action" to "notify", "title" to "", "text" to "")
            WorkflowActionType.DSH -> mapOf("action" to "dsh", "cmd" to "dsh --version")
            else -> mapOf("action" to "shell", "cmd" to "echo hello")
        }
        WorkflowNodeType.CONDITION -> mapOf("source" to "exitCode", "op" to "equals", "value" to "0")
        WorkflowNodeType.LOGIC -> mapOf("op" to "and", "sources" to "")
        WorkflowNodeType.EXTRACT -> mapOf("source" to "lastOutput", "mode" to "text", "var" to "value")
        WorkflowNodeType.WAIT -> mapOf("ms" to "1000")
    }
