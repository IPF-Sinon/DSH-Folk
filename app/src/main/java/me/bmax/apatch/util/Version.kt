package me.bmax.apatch.util

import androidx.core.content.pm.PackageInfoCompat
import me.bmax.apatch.apApp

/**
 * 版本信息。
 *
 * FolkPatch 这里原本还负责 KernelPatch 镜像版本（kpimg / installedKPV / apd sha256 比对），
 * DSH-Folk 不打内核补丁也不带 apd，那些函数与它们唯一的调用方（APApplication 的状态机）
 * 一起删掉了。运行时版本（rootfs / dsh / node）由 me.bmax.apatch.dsh.DshRuntime 提供。
 */
object Version {

    /** 管理器自身版本：Pair(versionName, versionCode)。 */
    fun getManagerVersion(): Pair<String, Long> {
        val packageInfo = apApp.packageManager.getPackageInfo(apApp.packageName, 0)!!
        val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
        return Pair(packageInfo.versionName!!, versionCode)
    }
}
