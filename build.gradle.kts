plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
}

val androidMinSdkVersion by extra(26)
val androidTargetSdkVersion by extra(36)
val androidCompileSdkVersion by extra(37)
val androidBuildToolsVersion by extra("36.1.0")
val managerVersionCode by extra(getVersionCode())
val managerVersionName by extra(getVersionName())
val branchName by extra(getbranch())

fun Project.exec(command: String, default: String): String {
    return try {
        providers.exec {
            commandLine(command.split(" "))
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() } ?: default
    } catch (e: Exception) {
        default
    }
}

fun getGitCommitCount(): Int {
    return exec("git rev-list --count HEAD", "0").toInt()
}

fun getGitDescribe(): String {
    return exec("git rev-parse --verify --short HEAD", "unknown")
}

/**
 * 本项目的基准版本。**改版本时只改这两个函数的返回值**，其余一切（versionCode、
 * 更新说明的版本校验、测试版的版本名）都从它们推导。
 *
 * 为什么是函数而不是常量：`.kts` 的脚本体被编译成一个**类的主体**，那里不允许
 * `const val`（CI 就是这么挂的）；而换成普通 `val` 会引入一个更安静的坑 —— 上面
 * `managerVersionCode by extra(getVersionCode())` 在脚本第 11 行就执行，一个声明在
 * 后面的 `val` 此刻还是 0，构建会拿到错误的版本号而不报任何错。函数没有初始化顺序。
 */
fun baseVersionName(): String = "1.8.1"

fun baseVersionCode(): Int = 10801

/**
 * 允许 CI 覆盖版本。
 *
 * 测试版走的是 `-PdshVersionName=1.8.1-beta.3 -PdshVersionCode=10801`：它必须是一个
 * **真的更高**的版本，否则装了正式版的用户永远收不到测试版提示（compareVersions 会
 * 判成不更新）。正式构建不传这两个属性，用上面的基准值。
 *
 * 用 providers.gradleProperty 而不是 findProperty：后者会让配置缓存失效。
 * 写成 Project 扩展是照着这个文件里已有的 `Project.exec` —— 那条路径已经证明能从
 * 脚本里的普通函数调到（`getGitCommitCount` 就是这么用的）。
 */
fun Project.dshVersionOverride(name: String): String? =
    providers.gradleProperty(name).orNull?.trim()?.takeIf { it.isNotEmpty() }

fun getVersionCode(): Int =
    dshVersionOverride("dshVersionCode")?.toIntOrNull() ?: baseVersionCode()

fun getbranch(): String {
    return exec("git rev-parse --abbrev-ref HEAD", "unknown")
}

fun getVersionName(): String =
    dshVersionOverride("dshVersionName") ?: baseVersionName()

tasks.register("printVersion") {
    doLast {
        println("Version code: $managerVersionCode")
        println("Version name: $managerVersionName")
    }
}
