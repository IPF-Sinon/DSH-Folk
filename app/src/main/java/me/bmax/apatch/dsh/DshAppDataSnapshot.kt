package me.bmax.apatch.dsh

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/**
 * 软件设置的本地快照（「恢复快照」能不能把设置也退回去的关键）。
 *
 * ## 为什么必须由 App 自己做
 *
 * 插件侧的 DSH 快照按 `SECTION_IDS` 采集，覆盖的是 `~/.dsh` 下的分区 —— 而 App 的设置住在
 * Android 的 `SharedPreferences`（`config` / `dshfolk`）与 `filesDir` 里的外观资源文件里，
 * 插件**物理上够不到**。所以快照回滚一直只回 DSH 配置，软件设置停在导入后的样子
 * （真机反馈：快照里没有软件设置项，回不回去）。
 *
 * ## 一份格式，两条通路
 *
 * 存的内容与备份包里的软件数据**完全同构**：`app-data.json`（[DshAppData.collect] 的产物）
 * 与 `theme.zip`（[DshBackupArchive.THEME] 同一套外观包），所以恢复时直接复用
 * [DshAppData.apply] 与主题导入通路，不另造一套读写逻辑 —— 免得多一处会漂移的格式。
 *
 * 目录：`filesDir/appdata-snapshots/<快照 id>/`，每个快照 id 对应导入时插件给的那个 id。
 * 保留份数由 [MAX_KEPT] 限制（按目录修改时间淘汰最旧的）：这是「顺手留一份回退点」，
 * 不是备份，不该无限长大。
 */
object DshAppDataSnapshot {
    /** 与插件快照 id 一一对应；id 会直接当目录名，必须先校验。 */
    private const val DIR_NAME = "appdata-snapshots"

    /** 最多保留几份（超出后按修改时间淘汰最旧的）。 */
    private const val MAX_KEPT = 10

    /** 外层容器（zip）。里面装 [APP_DATA_ENTRY] 与可选的 [THEME_ENTRY]。 */
    private const val CONTAINER = "snapshot.zip"
    private const val APP_DATA_ENTRY = "app-data.json"
    private const val THEME_ENTRY = "theme.zip"

    private fun root(ctx: Context): File = File(ctx.filesDir, DIR_NAME)

    /**
     * 快照 id 能不能当目录名。
     *
     * 快照 id 来自插件（外部输入），直接拿它拼路径是不行的：`../` 之类能写到别处去。
     * 只接受 UUID 形状（十六进制与连字符），比「过滤一下危险字符」更不容易出错。
     */
    fun isValidId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 64 && id.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '-' }

    /** 某个快照的软件设置备份是否在（供界面显示「这次能一起回滚设置吗」）。 */
    fun has(ctx: Context, snapshotId: String): Boolean {
        if (!isValidId(snapshotId)) return false
        val f = File(root(ctx), "$snapshotId/$CONTAINER")
        return f.isFile && f.length() > 0L
    }

    /**
     * 把当前的软件设置存进这个快照 id 下。
     *
     * 调用时机是**导入执行之前**（此时还拿不到新的快照 id），所以由调用方先建好目录、
     * 拿到 id 后再落盘 —— 见 [capture]。这里只负责写。
     *
     * @return 写成功返回 true；失败不抛（调用方只记一笔日志，不该因此中断导入）。
     */
    fun write(ctx: Context, snapshotId: String, appData: JSONObject, theme: File?): Boolean {
        if (!isValidId(snapshotId)) return false
        return runCatching {
            val dir = File(root(ctx), snapshotId).apply { mkdirs() }
            ZipOutputStream(BufferedOutputStream(FileOutputStream(File(dir, CONTAINER)))).use { zos ->
                zos.putNextEntry(ZipEntry(APP_DATA_ENTRY))
                zos.write(appData.toString().toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                if (theme != null && theme.isFile && theme.length() > 0L) {
                    zos.putNextEntry(ZipEntry(THEME_ENTRY))
                    theme.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            prune(ctx)
            true
        }.getOrDefault(false)
    }

    /**
     * 把某个快照里的软件设置放回本机。
     *
     * 返回三态字符串由调用方翻译成人话：[Outcome.RESTORED] / [Outcome.MISSING]（这个快照
     * 没存设置，例如更早版本导入创建的）/ [Outcome.FAILED]。
     * **不 includePrivilege = true**：与备份一致的排除规则，提权项永不回滚
     * （换机或回滚都不该替用户改特权）。
     */
    suspend fun restore(ctx: Context, snapshotId: String): Outcome {
        if (!isValidId(snapshotId)) return Outcome.MISSING
        val zip = File(root(ctx), "$snapshotId/$CONTAINER")
        if (!zip.isFile || zip.length() <= 0L) return Outcome.MISSING
        return runCatching {
            var data: JSONObject? = null
            var theme: ByteArray? = null
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    when {
                        e.isDirectory -> Unit
                        e.name == APP_DATA_ENTRY -> data = JSONObject(zis.readBytes().toString(Charsets.UTF_8))
                        e.name == THEME_ENTRY -> theme = zis.readBytes()
                    }
                    zis.closeEntry()
                }
            }
            val json = data ?: return@runCatching Outcome.FAILED
            DshAppData.apply(ctx, json)
            // 外观在软件数据之后落地：两边都会写外观参数（自定义主色/首页布局/夜间模式），
            // 主题后写才是最终生效的那份 —— 与导入恢复的顺序保持同一个理由。
            val themeBytes = theme
            if (themeBytes != null && themeBytes.isNotEmpty()) {
                val tmp = File(ctx.cacheDir, "appdata-snapshot-theme.zip")
                runCatching {
                    tmp.delete()
                    tmp.writeBytes(themeBytes)
                    me.bmax.apatch.ui.theme.ThemeManager.importTheme(ctx, android.net.Uri.fromFile(tmp))
                }
                tmp.delete()
            }
            Outcome.RESTORED
        }.getOrDefault(Outcome.FAILED)
    }

    /** [restore] 的结果。 */
    enum class Outcome { RESTORED, MISSING, FAILED }

    /** 删除某个快照的软件设置副本（插件侧快照被删时一并清理，不留孤儿）。 */
    fun delete(ctx: Context, snapshotId: String): Boolean {
        if (!isValidId(snapshotId)) return false
        return runCatching { File(root(ctx), snapshotId).deleteRecursively() }.getOrDefault(false)
    }

    /**
     * 只留最近 [MAX_KEPT] 份（按目录修改时间）。
     *
     * 这是「顺手留一份回退点」，不是备份 —— 不设上限会随着每次导入无限长大，
     * 而每份里可能有整张背景图甚至背景视频。
     */
    private fun prune(ctx: Context) {
        runCatching {
            val dirs = (root(ctx).listFiles() ?: emptyArray())
                .filter { it.isDirectory }
                .sortedByDescending { it.lastModified() }
            for (old in dirs.drop(MAX_KEPT)) old.deleteRecursively()
        }
    }
}
