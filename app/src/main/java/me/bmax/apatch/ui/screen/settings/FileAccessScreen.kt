package me.bmax.apatch.ui.screen.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshFileAccess
import me.bmax.apatch.dsh.DshFileAccess.PickerHostDir
import me.bmax.apatch.dsh.DshRuntime
import me.bmax.apatch.util.ui.showToast
import java.io.File

/**
 * 「文件访问范围」子页：管理容器可访问手机目录的黑白名单。
 *
 * 规则见 [DshFileAccess]。改动只写偏好，**真正生效在容器启动那一刻的 bind 挂载**，所以
 * 名单一改就显示「需重启 DSH」横幅，用户点「重启 DSH」（[DshRuntime.restart]）后新挂载才生效。
 */
@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileAccessScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current

    // 初始快照，用来判断「有没有改动过、要不要提示重启」
    val initialAllow = remember { DshFileAccess.allowDirs(context) }
    val initialDeny = remember { DshFileAccess.denyDirs(context) }
    val initialWsMounts = remember { DshFileAccess.workspaceMounts(context) }
    val allow = remember { mutableStateListOf<String>().apply { addAll(initialAllow) } }
    val deny = remember { mutableStateListOf<String>().apply { addAll(initialDeny) } }
    // 「挂进工作区」没有自己的开关了（2026-10 与共享存储合并成一个）：这里只显示它现在生不生效
    val storageMount = remember { DshFileAccess.mountEnabled(context) }
    val wsMounts = remember {
        mutableStateListOf<DshFileAccess.WsMount>().apply { addAll(initialWsMounts) }
    }

    // 目录选择器：pickerFor = "allow" | "deny" | "ws" | null
    var pickerFor by remember { mutableStateOf<String?>(null) }
    // 工作区映射：选完 src 后填 dest 的挂起态。主卷是"相对 /sdcard"的路径，第二卷（SD/U 盘）
    // 是真实宿主绝对路径 —— 两种都由 DshFileAccess 落盘时各归各的形态（见 WsMount）。
    var wsPendingSrc by remember { mutableStateOf<String?>(null) }
    // 共享存储是否支持真硬链接（决定要不要提示「write 工具在此会失败」）。可在页内重新检测。
    var storageLinkOk by remember { mutableStateOf(DshFileAccess.storageLinkSupported(context)) }
    // 系统文件选择器失败时的现场（失败原因 + 小字号诊断）。以前只弹一句 Toast：一闪就没、
    // 也装不下 authority / document id / 根信息这几行 —— 用户报障时既截不到图也抄不全。
    var pickerFailure by remember { mutableStateOf<PickerFailure?>(null) }

    val dirty = allow.toList() != initialAllow || deny.toList() != initialDeny ||
        wsMounts.toList() != initialWsMounts

    fun persist() {
        DshFileAccess.setAllowDirs(context, allow.toList())
        DshFileAccess.setDenyDirs(context, deny.toList())
        DshFileAccess.setWorkspaceMounts(context, wsMounts.toList())
    }

    fun addTo(which: String, rel: String) {
        val target = if (which == "allow") allow else deny
        val merged = DshFileAccess.normalize(target.toList() + rel)
        target.clear(); target.addAll(merged)
        persist()
    }

    fun addWsMount(src: String, dest: String) {
        val d = DshFileAccess.normalizeDest(dest)
        // 同一 dest 只留一条：先移除既有同名，再追加
        val filtered = wsMounts.filter { it.dest != d }
        wsMounts.clear()
        wsMounts.addAll(filtered)
        wsMounts.add(DshFileAccess.WsMount(src, d))
        persist()
    }

    /**
     * 选完目录之后的共同落点：页内浏览器与系统文件选择器**走同一条**路（先选 src、再填 dest，
     * 或直接进黑白名单），所以只写这一处。
     *
     * [rel] 对黑白名单永远是"相对 /sdcard"的路径（第二卷在那两处会被挡在前面）；对工作区映射
     * 则可能是主卷的相对路径，也可能是第二卷的真实宿主绝对路径（`/storage/<卷>/…`）。
     */
    fun handlePicked(rel: String) {
        val which = pickerFor
        pickerFor = null
        when (which) {
            // 工作区映射：选完 src，接着填 dest（根 = 整棵 /sdcard，src 为空串）
            "ws" -> wsPendingSrc = rel
            // 黑白名单要求进到子目录再选：根目录对这两份名单没有意义
            // （白名单空本来就是"全放行"；黑名单加根会把整棵树都禁掉）
            "allow", "deny" ->
                if (rel.isEmpty()) {
                    showToast(context, context.getString(R.string.dsh_fs_picker_need_subdir))
                } else {
                    addTo(which, rel)
                }
        }
    }

    /**
     * 系统文件选择器失败的统一出口：把失败原因与**诊断**一起弹出来（不再只弹一句 Toast）。
     *
     * 诊断（[DshFileAccess.pickerDiagnostics]）里带 authority / tree document id / 根信息 /
     * 我们走到哪一步；它自己也会把同一段写进 `Log.i("DshPicker", …)`，所以截图与日志逐字相同。
     * 判断标准始终是"DshFileAccess 说挂不了就不挂"，这里只负责把现场交给用户。
     */
    fun failPicker(uri: Uri?, message: String) {
        pickerFor = null
        pickerFailure = PickerFailure(message, DshFileAccess.pickerDiagnostics(context, uri))
    }

    /**
     * 系统文件选择器（`ACTION_OPEN_DOCUMENT_TREE`）：不限定提供器 —— 系统文件、MT 管理器、
     * 其它 SAF 提供器都能进来选目录，页内那个只能翻 /sdcard 的浏览器不再是唯一入口。
     *
     * 两件事必须做对：
     * 1. **持久化授权**：拿到 tree URI 立刻 `takePersistableUriPermission`（授权位原样 take）。
     *    不 take 的话，进程重启后这个 URI 就失效了 —— 而用户选它正是为了"以后一直能用"。
     *    发起时也带上 persistable/prefix 两个 flag（与「允许第三方应用访问数据目录」那条路
     *    完全相同的写法，见 FunctionSettings 的 `function_docs_access`）。
     * 2. **换算成能挂的宿主路径**再交给 [handlePicked]。三条已知约定按顺序试：
     *    主卷（externalstorage 的 `primary:`）换成"相对 /sdcard"；真第二卷（SD 卡 / U 盘）
     *    换成真实宿主绝对路径 `/storage/<卷>/…`（见 [DshFileAccess.hostPathFromTreeUri]）；
     *    其余提供器交给 [DshFileAccess.resolvePickerHostDir] —— 自家文档提供器、`raw:` /
     *    绝对路径形状、形状不足为凭时的子项名交叉验证，最后都过同一条判据：**解析到底**
     *    （canonical 跟随软链，见 [DshFileAccess.resolveHostDir]）、存在、是目录、
     *    **本应用读得到**（[DshFileAccess.hostDirReadable]，`listFiles()` 非 null）。
     *    这里**不再要求路径落在 /storage 之下、也不因为尾段有软链而拒** —— 第三方提供器
     *    （Termux、DSHA、FCL…）的真实路径本来就在 `/data/data/<包名>/…`、应用专属外部目录
     *    或 SD 卷里（`/storage/<卷>` 自己都可能是个软链），按根筛、按"规范形态"筛都是成片的
     *    误拒。
     *    换出来的绝对路径若落在主卷里，还会折回"相对 /sdcard"（见
     *    [DshFileAccess.relativeUnderHostRoot]）——与页内浏览器选同一个目录是同一种形态，
     *    黑白名单也照常生效。
     * 3. **用不了就不挂**：真实路径**推出来了**但用不了（不存在 / 不是目录 / 读不到：别的应用
     *    的私有数据、没拿到「所有文件访问」）时，给
     *    [R.string.dsh_fs_picker_unreadable_host_dir] 的提示，不静默挂一个空目录。四种情形
     *    因此各说各的：**压根推不出**真实路径 →「这个提供器不给出真实路径」；**推出来但用不了**
     *    →「本应用用不了」；**推出来、也存在，但与 SAF 那棵树对不上**（[PickerHostDir.Unverified]）
     *    →「推出可能是 X，但确认不了」并把路径显示出来；系统存储提供器（externalstorage）的
     *    卷/目录不在了 → 它自己那句「卷或目录已经不在了」（更精确，优先）。
     *
     * 四种失败都走 [failPicker]：提示框里除了原因还贴一段小字号诊断（authority / tree document id /
     * 显示名 / 根信息 / 我们走到哪一步），用户截一张图就能把现场交回来（见
     * [DshFileAccess.pickerDiagnostics]）。
     */
    val systemPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val uri = data?.data
        // data 也要一起判空：`ActivityResult.data` 是可空的 Intent，只判 uri 的话
        // 下面读 `data.flags` 过不了编译（可空接收者）。
        if (result.resultCode != android.app.Activity.RESULT_OK || data == null || uri == null) {
            return@rememberLauncherForActivityResult
        }
        val take = data.flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        if (take != 0) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, take) }
        }
        // 主卷（externalstorage 的 `primary:`）：换算成"相对 /sdcard"，与页内浏览器选同一个
        // 目录是同一种形态，黑白名单照常生效。存在但读不到（没权限时的 FUSE 拒绝）同样不挂。
        val rel = DshFileAccess.relativeFromTreeUri(uri)
        if (rel != null) {
            if (DshFileAccess.hostDirReadable(DshFileAccess.wsHostDir(rel))) {
                handlePicked(rel)
            } else {
                failPicker(uri, context.getString(R.string.dsh_fs_picker_unreadable_host_dir))
            }
            return@rememberLauncherForActivityResult
        }
        // 主卷之外再看别的来源：第二卷（SD 卡 / U 盘）由 hostPathFromTreeUri 给出真实宿主绝对
        // 路径；其余提供器（自家 / 第三方）交给 resolvePickerHostDir 判定。两处的结果都按
        // "用得了吗"这一条收口 —— 用不了就是 Unreadable，不再往下走。
        val resolved = when (val volPath = DshFileAccess.hostPathFromTreeUri(uri)) {
            null -> DshFileAccess.resolvePickerHostDir(context, uri)
            else -> if (DshFileAccess.hostDirReadable(volPath)) {
                PickerHostDir.Mountable(volPath)
            } else {
                PickerHostDir.Unreadable(volPath)
            }
        }
        // 主卷内的绝对路径折回"相对 /sdcard"：与页内浏览器选同一个目录时是同一种形态，
        // 黑白名单也因此照常生效（绝对形态在挂载层不套名单，见 DshFileAccess.workspaceBinds）。
        val hostPath = when (resolved) {
            is PickerHostDir.Mountable -> resolved.path
            is PickerHostDir.Unreadable -> resolved.path
            // 只为了把推出来的路径显示给用户；下面第一处就拦掉，绝不按它挂载
            is PickerHostDir.Unverified -> resolved.path
            PickerHostDir.NoPath -> null
        }
        val sdcardRel = hostPath?.let { DshFileAccess.relativeUnderHostRoot(it) }
        when {
            // 系统存储提供器（externalstorage）的树现在不成立：卡没插 / 目录已不在 / id 里带 ..
            // —— 这几条它自己就有更精确的说法（"卷或目录已经不在了"），优先于下面那句笼统的
            // "本应用用不了"。
            DshFileAccess.isExternalStorageTree(uri) && resolved !is PickerHostDir.Mountable -> {
                failPicker(uri, context.getString(R.string.dsh_fs_picker_volume_missing))
            }
            // 真实路径推出来了但用不了（别的应用的私有数据 / 没权限 / 已经不在了）：不挂
            resolved is PickerHostDir.Unreadable -> {
                failPicker(uri, context.getString(R.string.dsh_fs_picker_unreadable_host_dir))
            }
            // 路径推出来了、候选目录也真实存在，但与 SAF 那棵树对不上：不挂。以前这里报的是
            // "提供器不给出真实路径" —— 与自己算出来的事实矛盾，也丢掉了唯一的诊断线索。
            // 这一支必须排在下面任何"挂载"分支之前。
            resolved is PickerHostDir.Unverified -> {
                failPicker(
                    uri,
                    context.getString(R.string.dsh_fs_picker_unverified_path, resolved.path),
                )
            }
            // 主卷内的目录：走和 externalstorage primary 完全相同的那条路
            sdcardRel != null -> handlePicked(sdcardRel)
            // 挂载映射按"具体目录"走，能直接吃下这个绝对路径
            hostPath != null && pickerFor == "ws" -> handlePicked(hostPath)
            // 黑白名单存的只有"相对 /sdcard"的条目，别的宿主路径不是那棵树；指路，不含糊
            hostPath != null -> {
                failPicker(uri, context.getString(R.string.dsh_fs_picker_list_needs_sdcard))
            }
            // 真·虚拟提供器（云盘 / 相册 SPA / 不透明 id）：id 里压根推不出真实路径，挂不了
            else -> {
                failPicker(uri, context.getString(R.string.dsh_fs_picker_no_local_path))
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.dsh_fs_access_title)) },
            navigationIcon = {
                IconButton(onClick = { navigator.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                }
            },
        )
    }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.dsh_fs_access_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (dirty) {
                Spacer(Modifier.height(12.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.dsh_fs_restart_needed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { persist(); DshRuntime.restart() }) {
                            Text(stringResource(R.string.dsh_fs_restart_now))
                        }
                    }
                }
            }

            // ── 白名单 ──
            Spacer(Modifier.height(16.dp))
            DirListSection(
                header = stringResource(R.string.dsh_fs_allow_header),
                entries = allow,
                emptyHint = stringResource(R.string.dsh_fs_empty_allow),
                onAdd = { pickerFor = "allow" },
                onRemove = { allow.remove(it); persist() },
            )

            // ── 黑名单 ──
            Spacer(Modifier.height(16.dp))
            DirListSection(
                header = stringResource(R.string.dsh_fs_deny_header),
                entries = deny,
                emptyHint = stringResource(R.string.dsh_fs_empty_deny),
                onAdd = { pickerFor = "deny" },
                onRemove = { deny.remove(it); persist() },
                extra = {
                    TextButton(onClick = {
                        deny.clear(); deny.addAll(DshFileAccess.DEFAULT_DENY); persist()
                    }) { Text(stringResource(R.string.dsh_fs_reset_deny_default)) }
                },
            )

            // ── 挂载进工作区 ──
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.dsh_ws_mount_header),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.dsh_ws_mount_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 生效与否只看「共享存储」那一个开关（功能 → 共享存储）。这里说的是"现在生不生效"，
            // 而不是再给一个开关 —— 两个开关管同一件事，就一定会出现自相矛盾的状态。
            Text(
                stringResource(
                    if (storageMount) R.string.dsh_ws_mount_follows_on
                    else R.string.dsh_ws_mount_follows_off
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (storageMount) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
            run {
                Spacer(Modifier.height(8.dp))
                if (wsMounts.isEmpty()) {
                    Text(
                        text = stringResource(R.string.dsh_ws_mount_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    for (m in wsMounts) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    // 主卷显示成 /sdcard/…；第二卷（SD/U 盘）显示它真实的宿主路径
                                    text = stringResource(R.string.dsh_ws_mount_source) + "  " +
                                        DshFileAccess.wsSourceLabel(m.src),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    text = stringResource(R.string.dsh_ws_mount_destination) +
                                        "  /root/workspace/" + m.dest,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { wsMounts.remove(m); persist() }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.dsh_fs_remove))
                            }
                        }
                    }
                }
                // 详细限制注入 AI 的宿主提示词；这里给人类保留紧凑提醒，避免占满屏幕。
                //
                // 整段都只在**探测到共享存储不支持硬链接**时出现。以前这条 note 漏在 if 外面：
                // 设备上硬链接其实可用（storageLinkOk == true，上面的"重新检测"行也因此不显示），
                // 这段"无硬链接/无 exec 位"的警告却照样挂着 —— 与事实不符，也与宿主提示词那一侧
                // （storageHardlinkSupported === false 才渲染）不一致。
                if (!storageLinkOk) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.dsh_ws_mount_warn_title),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            DshFileAccess.resetStorageLinkProbe()
                            storageLinkOk = DshFileAccess.storageLinkSupported(context)
                        }) { Text(stringResource(R.string.dsh_ws_mount_recheck)) }
                    }
                    Text(
                        text = stringResource(R.string.dsh_ws_mount_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                TextButton(onClick = { pickerFor = "ws" }) {
                    Text(stringResource(R.string.dsh_ws_mount_add))
                }
            }
        }
    }

    if (pickerFor != null) {
        DirPickerDialog(
            allowRoot = pickerFor == "ws",
            onDismiss = { pickerFor = null },
            onPick = { rel -> handlePicked(rel) },
            // 左下角那个按钮：换系统文件选择器（不限定提供器），选回来走同一条落盘路
            onPickSystem = {
                systemPicker.launch(
                    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    )
                )
            },
        )
    }

    if (wsPendingSrc != null) {
        WsDestDialog(
            src = wsPendingSrc!!,
            onDismiss = { wsPendingSrc = null },
            onConfirm = { dest ->
                val src = wsPendingSrc!!
                wsPendingSrc = null
                addWsMount(src, dest)
            },
        )
    }

    pickerFailure?.let { f ->
        PickerFailureDialog(failure = f, onDismiss = { pickerFailure = null })
    }
}

