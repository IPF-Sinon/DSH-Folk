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
 * 本项目的基准版本。**改版本时只改这两个常量**，其余一切（versionCode、更新说明的
 * 版本校验、测试版的版本名）都从它们推导。
 */
private const val BASE_VERSION_NAME = "1.8.1"
private const val BASE_VERSION_CODE = 10801

/**
 * 允许 CI 覆盖版本。
 *
 * 测试版走的是 `-PdshVersionName=1.8.1-beta.3 -PdshVersionCode=10801`：它必须是一个
 * **真的更高**的版本，否则装了正式版的用户永远收不到测试版提示（compareVersions 会
 * 判成不更新）。正式构建不传这两个属性，用上面的基准值。
 *
 * 用 providers.gradleProperty 而不是 findProperty：后者会让配置缓存失效。
 */
fun getVersionCode(): Int {
    val override = providers.gradleProperty("dshVersionCode").orNull?.trim()?.toIntOrNull()
    return override ?: BASE_VERSION_CODE
}

fun getbranch(): String {
    return exec("git rev-parse --abbrev-ref HEAD", "unknown")
}

fun getVersionName(): String {
    val override = providers.gradleProperty("dshVersionName").orNull?.trim()
    return override?.takeIf { it.isNotEmpty() } ?: BASE_VERSION_NAME
}

tasks.register("printVersion") {
    doLast {
        println("Version code: $managerVersionCode")
        println("Version name: $managerVersionName")
    }
}
