package me.bmax.apatch.dsh.ext

import android.content.Context
import android.content.SharedPreferences
import me.bmax.apatch.dsh.DshEnv
import java.io.File

/**
 * 给扩展的目录读写能力：整个 dataDir 与 rootfs 都能碰。
 *
 * ## 边界（只有一条，但必须硬）
 * 不能动熔断标志文件（[ExtensionSafetyGuard.isProtectedPath]）。否则一个坏扩展可以把
 * 「禁用全部扩展」的标志删掉，用户的逃生通道就失效了 —— 而那时他正好需要它。
 *
 * 除此之外不设限：扩展本来就是宿主级同权代码，再在 API 上做假动作只会让人以为它受控。
 */
class ExtensionFs internal constructor(
    private val ctx: Context,
    private val id: String,
) {
    /** 应用私有根目录（/data/user/0/<pkg>）。 */
    val dataDir: File get() = ctx.dataDir

    /** files/（rootfs 也在这里面）。 */
    val filesDir: File get() = ctx.filesDir

    /** 容器 rootfs（files/rootfs），往里面写就是在改容器的根文件系统。 */
    val rootfsDir: File get() = DshEnv.rootfs(ctx)

    /** 缓存目录。 */
    val cacheDir: File get() = ctx.cacheDir

    /** 扩展自己的目录。 */
    val ownDir: File get() = ExtensionLayout.dirOf(ctx, id)

    /** 外部专属目录（/sdcard/Android/data/<pkg>/files，无需存储权限即可读写）。 */
    fun externalFilesDir(): File? = runCatching { ctx.getExternalFilesDir(null) }.getOrNull()

    /** 任意路径的 SharedPreferences（用于存放扩展自己的偏好）。 */
    fun prefs(name: String): SharedPreferences =
        ctx.getSharedPreferences("ext_" + id + "_" + name, Context.MODE_PRIVATE)

    /** 读字节。失败返回 null。 */
    fun read(file: File): ByteArray? = runCatching { file.readBytes() }.getOrNull()

    /** 读文本。失败返回 null。 */
    fun readText(file: File): String? =
        runCatching { file.readText(Charsets.UTF_8) }.getOrNull()

    /**
     * 写字节（必要时建父目录）。
     *
     * @return false = 被保护路径拦下，或 IO 失败。
     */
    fun write(file: File, bytes: ByteArray): Boolean {
        if (ExtensionSafetyGuard.isProtectedPath(ctx, file)) {
            refuse("write", file)
            return false
        }
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            true
        }.getOrDefault(false)
    }

    /** 写文本。 */
    fun writeText(file: File, text: String): Boolean = write(file, text.toByteArray(Charsets.UTF_8))

    fun mkdirs(dir: File): Boolean {
        if (ExtensionSafetyGuard.isProtectedPath(ctx, dir)) {
            refuse("mkdirs", dir)
            return false
        }
        return runCatching { dir.mkdirs() || dir.isDirectory }.getOrDefault(false)
    }

    /** 删除（文件或整棵目录）。命中保护路径时拒绝。 */
    fun delete(file: File): Boolean {
        if (ExtensionSafetyGuard.isProtectedPath(ctx, file)) {
            refuse("delete", file)
            return false
        }
        return runCatching { if (file.exists()) file.deleteRecursively() else true }.getOrDefault(false)
    }

    /** 建符号链接（容器里很常见的需求）。 */
    fun symlink(target: String, link: File): Boolean {
        if (ExtensionSafetyGuard.isProtectedPath(ctx, link)) {
            refuse("symlink", link)
            return false
        }
        return runCatching {
            link.parentFile?.mkdirs()
            android.system.Os.symlink(target, link.absolutePath)
            true
        }.getOrDefault(false)
    }

    /** 改权限（八进制数字，如 0x1ED = 0755）。 */
    fun chmod(file: File, mode: Int): Boolean = runCatching {
        android.system.Os.chmod(file.absolutePath, mode)
        true
    }.getOrDefault(false)

    /** 列目录（不存在返回空列表）。 */
    fun list(dir: File): List<File> = runCatching { dir.listFiles()?.toList() }.getOrNull().orEmpty()

    /** 该路径是否被安全守卫保护（扩展可以自查，避免写了个注定失败的调用）。 */
    fun isProtected(file: File): Boolean = ExtensionSafetyGuard.isProtectedPath(ctx, file)

    private fun refuse(op: String, file: File) {
        me.bmax.apatch.dsh.DshRuntime.appendLog(
            "[ext:" + id + "] 拒绝 " + op + " 保护路径：" + file.absolutePath,
        )
    }
}
