package me.bmax.apatch.util

import java.security.MessageDigest
import androidx.core.content.pm.PackageInfoCompat
import me.bmax.apatch.APApplication
import me.bmax.apatch.BuildConfig
import me.bmax.apatch.Natives
import me.bmax.apatch.apApp
import java.io.File


/**
 * version string is like 0.9.0 or 0.9.0-dev
 * version uint is hex number like: 0x000900
 */
object Version {

    private fun string2UInt(ver: String): UInt {
        val v = ver.trim().split("-")[0]
        val vn = v.split('.')
        val vi = vn[0].toInt().shl(16) + vn[1].toInt().shl(8) + vn[2].toInt()
        return vi.toUInt()
    }

    /**
     * DSH-Folk：内核补丁镜像已随打补丁功能一起移除（assets 里不再有 kpimg / boot_patch.sh）。
     * 保留桩函数是因为 APApplication 里的历史分支仍会引用它；那段代码只在
     * Natives.nativeReady() 为真时才跑，而 DSH-Folk 的 Natives 是恒 false 的桩。
     */
    fun getKpImg(): String = "unknown"

    fun uInt2String(ver: UInt): String {
        return "%d.%d.%d".format(
            ver.and(0xff0000u).shr(16).toInt(),
            ver.and(0xff00u).shr(8).toInt(),
            ver.and(0xffu).toInt()
        )
    }
    
    fun installedKPTime(): String {
        if (BuildConfig.DEBUG_FAKE_ROOT) {
            return "2024-05-20 12:00:00"
        }
        val time = Natives.kernelPatchBuildTime()
        return if (time.startsWith("ERROR_")) "读取失败" else time
    }

    fun buildKPVUInt(): UInt {
        val buildVS = BuildConfig.buildKPV
        return string2UInt(buildVS)
    }

    fun buildKPVString(): String {
        return BuildConfig.buildKPV
    }

    /**
     * installed KernelPatch version (installed kpimg)
     */
    fun installedKPVUInt(): UInt {
        if (BuildConfig.DEBUG_FAKE_ROOT) {
            return string2UInt("0.12.2")
        }
        return Natives.kernelPatchVersion().toUInt()
    }

    fun installedKPVString(): String {
        if (BuildConfig.DEBUG_FAKE_ROOT) {
            return "0.12.2"
        }
        return uInt2String(installedKPVUInt())
    }


    /** DSH-Folk 不再打包 libapd.so，文件缺失时返回空串而不是抛 FileNotFoundException。 */
    fun getBundledApdSha256(): String {
        val nativeDir = apApp.applicationInfo.nativeLibraryDir
        val libapd = File(nativeDir, "libapd.so")
        if (!libapd.isFile) return ""
        return runCatching { computeSHA256(libapd) }.getOrDefault("")
    }

    fun getInstalledApdSha256(): String {
        val resultShell = rootShellForResult("sha256sum ${APApplication.APD_PATH}")
        installedApdHash = if (resultShell.isSuccess) {
            resultShell.out.firstOrNull()?.split("\\s+".toRegex())?.first() ?: ""
        } else {
            ""
        }
        return installedApdHash
    }

    private fun computeSHA256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var read = fis.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = fis.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }


    fun getManagerVersion(): Pair<String, Long> {
        val packageInfo = apApp.packageManager.getPackageInfo(apApp.packageName, 0)!!
        val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
        return Pair(packageInfo.versionName!!, versionCode)
    }

    var installedApdHash: String = ""
}
