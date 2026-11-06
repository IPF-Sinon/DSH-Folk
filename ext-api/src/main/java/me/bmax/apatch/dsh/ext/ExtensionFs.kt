package me.bmax.apatch.dsh.ext

import android.content.SharedPreferences
import java.io.File

/**
 * 给扩展的目录读写能力：整个 dataDir 与 rootfs 都能碰。
 *
 * ## 为什么是接口
 * 真正的实现要读宿主的路径约定（rootfs 在哪、掩码目录在哪），那些是 :app 的内部知识。
 * 所以这里只定契约，宿主提供 AppExtensionFs 实现；扩展仓库编译时只看到接口。
 *
 * ## 边界（只有一条，但必须硬）
 * 不能动熔断标志文件。否则一个坏扩展可以把「禁用全部扩展」的标志删掉，
 * 用户的逃生通道就失效了 —— 而那时他正好需要它。
 */
interface ExtensionFs {
    /** 应用私有根目录（/data/user/0/包名）。 */
    val dataDir: File

    /** files/（rootfs 也在这里面）。 */
    val filesDir: File

    /** 容器 rootfs，往里面写就是在改容器的根文件系统。 */
    val rootfsDir: File

    /** 缓存目录。 */
    val cacheDir: File

    /** 这个扩展自己的目录。 */
    val ownDir: File

    /** 外部专属目录（/sdcard/Android/data/包名/files，无需存储权限即可读写）。 */
    fun externalFilesDir(): File?

    /** 任意名字的 SharedPreferences（会自动带扩展前缀，互不污染）。 */
    fun prefs(name: String): SharedPreferences

    /** 读字节；失败返回 null。 */
    fun read(file: File): ByteArray?

    /** 读文本；失败返回 null。 */
    fun readText(file: File): String?

    /** 写字节（必要时建父目录）。false = 被保护路径拦下或 IO 失败。 */
    fun write(file: File, bytes: ByteArray): Boolean

    /** 写文本。 */
    fun writeText(file: File, text: String): Boolean

    fun mkdirs(dir: File): Boolean

    /** 删除文件或整棵目录；命中保护路径时拒绝。 */
    fun delete(file: File): Boolean

    /** 建符号链接（容器相关扩展的常见需求）。 */
    fun symlink(target: String, link: File): Boolean

    /** 改权限（八进制数字，如 0x1ED = 0755）。 */
    fun chmod(file: File, mode: Int): Boolean

    /** 列目录（不存在返回空列表）。 */
    fun list(dir: File): List<File>

    /** 该路径是否被安全守卫保护（扩展可自查，避免写一个注定失败的调用）。 */
    fun isProtected(file: File): Boolean
}
