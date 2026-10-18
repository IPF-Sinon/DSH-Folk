package me.bmax.apatch.dsh

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 往容器里写文件的两条统一规则：**内容相同就不写**、脚本**总是可执行**。
 *
 * 「相同就不写」不是省事：容器侧的 `dsh-native` / `dsh-fs` / `dsh-workflow` 与说明文档都是
 * 按内容比对再落盘的（换语言、换版本才重写），无谓改写会让 agent 读到一半的文件、也会让
 * 「实现改了文档没改」这种漂移更难看出来。
 *
 * 与 [DshRuntimeEnv] 同样的原则：只接显式参数，不依赖 DshRuntime 的 private 成员。
 */
internal object DshRuntimeFiles {
    /** 内容不同才写。返回是否真的写了（调用方要打日志时用得上）。 */
    fun writeIfChanged(file: File, text: String): Boolean {
        file.parentFile?.mkdirs()
        if (file.isFile && runCatching { file.readText() }.getOrNull() == text) return false
        file.writeText(text, StandardCharsets.UTF_8)
        return true
    }

    /**
     * 写可执行脚本：内容相同也会补一次可执行位。
     *
     * 旧实现里「内容没变」会在设置可执行位之前就返回，于是文件丢了 x 位就再也补不回来
     * （rootfs 被别的路径解压/覆盖时会发生）。
     */
    fun writeScript(file: File, text: String) {
        writeIfChanged(file, text)
        file.setExecutable(true, false)
    }
}