/** 系统文件选择器失败时的现场：给用户看的那句原因 + 小字号诊断（可截图 / 可复制）。 */
private data class PickerFailure(val message: String, val diagnostics: String)

/**
 * 「这个目录挂不了」的提示框。
 *
 * 为什么不是 Toast：失败原因要**连诊断一起**给（哪家提供器、什么 document id、根信息、
 * 我们走到哪一步）—— Toast 一闪就没、也装不下这几行，用户报障时既截不到图也抄不全。
 *
 * 诊断用等宽小字、可选中复制，并在纵向/横向上都能滚（长 document id 与长路径不会被裁掉，
 * 也不会把对话框顶出屏幕）。内容与 `Log.i("DshPicker", …)` 那段逐字相同（见
 * [DshFileAccess.pickerDiagnostics]）——用户截图回来，我们和日志一比对就知道是不是同一份。
 */
@Composable
private fun PickerFailureDialog(failure: PickerFailure, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dsh_fs_picker_diag_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(failure.message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.dsh_fs_picker_diag_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                SelectionContainer {
                    Text(
                        text = failure.diagnostics,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
    )
}

@Composable
private fun DirListSection(
    header: String,
    entries: List<String>,
    emptyHint: String,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    Text(header, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    if (entries.isEmpty()) {
        Text(
            text = emptyHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        for (e in entries) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = e,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onRemove(e) }) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.dsh_fs_remove))
                }
            }
        }
    }
    Row {
        TextButton(onClick = onAdd) { Text(stringResource(R.string.dsh_fs_add_dir)) }
        extra?.invoke()
    }
}

