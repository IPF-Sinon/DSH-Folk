package me.bmax.apatch.dsh.ext

import android.content.Context
import android.content.SharedPreferences
import me.bmax.apatch.dsh.DshEnv
import java.io.File

/**
 * [ExtensionFs] 的宿主实现。
 *
 * 契约在 :ext-api（第三方扩展只看到接口），这里补上宿主才知道的两件事：
 *  - rootfs 的实际位置（DshEnv）；
 *  - 熔断标志文件的保护判定（ExtensionSafetyGuard）。
 */
internal class AppExtensionFs(
    private val ctx: Context,
    private val extensionId: String,
) : ExtensionFs {

    override val dataDir: File get() = ctx.dataDir
    override val filesDir: File get() = ctx.filesDir
    override val rootfsDir: File get() = DshEnv.rootfs(ctx)
    override val cacheDir: File get() = ctx.cacheDir
    override val ownDir: File get() = ExtensionLayout.dirOf(ctx, extensionId)

    override fun externalFilesDir(): File? = runCatching { ctx.getExternalFilesDir(null) }.getOrNull()

    /** 名字带扩展前缀：多个扩展各用各的键，互不覆盖。 */
    override fun prefs(name: String): SharedPreferences =
        ctx.getSharedPreferences("ext_" + extensionId + "_" + name, Context.MODE_PRIVATE)

    override fun read(file: File): ByteArray? = runCatching { file.readBytes() }.getOrNull()

    override fun readText(file: File): String? = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()

    override fun write(file: File, bytes: ByteArray): Boolean {
        if (isProtected(file)) {
            refuse("write", file)
            return false
        }
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            true
        }.getOrDefault(false)
    }

    override fun writeText(file: File, text: String): Boolean =
        write(file, text.toByteArray(Charsets.UTF_8))

    override fun mkdirs(dir: File): Boolean {
        if (isProtected(dir)) {
            refuse("mkdirs", dir)
            return false
        }
        return runCatching { dir.mkdirs() || dir.isDirectory }.getOrDefault(false)
    }

    override fun delete(file: File): Boolean {
        if (isProtected(file)) {
            refuse("delete", file)
            return false
        }
        return runCatching { if (file.exists()) file.deleteRecursively() else true }.getOrDefault(false)
    }

    override fun symlink(target: String, link: File): Boolean {
        if (isProtected(link)) {
            refuse("symlink", link)
            return false
        }
        return runCatching {
            link.parentFile?.mkdirs()
            android.system.Os.symlink(target, link.absolutePath)
            true
        }.getOrDefault(false)
    }

    override fun chmod(file: File, mode: Int): Boolean = runCatching {
        android.system.Os.chmod(file.absolutePath, mode)
        true
    }.getOrDefault(false)

    override fun list(dir: File): List<File> =
        runCatching { dir.listFiles()?.toList() }.getOrNull().orEmpty()

    override fun isProtected(file: File): Boolean =
        ExtensionSafetyGuard.isProtectedPath(ctx, file)

    private fun refuse(op: String, file: File) {
        ExtLog.log(
            "[" + extensionId + "] 拒绝 " + op + " 保护路径：" + file.absolutePath,
        )
    }
}
