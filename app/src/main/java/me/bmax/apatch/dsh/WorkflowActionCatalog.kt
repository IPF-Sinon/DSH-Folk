package me.bmax.apatch.dsh

import me.bmax.apatch.R

/**
 * 工作流的**动作目录**：一张「有哪些动作、各自要什么参数、用哪个能力」的表。
 *
 * ## 它解决什么问题
 *
 * 参数编辑器（[me.bmax.apatch.ui.component.WorkflowNodeFields]）解决的是"节点内部字段"，
 * 这一张解决的是"一次执行具体调哪个端点、传什么参数"。两者分开是因为它们的归属方不同：
 * 前者属于 DSL（validator 认的键），后者属于**桥**（端点与 Cap）。
 *
 * ## 为什么每个条目都必须有真实端点
 *
 * 卡片上写着「点击」，实际却拼一条 shell 去调别的东西，是最坏的形态：用户在界面上看到的是
 * A、设备上执行的是 B，而批准弹窗按 A 渲染。所以每个 [Action] 都带着**桥的真实 method/path**，
 * 门禁 `check-workflow-actions.js` 会把它和 [DshNativeBridge] 的 dispatch 表逐条对。
 *
 * ## 与 NativeCapCatalog 的关系
 *
 * [NativeCapCatalog] 是给「点一下抄个命令」用的文案目录（一张 Cap 一条命令）；
 * 这里是给**编辑器**用的结构化目录（一个 Cap 可以有多条动作，每条有各自的参数）。
 * 两者的 Cap 集合必须一致，但粒度不同：例如 A11Y 在文案目录里是一条，
 * 这里拆成读树 / 点击 / 滑动 / 输入 / 全局操作 / 截图六条。
 */
internal object WorkflowActionCatalog {

    /** 参数的种类。决定编辑器渲染什么控件、怎么校验。 */
    enum class ParamKind { TEXT, NUMBER, CHOICE, BOOL }

    /**
     * 一条动作的参数。
     *
     * [required] 为 true 时编辑器会标出来（空值仍能保存 —— 用户可能正写到一半；
     * 真正拦运行的是引擎与桥）。
     */
    data class Param(
        val key: String,
        val labelRes: Int,
        val kind: ParamKind,
        val choices: List<String> = emptyList(),
        val required: Boolean = false,
        val default: String = "",
    )

    /**
     * 一条可执行动作。
     *
     * [groupTitleRes] 与 [titleRes] 一起决定它在选择面板里的位置（与点击助手一样两级）：
     * 一级是「想干什么」（屏幕操作 / 提醒 / 设备…），二级不再细分，直接列出这一组里的动作。
     */
    data class Action(
        val id: String,
        val groupTitleRes: Int,
        val titleRes: Int,
        val descRes: Int,
        val cap: DshNativeBridge.Cap,
        val method: String,
        val path: String,
        val params: List<Param> = emptyList(),
    ) {
        /** 写进节点 config 的键前缀：动作 id 与参数键组合成唯一键，避免跨动作撞名。 */
        fun configKey(param: Param): String = "p_" + param.key
    }

    /**
     * 参数构造。
     *
     * [choices] 收 `List` 而不是 vararg，是因为调用点都在**具名参数**位置写它
     * （`p("action", R.string.x, ParamKind.CHOICE, choices = listOf(...))`）——
     * Kotlin 不允许在具名位置传 vararg，写成 `vararg` 会直接编译不过。
     */
    private fun p(
        key: String,
        labelRes: Int,
        kind: ParamKind = ParamKind.TEXT,
        required: Boolean = false,
        default: String = "",
        choices: List<String> = emptyList(),
    ) = Param(key, labelRes, kind, choices, required, default)

