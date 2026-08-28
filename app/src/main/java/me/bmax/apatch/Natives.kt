package me.bmax.apatch

import android.os.Parcelable
import android.content.Context
import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import kotlinx.parcelize.Parcelize

/**
 * DSH-Folk：原 APatch 的 KernelPatch JNI 桥（apjni）已移除。
 *
 * DSH-Folk 是 DeepSeek Harness 的移动启动器，不再打内核补丁、不再自带
 * su 实现，而是探测并复用设备上现有的 root（Magisk / KernelSU / APatch）
 * 或 Shizuku / 无线 ADB 通道（见 me.bmax.apatch.dsh.PermissionManager）。
 *
 * 为了在不重写数千行 UI 代码的前提下复用 FolkPatch 的界面骨架，这里保留
 * 与原 Natives 相同的方法签名，但全部改为纯 Kotlin 的安全桩实现：
 * 内核补丁相关能力一律返回“不可用/空”，su 相关能力委托给运行时权限层。
 * 这样所有历史调用点都能编译通过，同时彻底切断 System.loadLibrary("apjni")。
 */
object Natives {

    /** KernelPatch 不再存在，用作“无内核补丁”的哨兵值。 */
    const val KP_VERSION_NONE: Long = 0

    @Immutable
    @Parcelize
    @Keep
    data class Profile(
        var uid: Int = 0,
        var toUid: Int = 0,
        var scontext: String = APApplication.DEFAULT_SCONTEXT,
    ) : Parcelable

    @Keep
    class KPMCtlRes {
        var rc: Long = 0
        var outMsg: String? = null

        constructor()

        constructor(rc: Long, outMsg: String?) {
            this.rc = rc
            this.outMsg = outMsg
        }
    }

    // ---- su：DSH-Folk 不自带 su，交由权限层探测现有通道 ----
    fun su(toUid: Int, scontext: String?): Boolean = false
    fun su(): Boolean = false

    /** 原义为“superKey 能否驱动内核补丁 su”；DSH-Folk 恒为 false。 */
    fun nativeReady(superKey: String): Boolean = false

    fun suPath(): String = APApplication.DEFAULT_SU_PATH
    fun suUids(): IntArray = IntArray(0)

    // ---- KernelPatch：全部不可用 ----
    fun kernelPatchVersion(): Long = KP_VERSION_NONE
    fun kernelPatchBuildTime(): String = ""
    fun loadKernelPatchModule(modulePath: String, args: String): Long = -1
    fun unloadKernelPatchModule(moduleName: String): Long = -1
    fun kernelPatchModuleNum(): Long = 0
    fun kernelPatchModuleList(): String = ""
    fun kernelPatchModuleInfo(moduleName: String): String = ""
    fun kernelPatchModuleControl(moduleName: String, controlArg: String): KPMCtlRes =
        KPMCtlRes(-1, null)

    // ---- su 授权管理：无内核补丁，全部 no-op ----
    fun grantSu(uid: Int, toUid: Int, scontext: String?): Long = -1
    fun revokeSu(uid: Int): Long = -1
    fun setUidExclude(uid: Int, exclude: Int): Int = 0
    fun isUidExcluded(uid: Int): Int = 0
    fun setNewAppProfileMode(mode: Int): Long = -1
    fun getNewAppProfileMode(): Int = 0
    fun suProfile(uid: Int): Profile = Profile()
    fun resetSuPath(path: String): Boolean = false

    // ---- uts / 隐藏路径 / 网络隔离：内核补丁特性，全部 no-op ----
    fun utsSet(release: String?, version: String?): Long = -1
    fun utsReset(): Long = -1
    fun pathHideAdd(path: String): Long = -1
    fun pathHideRemove(path: String): Long = -1
    fun pathHideList(): String = ""
    fun pathHideClear(): Long = -1
    fun pathHideEnable(enable: Boolean): Long = -1
    fun pathHideStatus(): Long = 0
    fun pathHideUidAdd(uid: Int): Long = -1
    fun pathHideUidRemove(uid: Int): Long = -1
    fun pathHideUidList(): String = ""
    fun pathHideUidClear(): Long = -1
    fun pathHideUidMode(enable: Boolean): Long = -1
    fun pathHideFilterSystem(enable: Boolean): Long = -1
    fun netIsolateEnable(enable: Boolean): Long = -1
    fun netIsolateStatus(): Long = 0
    fun netIsolateUidAdd(uid: Int): Long = -1
    fun netIsolateUidRemove(uid: Int): Long = -1
    fun netIsolateUidList(): String = ""
    fun netIsolateUidClear(): Long = -1
    fun suAuditList(): String = ""
    fun suAuditClear(): Long = -1

    /** 原生 API token 生成器已移除；DSH-Folk 的鉴权走 dsh 自身 + 权限层。 */
    fun getApiToken(context: Context): String = ""
    fun controlFeature(featureName: String, enable: Boolean): Long = -1
}
