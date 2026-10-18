package me.bmax.apatch.dsh

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets

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
}