    /**
     * 全部动作。
     *
     * **顺序即界面顺序**。每个 path 都必须在 [DshNativeBridge] 的 dispatch 里真实存在 ——
     * 这不是注释约定，是门禁在核对。
     */
    val ACTIONS: List<Action> = listOf(
        // ── 屏幕操作：无障碍 ──
        Action(
            "a11y_tree", R.string.dsh_wf_grp_screen, R.string.dsh_cap_a11y_title, R.string.dsh_wf_act_a11y_tree,
            DshNativeBridge.Cap.A11Y, "GET", "/native/a11y/tree",
        ),
        Action(
            "a11y_click", R.string.dsh_wf_grp_screen, R.string.dsh_cap_a11y_title, R.string.dsh_wf_act_a11y_click,
            DshNativeBridge.Cap.A11Y, "POST", "/native/a11y/click",
            listOf(p("text", R.string.dsh_wf_param_text, required = true)),
        ),
        Action(
            "a11y_tap", R.string.dsh_wf_grp_screen, R.string.dsh_cap_a11y_title, R.string.dsh_wf_act_a11y_tap,
            DshNativeBridge.Cap.A11Y, "POST", "/native/a11y/tap",
            listOf(
                p("x", R.string.dsh_wf_param_x, ParamKind.NUMBER, required = true),
                p("y", R.string.dsh_wf_param_y, ParamKind.NUMBER, required = true),
            ),
        ),
        Action(
            "a11y_swipe", R.string.dsh_wf_grp_screen, R.string.dsh_cap_a11y_title, R.string.dsh_wf_act_a11y_swipe,
            DshNativeBridge.Cap.A11Y, "POST", "/native/a11y/swipe",
            listOf(
                p("x1", R.string.dsh_wf_param_x, ParamKind.NUMBER, required = true),
                p("y1", R.string.dsh_wf_param_y, ParamKind.NUMBER, required = true),
                p("x2", R.string.dsh_wf_param_x, ParamKind.NUMBER, required = true),
                p("y2", R.string.dsh_wf_param_y, ParamKind.NUMBER, required = true),
                p("durationMs", R.string.dsh_wf_param_duration, ParamKind.NUMBER, default = "300"),
            ),
        ),
        Action(
            "a11y_text", R.string.dsh_wf_grp_screen, R.string.dsh_cap_a11y_title, R.string.dsh_wf_act_a11y_text,
            DshNativeBridge.Cap.A11Y, "POST", "/native/a11y/text",
            listOf(p("text", R.string.dsh_wf_param_text, required = true)),
        ),
        Action(
            "a11y_global", R.string.dsh_wf_grp_screen, R.string.dsh_cap_a11y_title, R.string.dsh_wf_act_a11y_global,
            DshNativeBridge.Cap.A11Y, "POST", "/native/a11y/global",
            listOf(
                p(
                    "action", R.string.dsh_wf_param_action, ParamKind.CHOICE, required = true,
                    choices = listOf("back", "home", "recents", "notifications"),
                ),
            ),
        ),
        Action(
            "a11y_shot", R.string.dsh_wf_grp_screen, R.string.dsh_cap_a11y_title, R.string.dsh_wf_act_a11y_shot,
            DshNativeBridge.Cap.A11Y, "GET", "/native/a11y/screenshot",
        ),
        // ── 屏幕操作：虚拟屏 ──
        Action(
            "display_shot", R.string.dsh_wf_grp_screen, R.string.dsh_cap_display_title, R.string.dsh_wf_act_display_shot,
            DshNativeBridge.Cap.DISPLAY, "POST", "/native/display/screenshot",
        ),
        Action(
            "display_tap", R.string.dsh_wf_grp_screen, R.string.dsh_cap_display_title, R.string.dsh_wf_act_display_tap,
            DshNativeBridge.Cap.DISPLAY, "POST", "/native/display/tap",
            listOf(
                p("x", R.string.dsh_wf_param_x, ParamKind.NUMBER, required = true),
                p("y", R.string.dsh_wf_param_y, ParamKind.NUMBER, required = true),
            ),
        ),
        Action(
            "display_swipe", R.string.dsh_wf_grp_screen, R.string.dsh_cap_display_title, R.string.dsh_wf_act_display_swipe,
            DshNativeBridge.Cap.DISPLAY, "POST", "/native/display/swipe",
            listOf(
                p("x1", R.string.dsh_wf_param_x, ParamKind.NUMBER, required = true),
                p("y1", R.string.dsh_wf_param_y, ParamKind.NUMBER, required = true),
                p("x2", R.string.dsh_wf_param_x, ParamKind.NUMBER, required = true),
                p("y2", R.string.dsh_wf_param_y, ParamKind.NUMBER, required = true),
            ),
        ),
        Action(
            "display_key", R.string.dsh_wf_grp_screen, R.string.dsh_cap_display_title, R.string.dsh_wf_act_display_key,
            DshNativeBridge.Cap.DISPLAY, "POST", "/native/display/key",
            listOf(p("key", R.string.dsh_wf_param_key, required = true)),
        ),
        Action(
            "display_stop", R.string.dsh_wf_grp_screen, R.string.dsh_cap_display_title, R.string.dsh_wf_act_display_stop,
            DshNativeBridge.Cap.DISPLAY, "POST", "/native/display/stop",
        ),
        // ── 提醒 ──
        Action(
            "toast", R.string.dsh_wf_grp_alert, R.string.dsh_cap_toast_title, R.string.dsh_wf_act_toast,
            DshNativeBridge.Cap.TOAST, "POST", "/native/toast",
            listOf(p("text", R.string.dsh_wf_param_text, required = true)),
        ),
        Action(
            "vibrate", R.string.dsh_wf_grp_alert, R.string.dsh_cap_vibrate_title, R.string.dsh_wf_act_vibrate,
            DshNativeBridge.Cap.VIBRATE, "POST", "/native/vibrate",
            listOf(p("ms", R.string.dsh_wf_param_ms, ParamKind.NUMBER, default = "300")),
        ),
        Action(
            "notify_full", R.string.dsh_wf_grp_alert, R.string.dsh_cap_fullscreens_title, R.string.dsh_wf_act_notify_full,
            DshNativeBridge.Cap.FULL_SCREEN_NOTIFY, "POST", "/native/notify/full-screen",
            listOf(
                p("title", R.string.dsh_wf_param_notify_title, required = true),
                p("body", R.string.dsh_wf_param_notify_body),
            ),
        ),
        // ── 设备 ──
        Action(
            "clip_set", R.string.dsh_wf_grp_device, R.string.dsh_cap_clip_title, R.string.dsh_wf_act_clip_set,
            DshNativeBridge.Cap.CLIPBOARD, "POST", "/native/clipboard",
            listOf(p("text", R.string.dsh_wf_param_text, required = true)),
        ),
        Action(
            "torch", R.string.dsh_wf_grp_device, R.string.dsh_cap_torch_title, R.string.dsh_wf_act_torch,
            DshNativeBridge.Cap.TORCH, "POST", "/native/torch",
            listOf(p("on", R.string.dsh_wf_param_on, ParamKind.CHOICE, choices = listOf("on", "off"))),
        ),
        Action(
            "volume", R.string.dsh_wf_grp_device, R.string.dsh_cap_volume_title, R.string.dsh_wf_act_volume,
            DshNativeBridge.Cap.VOLUME, "POST", "/native/volume",
            listOf(
                p("level", R.string.dsh_wf_param_level, ParamKind.NUMBER, required = true),
                p(
                    "stream", R.string.dsh_wf_param_stream, ParamKind.CHOICE,
                    default = "music", choices = listOf("music", "ring", "alarm", "notification"),
                ),
            ),
        ),
        Action(
            "ringer", R.string.dsh_wf_grp_device, R.string.dsh_cap_volume_title, R.string.dsh_wf_act_ringer,
            DshNativeBridge.Cap.VOLUME, "POST", "/native/ringer",
            listOf(
                p(
                    "mode", R.string.dsh_wf_param_mode, ParamKind.CHOICE, required = true,
                    choices = listOf("normal", "silent", "vibrate"),
                ),
            ),
        ),
        Action(
            "brightness", R.string.dsh_wf_grp_device, R.string.dsh_cap_settings_title, R.string.dsh_wf_act_brightness,
            DshNativeBridge.Cap.SETTINGS, "POST", "/native/settings/brightness",
            listOf(p("value", R.string.dsh_wf_param_value, ParamKind.NUMBER, required = true)),
        ),
        Action(
            "rotation", R.string.dsh_wf_grp_device, R.string.dsh_cap_settings_title, R.string.dsh_wf_act_rotation,
            DshNativeBridge.Cap.SETTINGS, "POST", "/native/settings/rotation",
            listOf(p("locked", R.string.dsh_wf_param_locked, ParamKind.CHOICE, choices = listOf("0", "1"))),
        ),
        // ── 媒体 ──
        Action(
            "camera", R.string.dsh_wf_grp_media, R.string.dsh_cap_camera_title, R.string.dsh_wf_act_camera,
            DshNativeBridge.Cap.CAMERA, "POST", "/native/camera/photo",
            listOf(p("facing", R.string.dsh_wf_param_facing, ParamKind.CHOICE, default = "back", choices = listOf("back", "front"))),
        ),
        Action(
            "tts", R.string.dsh_wf_grp_media, R.string.dsh_cap_tts_title, R.string.dsh_wf_act_tts,
            DshNativeBridge.Cap.TTS, "POST", "/native/tts/speak",
            listOf(p("text", R.string.dsh_wf_param_text, required = true)),
        ),
        Action(
            "media_list", R.string.dsh_wf_grp_media, R.string.dsh_cap_media_title, R.string.dsh_wf_act_media_list,
            DshNativeBridge.Cap.MEDIA, "GET", "/native/media/list",
            listOf(
                p("type", R.string.dsh_wf_param_type, ParamKind.CHOICE, default = "image", choices = listOf("image", "video", "audio")),
                p("limit", R.string.dsh_wf_param_limit, ParamKind.NUMBER, default = "10"),
            ),
        ),
        // ── 感知 ──
        Action(
            "sensors_read", R.string.dsh_wf_grp_sense, R.string.dsh_cap_sensors_title, R.string.dsh_wf_act_sensors_read,
            DshNativeBridge.Cap.SENSORS, "GET", "/native/sensors/read",
            listOf(p("id", R.string.dsh_wf_param_id, required = true)),
        ),
        Action(
            "network", R.string.dsh_wf_grp_sense, R.string.dsh_cap_network_title, R.string.dsh_wf_act_network,
            DshNativeBridge.Cap.NETWORK, "GET", "/native/network",
        ),
        Action(
            "usage", R.string.dsh_wf_grp_sense, R.string.dsh_cap_usage_title, R.string.dsh_wf_act_usage,
            DshNativeBridge.Cap.USAGE, "GET", "/native/usage/list",
            listOf(
                p("days", R.string.dsh_wf_param_days, ParamKind.NUMBER, default = "1"),
                p("limit", R.string.dsh_wf_param_limit, ParamKind.NUMBER, default = "10"),
            ),
        ),
        // ── 数据 ──
        Action(
            "calendar_list", R.string.dsh_wf_grp_data, R.string.dsh_cap_calendar_title, R.string.dsh_wf_act_calendar_list,
            DshNativeBridge.Cap.CALENDAR, "GET", "/native/calendar/list",
            listOf(p("days", R.string.dsh_wf_param_days, ParamKind.NUMBER, default = "7")),
        ),
        Action(
            "contacts_list", R.string.dsh_wf_grp_data, R.string.dsh_cap_contacts_title, R.string.dsh_wf_act_contacts_list,
            DshNativeBridge.Cap.CONTACTS, "GET", "/native/contacts/list",
            listOf(p("limit", R.string.dsh_wf_param_limit, ParamKind.NUMBER, default = "20")),
        ),
        Action(
            "location", R.string.dsh_wf_grp_data, R.string.dsh_cap_location_title, R.string.dsh_wf_act_location,
            DshNativeBridge.Cap.LOCATION, "GET", "/native/location",
        ),
        Action(
            "sms_list", R.string.dsh_wf_grp_data, R.string.dsh_cap_sms_title, R.string.dsh_wf_act_sms_list,
            DshNativeBridge.Cap.SMS, "GET", "/native/sms/list",
            listOf(p("limit", R.string.dsh_wf_param_limit, ParamKind.NUMBER, default = "10")),
        ),
    )

    /** 按一级分组（界面按这个顺序铺 chip）。 */
    fun byGroup(): List<Pair<Int, List<Action>>> {
        val out = LinkedHashMap<Int, MutableList<Action>>()
        for (a in ACTIONS) out.getOrPut(a.groupTitleRes) { mutableListOf() }.add(a)
        return out.map { it.key to it.value }
    }

    fun byId(id: String): Action? = if (id.isBlank()) null else ACTIONS.firstOrNull { it.id == id }
}
