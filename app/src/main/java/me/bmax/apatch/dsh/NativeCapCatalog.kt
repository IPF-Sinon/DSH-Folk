package me.bmax.apatch.dsh

import me.bmax.apatch.R

/**
 * 原生能力的**目录**：把 `dsh-native` 的每一项能力整理成「分类 → 卡片」两级，
 * 供工作流编辑页像「点击助手」那样点选，而不是让用户自己拼命令行。
 *
 * ## 为什么需要它
 * 桥能力已经有 28 项（通知/剪贴板/相机/TTS/日历/通讯录/传感器/无障碍/虚拟屏…），
 * 全部挤在一个下拉框里既找不到、也记不住该配什么参数。用户真正需要的是：
 * 「我想让 agent 发个通知」→ 在「提醒与提示」里点「通知」→ 填标题和正文。
 *
 * ## 与 [DshNativeBridge.Cap] 的关系
 * **每个条目都必须对应一个真实存在的 Cap**（[CapItem.cap] 非空时）。
 * 这是刻意的：目录是给人看的说明书，而 Cap 是真正的权限与审计单位 ——
 * 两者对不上时，用户点了卡片却会被权限检查拦下，那比没有卡片更糟。
 * 门禁 `check-native-cli.js` 会核对这件事。
 */
internal object NativeCapCatalog {

    /** 卡片所属的分类（顺序即界面上的分组顺序）。 */
    enum class Group(val titleRes: Int) {
        /** 提醒与提示：通知、Toast、振动、全屏提醒。 */
        ALERT(R.string.dsh_cap_group_alert),
        /** 设备与输入：剪贴板、手电筒、音量、响铃、系统设置。 */
        DEVICE(R.string.dsh_cap_group_device),
        /** 媒体：相册、录音、拍照、语音合成。 */
        MEDIA(R.string.dsh_cap_group_media),
        /** 数据与个人信息：日历、通讯录、短信、位置、电话状态。 */
        DATA(R.string.dsh_cap_group_data),
        /** 感知：传感器、网络、使用统计、设备信息。 */
        SENSE(R.string.dsh_cap_group_sense),
        /** 屏幕操作：无障碍与虚拟屏（两条不同的路）。 */
        SCREEN(R.string.dsh_cap_group_screen),
        /** 特权：shell 与 SAF。 */
        PRIVILEGE(R.string.dsh_cap_group_privilege),
    }

    /**
     * 一张能力卡片。
     *
     * [command] 是**可以直接抄进工作流步骤**的 `dsh-native` 命令骨架（不含 `--reason`，
     * 那个由工作流引擎在执行时补上，因为它每次都不一样）。
     * [usage] 是给人看的一句话用法；[argsHint] 提醒还需要补哪些参数。
     */
    data class CapItem(
        val cap: DshNativeBridge.Cap,
        val group: Group,
        val titleRes: Int,
        val descRes: Int,
        val command: String,
        val argsHint: String = "",
    )

