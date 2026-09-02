package bin.mt.file.content

import android.app.Activity
import android.os.Bundle

/**
 * MT 管理器用来唤起本应用进程的空 Activity。
 *
 * ## 为什么需要它
 *
 * MT 管理器通过 [android.provider.DocumentsProvider]（DSH-Folk 侧是
 * `me.bmax.apatch.util.DshDocumentsProvider`）浏览本应用私有目录。但被系统标记为
 * **stopped** 的应用（装完没启动过、或被「强行停止」过）不会因为一次 provider 查询
 * 而被拉起，于是 MT 那边表现为「目录里什么都没有」。MT 官方文档写明了这点：
 * 「在浏览目标 APP 的文件时，其进程必须正在运行」，并且 MT 会检测、提供「一键启动」。
 *
 * 一键启动需要一个能显式 start 的组件。这个 Activity 就是那个落点：`onCreate` 里
 * 立刻 `finish()`，用户看不到任何界面，但进程被拉起、stopped 标记被清掉，
 * 随后的 provider 查询就正常了。
 *
 * ## 类名与包名是**约定**
 *
 * 全限定名 `bin.mt.file.content.MTDataFilesWakeUpActivity` 沿用 MT 官方注入库
 * （MTDataFilesProvider）的形状 —— MT 按这个名字找唤起入口。名字改了就等于没有。
 * 这里只复刻这个约定（一个空 Activity 的声明），没有引入也没有拷贝该库的代码。
 *
 * DSH-Folk 本身有 LAUNCHER Activity，用户手动打开应用同样能达到目的；
 * 这个组件只是让 MT 的「一键启动」按钮可用。
 */
class MTDataFilesWakeUpActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 只为把进程拉起来，不显示任何界面
        finish()
    }
}
