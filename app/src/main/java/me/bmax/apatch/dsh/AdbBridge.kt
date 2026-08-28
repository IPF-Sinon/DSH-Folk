package me.bmax.apatch.dsh

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets

/**
 * 无线 ADB 配对桥（移植 DSHA 的 AdbBridge，MIT）。
 *
 * 用途：在没有 root、也不想装 Shizuku 时，通过系统「无线调试」把容器内的 python 客户端
 * 直连本机 adbd，拿到 uid=2000(shell) 权限。这替换了 FolkPatch 内置的 Shizuku Server ——
 * DSH-Folk 只做 Shizuku 客户端，不再自己起服务端。
 *
 * 协议栈：TLS1.3-PSK + SPAKE2(AOSP)，由注入到 rootfs 的三个脚本实现：
 * - `adb-pair.py`  完成「配对码 → 建立连接 → 落 adbkey」；
 * - `adb-shell.py` 用已配对的密钥执行 adb shell 命令；
 * - `adb-setup.sh` 装 python 依赖（adb_shell_wifi + spake2-cffi，离线 wheels 随 APK 附带）。
 */
object AdbBridge {
    private val SCRIPTS = arrayOf("adb-pair.py", "adb-shell.py", "adb-setup.sh")

    /** 脚本版本：改脚本就 +1，旧版残留会因版本不符被强制重注入。 */
    private const val SCRIPT_VERSION = "1"

    // 源文件是 adb-wheels.tar.gz，但 AGP 会在打包时解压并去掉 .gz 后缀
    private val WHEELS_ASSET_NAMES = listOf("adb-wheels.tar.gz", "adb-wheels.tar")

    /** 脚本是否已注入且版本一致。 */
    fun injected(): Boolean = injectedState() == "YES"

    /**
     * 三态：YES 已注入且版本一致 / NO 确认没有或版本不符 / UNKNOWN 查不了。
     *
     * 区分 UNKNOWN 很重要：容器没起来时读不到输出，那是查询失败而不是注入失败 ——
     * 当成「没注入」会导致反复重注入并把状态显示成异常。
     */
    fun injectedState(): String {
        val r = DshRuntime.execRootfsForOutput(
            "test -f /root/.dsh/script-version && cat /root/.dsh/script-version || echo NO"
        )
        val v = r.trim()
        if (v.isEmpty()) return "UNKNOWN"
        return if (v.lines().last().trim() == SCRIPT_VERSION) "YES" else "NO"
    }

    /** 幂等注入：三个脚本 base64 写入 /root/.dsh/ 并加执行位 + 写版本标记。 */
    fun inject(ctx: Context): String {
        val cmds = StringBuilder("set -e; mkdir -p /root/.dsh; ")
        for (name in SCRIPTS) {
            val content = readAsset(ctx, name)
            if (content.isEmpty()) continue
            val b64 = Base64.encodeToString(content.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            cmds.append("printf '%s' '").append(b64).append("' | base64 -d > /root/.dsh/").append(name)
                .append("; chmod +x /root/.dsh/").append(name).append("; ")
        }
        cmds.append("printf '%s' '").append(SCRIPT_VERSION).append("' > /root/.dsh/script-version; ")
        cmds.append("echo INJECT_OK")
        return DshRuntime.execRootfsForOutput(cmds.toString(), 120_000)
    }

    /** 安装 python 依赖：优先离线 wheels（随 APK），失败回落 pip 联网。 */
    fun installDeps(ctx: Context): String {
        // AGP 打包 assets 时会把 .gz 解开并去掉扩展名，APK 里实际是 adb-wheels.tar。
        // 两个名字都试，取到哪个都行 —— 下面的 shell 用文件头判断要不要 -z。
        val wheels = WHEELS_ASSET_NAMES.firstNotNullOfOrNull { name ->
            runCatching { ctx.assets.open(name).readBytes() }.getOrNull()
        }
        if (wheels != null) {
            val dst = java.io.File(DshEnv.dshHome(ctx), "adb-wheels.tar.gz")
            dst.parentFile?.mkdirs()
            runCatching { dst.writeBytes(wheels) }
        }
        // 不能固定 tar xzf（强制 gzip，裸 tar 会报 not in gzip format）：aapt 可能已解压过
        val cmd = "mkdir -p /root/.dsh/wheels && " +
            "M=\$(head -c 2 /root/.dsh/adb-wheels.tar.gz 2>/dev/null | od -An -tx1 | tr -d ' \\n'); " +
            "if [ \"\$M\" = \"1f8b\" ]; then tar xzf /root/.dsh/adb-wheels.tar.gz -C /root/.dsh/wheels/; " +
            "else tar xf /root/.dsh/adb-wheels.tar.gz -C /root/.dsh/wheels/ 2>/dev/null || true; fi; " +
            "bash /root/.dsh/adb-setup.sh 2>&1 | tail -20"
        return DshRuntime.execRootfsForOutput(cmd, 300_000)
    }

    /** 依赖是否就绪（新版库从 spake2.spake2 导入，模块名无下划线）。 */
    fun depsOk(): Boolean = DshRuntime.execRootfsForOutput(
        "python3 -c 'import adb_shell_wifi; from spake2.spake2 import Spake2_Alice, Spake2_Bob' 2>/dev/null && echo YES || echo NO"
    ).contains("YES")

    /**
     * 单次配对。
     *
     * @param code       系统「无线调试 → 使用配对码配对」弹出的 6 位配对码
     * @param pairPort   配对端口（弹窗上的端口）；留空时脚本内尝试 mDNS 发现
     * @param connectPort 连接端口，默认 5555
     * @param host       本机 IP（部分 ROM 的配对服务只监听 WiFi 接口）
     */
    fun pair(code: String, pairPort: String = "", connectPort: String = "", host: String = ""): String {
        val c = StringBuilder("python3 /root/.dsh/adb-pair.py --code '").append(esc(code)).append("'")
        if (host.isNotBlank()) c.append(" --host ").append(host.trim())
        if (pairPort.isNotBlank()) c.append(" --port ").append(pairPort.trim())
        if (connectPort.isNotBlank()) c.append(" --connect-port ").append(connectPort.trim())
        return DshRuntime.execRootfsForOutput(c.toString(), 180_000)
    }

    /** 用已配对通道执行一条 adb shell 命令。 */
    fun shell(command: String): String = DshRuntime.execRootfsForOutput(
        "DSH_INTERNAL=1 python3 /root/.dsh/adb-shell.py " + command, 120_000
    )

    /** 状态快照：key / deps / connect_port（供 UI 展示）。 */
    fun status(): String = DshRuntime.execRootfsForOutput(
        "K=\$(test -f /root/.dsh/adbkeys/adbkey && echo YES || echo NO); " +
            "D=\$(python3 -c 'import adb_shell_wifi; from spake2.spake2 import Spake2_Alice, Spake2_Bob' 2>/dev/null && echo YES || echo NO); " +
            "P=\$(test -f /root/.dsh/adbkeys/connect_port && cat /root/.dsh/adbkeys/connect_port || echo -); " +
            "echo 'key='\$K' deps='\$D' port='\$P"
    )

    private fun esc(s: String): String = s.replace("'", "'\\''")

    private fun readAsset(ctx: Context, name: String): String = runCatching {
        ctx.assets.open(name).use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }.getOrDefault("")
}
