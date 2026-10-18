package me.bmax.apatch.dsh

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets
import me.bmax.apatch.R
import me.bmax.apatch.util.appString

/**
 * 容器环境里那些「落地件」：DNS 与 hosts。
 *
 * 抽出来的原则（阶段 4 的第一刀）：**只接显式参数**（ctx），不碰 DshRuntime 的 private
 * 成员（prefs()/scope/bootMutex/_state）。同包不同文件的 `internal` 看不见那些 private，
 * 一旦依赖它们，拆分就会卡在可见性上 —— 这份实现因此只依赖 DshEnv 与 File。
 */
internal object DshRuntimeEnv {
    /**
     * 安装/重装后调用：先删掉旧的 resolv.conf（换网络环境后原来的 nameserver 可能已不可达），
     * 再按需写入默认解析器与 hosts。
     */
    fun setupResolvConf(ctx: Context) {
        runCatching {
            File(DshEnv.rootfs(ctx), "etc/resolv.conf").delete()
        }
        ensureDns(ctx)
    }

    /** 只在文件为空时补默认内容：用户自己改过的解析器不该被覆盖。 */
    fun ensureDns(ctx: Context) {
        runCatching {
            val rootfs = DshEnv.rootfs(ctx)
            if (!File(rootfs, "etc").isDirectory) return@runCatching
            val rc = File(rootfs, "etc/resolv.conf")
            if (rc.length() == 0L) {
                rc.writeText(
                    "nameserver 223.5.5.5\nnameserver 119.29.29.29\n" +
                        "nameserver 8.8.8.8\nnameserver 1.1.1.1\n",
                    StandardCharsets.UTF_8,
                )
            }
            val hosts = File(rootfs, "etc/hosts")
            if (hosts.length() == 0L) {
                hosts.writeText(
                    "127.0.0.1\tlocalhost\n::1\tlocalhost ip6-localhost ip6-loopback\n",
                    StandardCharsets.UTF_8,
                )
            }
        }
    }

    /**
     * 把本进程的 Android 补充组补进容器的 `/etc/group`。
     *
     * 为什么要做：proot 的 `-0` 只伪造 uid/gid 0，**不拦 getgroups()**，所以容器里看到的
     * 补充组是宿主应用进程真实的那几个 Android gid（3003=inet、9997=everybody、
     * 20054=本应用的 cache gid、50054=本应用的 shared gid）。容器 glibc 在 /etc/group
     * 里查不到它们，于是每次调用 `groups` 都往 stderr 刷一串
     * `groups: cannot find name for group ID 3003`。
     *
     * 而 Ubuntu 的 `/etc/bash.bashrc` 里有一段 sudo 提示，无条件跑 `case " $(groups) " in`
     * （见 bash 包的 debian/etc.bash.bashrc）—— 于是每开一次终端就先糊四行警告。
     * 用户看到的就是这个。
     *
     * 为什么在运行期做而不是打进 rootfs：cache/shared gid 由本应用的 uid 派生
     * （appid + 20000 / 50000），每次安装都可能不同，构建期不可能知道。也因此不必动
     * [ROOTFS_REV] —— 这个函数在每条 exec 路径上都会跑一遍，存量用户下次进终端就好了。
     *
     * 顺带写一个 `/root/.hushlogin`：那段 sudo 提示对本容器毫无意义（rootfs 里没装
     * sudo，而且永远是 root），跳过它就少一次 fork+exec。两道措施互不依赖 —— 补组是
     * 为了让用户真的敲 `groups`、`id -Gn` 时也不报错，hushlogin 只挡开场那一次。
     *
     * 只追加缺的行，不动已有内容：用户自己加过的组要保住。
     */
    fun ensureContainerGroups() {
        runCatching {
            val rootfs = DshEnv.rootfs(ctx)
            val f = File(rootfs, "etc/group")
            if (!File(rootfs, "etc").isDirectory) return@runCatching
            // base rootfs 里这个文件必然存在且非空；真要是空的就别写，
            // 只补几个 Android gid 而丢掉 root/sudo 等基础组会更糟
            if (f.length() == 0L) return@runCatching

            val existing = f.readLines()
            val haveGid = existing.mapNotNull {
                it.split(':').getOrNull(2)?.trim()?.toIntOrNull()
            }.toHashSet()
            val haveName = existing.mapNotNull {
                it.split(':').firstOrNull()?.trim()?.takeIf { n -> n.isNotEmpty() }
            }.toHashSet()

            val add = StringBuilder()
            for (gid in supplementaryGids().distinct().sorted()) {
                if (gid in haveGid) continue
                val name = androidGroupName(gid)
                // 名字撞了就退回 aid_<gid>：/etc/group 里重名会让 getgrnam 取到错的那条
                val unique = if (name in haveName) "aid_$gid" else name
                if (unique in haveName) continue
                haveName += unique
                haveGid += gid
                add.append("$unique:x:$gid:\n")
            }
            if (add.isEmpty()) return@runCatching

            // 补尾部换行：base 文件末尾没有 \n 时直接追加会把两条粘成一行
            val needsNewline = f.readText(StandardCharsets.UTF_8).let {
                it.isNotEmpty() && !it.endsWith("\n")
            }
            java.io.FileOutputStream(f, true).bufferedWriter(StandardCharsets.UTF_8).use { w ->
                if (needsNewline) w.write("\n")
                w.write(add.toString())
            }
            log.info(R.string.dsh_log_groups_added, joinForLog(add.toString().trim().lines()))
        }.onFailure { android.util.Log.w(TAG, "补 /etc/group 失败: ${it.message}") }
    }