    /**
     * 全部卡片。
     *
     * 顺序即界面顺序。**每一条都必须对应真实的 Cap**（见类注释）。
     */
    val ITEMS: List<CapItem> = listOf(
        // ── 提醒与提示 ──
        CapItem(
            cap = DshNativeBridge.Cap.NOTIFY,
            group = Group.ALERT,
            titleRes = R.string.dsh_cap_notify_title,
            descRes = R.string.dsh_cap_notify_desc,
            command = "dsh-native notify \"标题\" \"正文\"",
            argsHint = "标题与正文",
        ),
        CapItem(
            cap = DshNativeBridge.Cap.TOAST,
            group = Group.ALERT,
            titleRes = R.string.dsh_cap_toast_title,
            descRes = R.string.dsh_cap_toast_desc,
            command = "dsh-native toast \"提示文字\"",
            argsHint = "提示文字",
        ),
        CapItem(
            cap = DshNativeBridge.Cap.VIBRATE,
            group = Group.ALERT,
            titleRes = R.string.dsh_cap_vibrate_title,
            descRes = R.string.dsh_cap_vibrate_desc,
            command = "dsh-native vibrate --ms 300",
            argsHint = "时长（毫秒）",
        ),
        CapItem(
            cap = DshNativeBridge.Cap.FULL_SCREEN_NOTIFY,
            group = Group.ALERT,
            titleRes = R.string.dsh_cap_fullscreens_title,
            descRes = R.string.dsh_cap_fullscreens_desc,
            command = "dsh-native notify-full-screen \"标题\" \"正文\"",
            argsHint = "标题与正文（紧急提醒，会占满屏幕）",
        ),
        // ── 设备与输入 ──
        CapItem(
            cap = DshNativeBridge.Cap.CLIPBOARD,
            group = Group.DEVICE,
            titleRes = R.string.dsh_cap_clip_title,
            descRes = R.string.dsh_cap_clip_desc,
            command = "dsh-native clip set \"文本\"",
            argsHint = "要写入剪贴板的文本（读用 clip get）",
        ),
        CapItem(
            cap = DshNativeBridge.Cap.TORCH,
            group = Group.DEVICE,
            titleRes = R.string.dsh_cap_torch_title,
            descRes = R.string.dsh_cap_torch_desc,
            command = "dsh-native torch on",
            argsHint = "on 或 off",
        ),
        CapItem(
            cap = DshNativeBridge.Cap.VOLUME,
            group = Group.DEVICE,
            titleRes = R.string.dsh_cap_volume_title,
            descRes = R.string.dsh_cap_volume_desc,
            command = "dsh-native volume set 50 --stream music",
            argsHint = "0~100 与音频流（music/ring/alarm/notification）",
        ),
        CapItem(
            cap = DshNativeBridge.Cap.SETTINGS,
            group = Group.DEVICE,
            titleRes = R.string.dsh_cap_settings_title,
            descRes = R.string.dsh_cap_settings_desc,
            command = "dsh-native settings brightness 50",
            argsHint = "亮度 1~100 / 休眠 ms / 旋转 0|1",
        ),
        // ── 媒体 ──
        CapItem(
            cap = DshNativeBridge.Cap.CAMERA,
            group = Group.MEDIA,
            titleRes = R.string.dsh_cap_camera_title,
            descRes = R.string.dsh_cap_camera_desc,
            command = "dsh-native camera photo --facing back",
            argsHint = "前/后置；照片落在容器 /tmp",
        ),
        CapItem(
            cap = DshNativeBridge.Cap.MIC,
            group = Group.MEDIA,
            titleRes = R.string.dsh_cap_mic_title,
            descRes = R.string.dsh_cap_mic_desc,
            command = "dsh-native mic record --ms 5000",
            argsHint = "录音时长（最长 30000 毫秒）",
        ),
        CapItem(
            cap = DshNativeBridge.Cap.MEDIA,
            group = Group.MEDIA,
            titleRes = R.string.dsh_cap_media_title,
            descRes = R.string.dsh_cap_media_desc,
            command = "dsh-native media list --type image --limit 10",
            argsHint = "类型 image/video/audio 与数量上限",
        ),
        CapItem(
            cap = DshNativeBridge.Cap.TTS,
            group = Group.MEDIA,
            titleRes = R.string.dsh_cap_tts_title,
            descRes = R.string.dsh_cap_tts_desc,
            command = "dsh-native tts say \"文字\"",
            argsHint = "要朗读的文字（可加 --lang/--rate/--pitch）",
        ),
        // ── 数据与个人信息 ──
        CapItem(
            cap = DshNativeBridge.Cap.CALENDAR,
            group = Group.DATA,
            titleRes = R.string.dsh_cap_calendar_title,
            descRes = R.string.dsh_cap_calendar_desc,
            command = "dsh-native calendar list --days 7",
            argsHint = "查看未来 N 天；新建用 calendar add",
        ),
        CapItem(
            cap = DshNativeBridge.Cap.CONTACTS,
            group = Group.DATA,
            titleRes = R.string.dsh_cap_contacts_title,
            descRes = R.string.dsh_cap_contacts_desc,
            command = "dsh-native contacts list --limit 20",
            argsHint = "可加 --q 按名字或号码筛选（只读）",
        ),
        CapItem(
            cap = DshNativeBridge.Cap.LOCATION,
            group = Group.DATA,
            titleRes = R.string.dsh_cap_location_title,
            descRes = R.string.dsh_cap_location_desc,
            command = "dsh-native location",
            argsHint = "可加 --maxAge / --wait（毫秒）",
        ),
        CapItem(
            cap = DshNativeBridge.Cap.SMS,
            group = Group.DATA,
            titleRes = R.string.dsh_cap_sms_title,
            descRes = R.string.dsh_cap_sms_desc,
            command = "dsh-native sms list --limit 10",
            argsHint = "读最近短信；发送用 sms send <号码> <内容>",
        ),
        // ── 感知 ──
        CapItem(
            cap = DshNativeBridge.Cap.SENSORS,
            group = Group.SENSE,
            titleRes = R.string.dsh_cap_sensors_title,
            descRes = R.string.dsh_cap_sensors_desc,
            command = "dsh-native sensors read light",
            argsHint = "传感器 id（先 sensors list 查看）",
        ),
        CapItem(
            cap = DshNativeBridge.Cap.NETWORK,
            group = Group.SENSE,
            titleRes = R.string.dsh_cap_network_title,
            descRes = R.string.dsh_cap_network_desc,
            command = "dsh-native network",
            argsHint = "只读：传输方式 / 是否联网 / 是否计费",
        ),
        CapItem(
            cap = DshNativeBridge.Cap.USAGE,
            group = Group.SENSE,
            titleRes = R.string.dsh_cap_usage_title,
            descRes = R.string.dsh_cap_usage_desc,
            command = "dsh-native usage list --days 1 --limit 10",
            argsHint = "最近 N 天的前台使用情况",
        ),
        CapItem(
            cap = DshNativeBridge.Cap.DEVICE,
            group = Group.SENSE,
            titleRes = R.string.dsh_cap_device_title,
            descRes = R.string.dsh_cap_device_desc,
            command = "dsh-native device",
            argsHint = "设备指纹（型号/系统/ABI 等）",
        ),
        // ── 屏幕操作 ──
        CapItem(
            cap = DshNativeBridge.Cap.A11Y,
            group = Group.SCREEN,
            titleRes = R.string.dsh_cap_a11y_title,
            descRes = R.string.dsh_cap_a11y_desc,
            command = "dsh-native a11y tree",
            argsHint = "读当前屏幕节点树；点击用 a11y click <文字>",
        ),
        CapItem(
            cap = DshNativeBridge.Cap.DISPLAY,
            group = Group.SCREEN,
            titleRes = R.string.dsh_cap_display_title,
            descRes = R.string.dsh_cap_display_desc,
            command = "dsh-native display shot",
            argsHint = "截屏到 /tmp；点击用 display tap <x> <y>",
        ),
        // ── 特权 ──
        CapItem(
            cap = DshNativeBridge.Cap.SHELL,
            group = Group.PRIVILEGE,
            titleRes = R.string.dsh_cap_shell_title,
            descRes = R.string.dsh_cap_shell_desc,
            command = "dsh-native shell getprop ro.product.model",
            argsHint = "要执行的特权命令（--su 走 root 域）",
        ),
        CapItem(
            cap = DshNativeBridge.Cap.SAF,
            group = Group.PRIVILEGE,
            titleRes = R.string.dsh_cap_saf_title,
            descRes = R.string.dsh_cap_saf_desc,
            command = "dsh-native saf list \"content://...\"",
            argsHint = "第三方提供器的 content:// 目录",
        ),
    )

    /** 按分类分组（界面按这个顺序铺）。 */
    fun byGroup(): List<Pair<Group, List<CapItem>>> =
        Group.entries.map { g -> g to ITEMS.filter { it.group == g } }
            .filter { it.second.isNotEmpty() }
}