/** 工作区映射：选完 src 后填「工作区下目的子路径」的对话框（默认预填 sdcard）。 */
@Composable
private fun WsDestDialog(
    src: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var dest by remember { mutableStateOf("sdcard") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dsh_ws_mount_dest_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    // src 可能是"相对 /sdcard"的路径，也可能是第二卷的真实宿主绝对路径，
                    // 显示换算交给 DshFileAccess 那一处（不在界面里各拼一份）
                    text = DshFileAccess.wsSourceLabel(src),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = dest,
                    onValueChange = { dest = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.dsh_ws_mount_dest_label)) },
                    prefix = { Text("/root/workspace/") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.dsh_ws_mount_dest_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = dest.isNotBlank(),
                onClick = { onConfirm(dest) },
            ) { Text(stringResource(R.string.dsh_fs_picker_choose)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/** 极简目录浏览器：直接用 java.io.File 遍历 /sdcard（App 已有「所有文件访问」时才列得出）。 */
@Composable
private fun DirPickerDialog(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    /** 左下角：切到系统文件选择器（`ACTION_OPEN_DOCUMENT_TREE`），可选任意提供器的目录。 */
    onPickSystem: () -> Unit,
    /** 允许直接选「当前目录」（含根 /sdcard）——工作区映射用它把整棵 /sdcard 挂进去。 */
    allowRoot: Boolean = false,
) {
    val root = remember { Environment.getExternalStorageDirectory() }
    val hasPerm = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true
    }
    var current by remember { mutableStateOf(root) }
    // 相对 /sdcard 的相对路径（根为空串）
    fun relOf(f: File): String = f.absolutePath.removePrefix(root.absolutePath).trim('/')
    val subDirs = remember(current, hasPerm) {
        if (!hasPerm) emptyList()
        else (current.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() } ?: emptyList())
    }
    val atRoot = current.absolutePath == root.absolutePath

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dsh_fs_picker_title)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                Text(
                    text = "/sdcard/" + relOf(current),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (!hasPerm) {
                    Text(
                        text = stringResource(R.string.dsh_fs_picker_need_perm),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        if (!atRoot) {
                            item {
                                Text(
                                    text = stringResource(R.string.dsh_fs_picker_up),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { current.parentFile?.let { current = it } }
                                        .padding(vertical = 10.dp),
                                )
                            }
                        }
                        items(subDirs) { d ->
                            Row(
                                Modifier.fillMaxWidth().clickable { current = d }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(d.name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                // 工作区映射允许选根（整棵 /sdcard，src=""）；黑白名单仍要求进到子目录再选
                enabled = hasPerm && (allowRoot || !atRoot),
                onClick = { onPick(relOf(current)) },
            ) { Text(stringResource(R.string.dsh_fs_picker_choose)) }
        },
        dismissButton = {
            // 左下角：换系统文件选择器。页内这个浏览器只能翻 /sdcard（而且要有「所有文件访问」），
            // 系统选择器不限定提供器 —— 系统文件、MT 管理器、别的 SAF 提供器都能选。
            Row {
                TextButton(onClick = onPickSystem) {
                    Text(stringResource(R.string.dsh_fs_picker_system))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        },
    )
}