    /**
     * 把包名写进 profile `pnpm-workspace.yaml` 的 `allowBuilds`，放行它们的构建脚本。
     *
     * pnpm 11 默认不执行依赖的 install/postinstall/prepare 脚本，且**直接失败**
     * （ERR_PNPM_IGNORED_BUILDS，退出码 1），官方出路是交互式 `pnpm approve-builds` ——
     * 在容器里没有 TTY，跑不了。所以由这里代写配置。
     *
     * 格式是**映射**而不是列表，已实测：
     * ```yaml
     * allowBuilds:
     *   esbuild: true
     * ```
     * 写成 `- esbuild` 会被 pnpm 改写成 `'0': esbuild`，等于没放行。
     *
     * 还有一个坑：pnpm 失败时会**自己**往文件里塞
     * `esbuild: set this to true or false` 这样的占位行。只看「键在不在」会把它
     * 当成已放行而跳过，于是重试仍然失败（本地实测踩到过）。所以这里按**值**判断，
     * 只认 `true`，占位行原地改写。
     *
     * 做的是有针对性的合并，不是整文件重写：文件里还有 packageImportMethod、
     * minimumReleaseAgeExclude 等别人的配置，解析后重新序列化会丢注释与顺序。
     *
     * @return 这次真正放行的包名（本来就是 true 的不算）
     */
    fun allowProfileBuilds(packages: List<String>, onLine: (String) -> Unit = {}): List<String> {
        if (packages.isEmpty()) return emptyList()
        val ws = File(DshEnv.rootfs(ctx), "$PROFILE_GUEST_REL/pnpm-workspace.yaml")
        if (!ws.isFile) {
            onLine("[DSH-Folk] 找不到 $PROFILE_GUEST_REL/pnpm-workspace.yaml，无法放行构建脚本")
            return emptyList()
        }
        return runCatching {
            val lines = ws.readText(StandardCharsets.UTF_8).lines().toMutableList()
            val blockAt = lines.indexOfFirst { it.trimEnd() == "$PNPM_ALLOW_BUILDS_KEY:" }

            // 块内已有的键：值为 true 才算放行，其余（pnpm 的占位串）记下行号待改写
            val allowed = mutableSetOf<String>()
            val placeholders = mutableMapOf<String, Int>()
            if (blockAt >= 0) {
                for (i in (blockAt + 1) until lines.size) {
                    val raw = lines[i]
                    if (raw.isBlank()) continue
                    // 回到顶层缩进即块结束
                    if (!raw.startsWith(" ") && !raw.startsWith("\t")) break
                    val body = raw.trim()
                    val colon = body.indexOf(':')
                    if (colon <= 0) continue
                    val key = body.substring(0, colon).trim().trim('\'', '"')
                    val value = body.substring(colon + 1).trim()
                    if (value == "true") allowed += key else placeholders[key] = i
                }
            }

            val want = packages.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val todo = want.filter { it !in allowed }
            if (todo.isEmpty()) return@runCatching emptyList()

            // 包名带 @ 或斜杠时必须加引号，否则 YAML 解析会走偏
            fun entry(p: String) = "  '${p.replace("'", "''")}': true"
            val insert = mutableListOf<String>()
            for (p in todo) {
                val at = placeholders[p]
                if (at != null) lines[at] = entry(p) else insert += entry(p)
            }
            if (insert.isNotEmpty()) {
                if (blockAt >= 0) {
                    lines.addAll(blockAt + 1, insert)
                } else {
                    if (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
                    lines += "$PNPM_ALLOW_BUILDS_KEY:"
                    lines += insert
                }
            }
            ws.writeText(lines.joinToString("\n").trimEnd() + "\n", StandardCharsets.UTF_8)
            android.util.Log.i(TAG, "已放行构建脚本: ${todo.joinToString(", ")}")
            onLine("[DSH-Folk] " + ctx.appString(R.string.dsh_log_builds_allowed, joinForLog(todo)))
            todo
        }.getOrElse {
            android.util.Log.e(TAG, "写 allowBuilds 失败", it)
            onLine(
                "[DSH-Folk] " + ctx.appString(
                    R.string.dsh_log_builds_allow_failed,
                    it.message ?: it.javaClass.simpleName,
                )
            )
            emptyList()
        }
    }

    // ────────────────────────── web 服务 ──────────────────────────
}

/**
 * 抽出来的落地件要打日志时用它：DshRuntime 实现这一层，避免把整个单例拖进辅助文件。
 */
internal interface DshRuntimeLog {
    fun info(resId: Int, vararg args: Any)
    fun warn(resId: Int, vararg args: Any)
}
