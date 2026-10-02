@file:Suppress("UnstableApiUsage")

import com.android.build.api.variant.FilterConfiguration
import com.android.build.gradle.tasks.PackageAndroidArtifact
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.io.File
import java.io.FileInputStream
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.lsplugin.apksign)
    alias(libs.plugins.lsplugin.resopt)
    id("kotlin-parcelize")
}

val androidCompileSdkVersion: Int by rootProject.extra
val androidBuildToolsVersion: String by rootProject.extra
val androidMinSdkVersion: Int by rootProject.extra
val androidTargetSdkVersion: Int by rootProject.extra
val managerVersionCode: Int by rootProject.extra
val managerVersionName: String by rootProject.extra
val branchName: String by rootProject.extra

// Load keystore properties
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

// Load local properties
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

apksign {
    storeFileProperty = "KEYSTORE_FILE"
    storePasswordProperty = "KEYSTORE_PASSWORD"
    keyAliasProperty = "KEY_ALIAS"
    keyPasswordProperty = "KEY_PASSWORD"
}

android {
    namespace = "me.bmax.apatch"
    signingConfigs {
        create("release") {
            storeFile = file(keystoreProperties.getProperty("KEYSTORE_FILE") ?: "debug.keystore")
            storePassword = keystoreProperties.getProperty("KEYSTORE_PASSWORD") ?: "android"
            keyAlias = keystoreProperties.getProperty("KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = keystoreProperties.getProperty("KEY_PASSWORD") ?: "android"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            multiDexEnabled = true
            vcsInfo.include = false
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    dependenciesInfo.includeInApk = false

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }

    defaultConfig {
        applicationId = "top.funcun.dshfolk"
        minSdk = androidMinSdkVersion
        targetSdk = androidTargetSdkVersion
        versionCode = managerVersionCode
        versionName = managerVersionName
        buildConfigField("boolean", "DEBUG_FAKE_ROOT", localProperties.getProperty("debug.fake_root", "false"))

        base.archivesName = "DSH-Folk_${managerVersionCode}_${managerVersionName}_on_${branchName}"
    }

    // 按 ABI 拆包，不出 universal APK。
    //
    // 两个架构的原生库合起来只多约 0.3 MB，拆包的理由不是体积而是**明确性**：
    // 下载页上「哪个包能装」一眼可见，而不是装完才发现容器起不来。
    // 代价是 release 里有两个 APK，应用内更新必须按本机 ABI 挑（见 UpdateChecker）。
    //
    // 这里**不能**再写 defaultConfig.ndk.abiFilters：AGP 明确拒绝两者并存
    // （Conflicting configuration: ndk abiFilters cannot be present when splits
    // abi filters are set）。ABI 集合由这份 include 单独决定 —— 它同时限定了
    // 产出哪些 APK、以及每个 APK 从 app/libs/<abi>/ 收哪一份原生库。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        jniLibs {
            // proot/proroot 的可执行 .so 必须原样打包（不能压缩，需可 mmap 执行）
            useLegacyPackaging = true
        }
        resources {
            excludes += "**"
            merges += "META-INF/com/google/android/**"
        }
    }

    androidResources {
        generateLocaleConfig = true
    }

    compileSdk = androidCompileSdkVersion
    buildToolsVersion = androidBuildToolsVersion

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    android.sourceSets.named("main") {
        kotlin.directories += "build/generated/ksp/$name/kotlin"
        // proot/proroot 等预编译 .so 放在 app/libs/<abi>/ 下（arm64-v8a、x86_64）
        jniLibs.directories += "libs"
    }
}

// 每个 ABI 一个独立 versionCode。
//
// 拆包后两个 APK 的 versionCode 不能相同：装了 arm64 包的设备遇到同号的 x86_64
// 包会被系统当作「同一版本」，覆盖安装与升级判定都会出错。
//
// 规则是 managerVersionCode * 10 + ABI 偏移，**乘 10 而不是加个大常数**，
// 这样跨版本严格单调：本版 10706 → 107061/107062，下一版 10707 的最小值
// 107071 仍大于本版最大值 107062。versionName 不加偏移 —— UpdateChecker 拿
// tag 与 BuildConfig.VERSION_NAME 比较，改动它会让自比较失准。
val abiVersionOffsets = mapOf("arm64-v8a" to 1, "x86_64" to 2)

// debug 用独立包名，与 release（top.funcun.dshfolk）共存，可同时安装测试。
// buildType 上没有 applicationId 全量覆盖（只有 applicationIdSuffix，会产生
// .dshfolk.debug 而不是要求的 folkpatch.debug），所以走 variant API 直接改。
// manifest 的 provider authority 都写 ${applicationId}，代码里也一律用
// context.packageName / BuildConfig.APPLICATION_ID 拼，会自动跟随新包名。
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.applicationId.set("top.funcun.folkpatch.debug")
    }
    onVariants { variant ->
        for (output in variant.outputs) {
            val abi = output.filters
                .firstOrNull { it.filterType == FilterConfiguration.FilterType.ABI }
                ?.identifier
            val offset = abiVersionOffsets[abi] ?: 0
            output.versionCode.set(managerVersionCode * 10 + offset)
        }
    }
}

// https://stackoverflow.com/a/77745844
tasks.withType<PackageAndroidArtifact> {
    doFirst { appMetadata.asFile.orNull?.writeText("") }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

ksp {
    arg("compose-destinations.defaultTransitions", "none")
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.biometric)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.runtime.livedata)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(libs.compose.destinations.core)
    ksp(libs.compose.destinations.ksp)

    // libsu：仅用于探测/驱动设备上已有的 su（Magisk/KernelSU/APatch），不再自带 su
    implementation(libs.com.github.topjohnwu.libsu.core)
    implementation(libs.com.github.topjohnwu.libsu.service)
    implementation(libs.com.github.topjohnwu.libsu.nio)
    implementation(libs.com.github.topjohnwu.libsu.io)

    implementation(libs.dev.rikka.rikkax.parcelablelist)

    // Shizuku：只做客户端 —— 连接设备上已安装并授权的 Shizuku / Sui。
    // 内置 Shizuku Server 那一套（rikka.shizuku.server / moe.shizuku.starter / rikka.rish）
    // 已整体删除，因此 hidden-api compat / stub 与 refine 运行时也不再需要。
    implementation(libs.dev.rikka.shizuku.api)
    implementation(libs.dev.rikka.shizuku.provider)

    implementation(libs.io.coil.kt.coil.compose)
    implementation(libs.io.coil.kt.coil.gif)

    // 真 PTY 终端（终端页）：Termux 的 terminal-view，传递带入 terminal-emulator
    // 与其 JNI PTY 层。不要为它加 guava 的 listenablefuture 空占位包（Termux Wiki 的
    // 那条建议只适用于同时引入 termux-shared 的情况）：本项目没有 guava，加空包会把
    // androidx.concurrent.futures 的父接口换空，启动即 NoClassDefFoundError。
    implementation(libs.termux.terminal.view)

    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.okhttp)

    implementation(libs.me.zhanghai.android.appiconloader.coil)

    implementation(libs.sheet.compose.dialogs.core)
    implementation(libs.sheet.compose.dialogs.list)
    implementation(libs.sheet.compose.dialogs.input)

    implementation(libs.markdown)

    implementation(libs.ini4j)

    implementation(libs.google.code.gson)

    implementation(libs.liquid)

    implementation(libs.materialKolor)
}

// ─────────────────────────────────────────────────────────────────────────────
// 虚拟屏服务端：把 displayserver/ 编成「只含 classes.dex 的 jar」并打进 assets
// ─────────────────────────────────────────────────────────────────────────────
//
// 为什么必须是独立的 dex jar：服务端要以 `app_process` 在特权身份（root / shell）下运行，
// 用的正是 INJECT_EVENTS 这类系统权限，不能寄居在 App 进程里。`app_process` 用 CLASSPATH
// 加载，而 ART 只认 dex —— 所以 jar 里必须是 classes.dex，装普通 .class 没有意义。
//
// 为什么只收 13 个类：移植源（Operit 的 tools/shower）是把**整个 app 模块**打成了 jar，
// 除了 classes.dex 还塞着 resources.arsc(408KB)、res/ 图片、四个 ABI 的
// libandroidx.graphics.path.so 与 baseline profile —— 那是它那个用不到的 Compose 界面
// 带进来的，对 app_process 全是死重量。我们只编服务端真正需要的类。
//
// 构建产物是 generated 目录，**不提交 git**：那样源码改了而 jar 没重编会静默发出一个
// 旧服务端，正是本项目门禁要防的那类事故。

val displayServerJarDir = layout.buildDirectory.dir("generated/displayServer")

abstract class BuildDisplayServerJar : DefaultTask() {
    @get:InputDirectory
    abstract val serverSources: DirectoryProperty

    /** 两端共享的 Binder 协议类：定义在 app 源码树里（App 与服务端都要用）。 */
    @get:InputFiles
    abstract val sharedSources: ConfigurableFileCollection

    @get:Input abstract val compileSdkVersion: Property<Int>
    @get:Input abstract val buildToolsVersion: Property<String>
    @get:Input abstract val minSdkVersion: Property<Int>
    @get:Input abstract val androidSdkPath: Property<String>

    /** javac 的绝对路径。走 Java 21 工具链而不是守护进程自己的 JDK：本地开发者的 JDK
     *  可能比 21 新很多，那会产出 build-tools 36 的 d8 不认识的 class 版本。 */
    @get:Input abstract val javacPath: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject abstract val execOps: ExecOperations

    @TaskAction
    fun build() {
        val out = outputDir.get().asFile
        val classesDir = File(out, "classes")
        val dexDir = File(out, "dex")
        val jarFile = File(out, "dsh-display-server.jar")
        out.deleteRecursively()
        classesDir.mkdirs()
        dexDir.mkdirs()

        val sdk = File(androidSdkPath.get())
        // 平台目录名有三种形态，必须都认得：
        //   android-34        —— 传统形态
        //   android-34-ext10  —— 扩展级（extension level），不是给普通编译用的，忽略
        //   android-37.1      —— 新的**小版本号**形态。CI runner 上 `android-37` 这个目录
        //                        根本不存在，只有 37.0/37.1/37.2 —— 第一版只按
        //                        `android-<compileSdk>` 找，于是永远找不到；而退回逻辑又把
        //                        "37.1".toIntOrNull() 解析成 null 丢掉，最终错误地退到 android-36。
        // 另外 AGP 是懒加载平台的（直到编译任务才去装），而本任务挂在资源合并上、跑在那之前，
        // 所以即使名字对，也可能还没装 —— 这里的"有就用、没有就退回"同时覆盖这两种情况。
        fun platformVersion(dirName: String): Pair<Int, Int>? {
            val m = Regex("^android-(\\d+)(?:\\.(\\d+))?$").find(dirName) ?: return null
            return m.groupValues[1].toInt() to m.groupValues[2].ifEmpty { "0" }.toInt()
        }
        val platformsDir = File(sdk, "platforms")
        val candidates = platformsDir.listFiles { f -> f.isDirectory }
            ?.mapNotNull { d ->
                platformVersion(d.name)?.let { (major, minor) -> Triple(major, minor, File(d, "android.jar")) }
            }
            ?.filter { it.third.isFile }
            .orEmpty()
        val wantSdk = compileSdkVersion.get()
        val picked = candidates.filter { it.first == wantSdk }.maxByOrNull { it.second }
            ?: candidates.maxByOrNull { it.first * 1000 + it.second }
        check(picked != null) {
            "找不到任何可用的 android.jar：$platformsDir 下没有可用的平台目录。" +
                "请先安装平台（sdkmanager \"platforms;android-$wantSdk\"）"
        }
        val androidJar = picked.third
        if (picked.first != wantSdk) {
            logger.lifecycle(
                "display server: 没有 android-$wantSdk 平台，退回 ${androidJar.parentFile.name} 的 android.jar" +
                    "（服务端只用老 API，编得过即可）",
            )
        }
        val d8 = File(sdk, "build-tools/${buildToolsVersion.get()}/d8")
        check(d8.isFile) { "找不到 d8：$d8" }

        val sources = buildList {
            addAll(serverSources.get().asFile.walkTopDown().filter { it.extension == "java" }.toList())
            addAll(sharedSources.files.filter { it.extension == "java" })
        }
        check(sources.isNotEmpty()) { "displayserver/ 下没有找到任何 .java 源码" }

        // 1) javac。**不覆盖编译级别**：与 app 模块自己的 compileOptions(Java 21) 一致，
        //    否则用更低的 target 去读新版 android.jar 会撞 "class file has wrong version"。
        //    源码列表走 @argfile：Windows 与 Linux 都有命令行长度上限。
        val argFile = File(out, "javac.args")
        argFile.writeText(sources.joinToString("\n") { it.absolutePath } + "\n")
        val javac = javacPath.get()
        execOps.exec {
            commandLine(
                javac, "-encoding", "UTF-8", "-nowarn",
                "-d", classesDir.absolutePath,
                "-classpath", androidJar.absolutePath,
                "@${argFile.absolutePath}",
            )
        }

        // 2) d8：.class → classes.dex。--min-api 必须与 app 的 minSdk 一致，否则 dex 里会
        //    留下运行时才炸的 API 引用（编译期看不出来）。
        val classFiles = classesDir.walkTopDown().filter { it.extension == "class" }.map { it.absolutePath }.toList()
        check(classFiles.isNotEmpty()) { "javac 没有产出任何 .class" }
        execOps.exec {
            commandLine(
                listOf(
                    d8.absolutePath, "--min-api", minSdkVersion.get().toString(),
                    "--lib", androidJar.absolutePath,
                    "--output", dexDir.absolutePath,
                ) + classFiles,
            )
        }

        // 3) 只装 classes.dex。夹带资源对 app_process 没有意义，只会让 APK 变大。
        val dex = File(dexDir, "classes.dex")
        check(dex.isFile) { "d8 没有产出 classes.dex" }
        ZipOutputStream(BufferedOutputStream(FileOutputStream(jarFile))).use { zos ->
            zos.putNextEntry(ZipEntry("classes.dex"))
            dex.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
        logger.lifecycle(
            "display server: ${jarFile.name} ${jarFile.length() / 1024}KB " +
                "(dex ${dex.length() / 1024}KB, ${sources.size} 个源文件, min-api ${minSdkVersion.get()})",
        )
    }
}

/**
 * Android SDK 位置。
 *
 * 不用 `android.sdkDirectory`：那是 AGP 内部 API，跨版本改过名。CI 里 setup-build-env 会
 * 显式导出 ANDROID_HOME，本地开发者则习惯写在 local.properties —— 这两条覆盖了实际用法，
 * 且都不依赖 AGP 的内部结构。
 */
val resolvedAndroidSdkPath: String by lazy {
    val fromLocal = rootProject.file("local.properties").takeIf { it.isFile }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }.getProperty("sdk.dir")
    }
    fromLocal
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("找不到 Android SDK：在 local.properties 里写 sdk.dir，或设 ANDROID_HOME")
}

/**
 * javac 位置：优先 Java 21 工具链（与 `java { toolchain }` 同一个来源），
 * 拿不到就退回 Gradle 守护进程自己的 JDK。
 *
 * 这里在**配置期**解析、以字符串形式作为任务输入，而不是把 JavaToolchainService 注入任务：
 * 后者不是文档化的注入目标，跨 Gradle 版本会变；而 `javaToolchains` 这个扩展访问器
 * 与文件里已经在用的 `java { toolchain { ... } }` 来自同一个 java-base 插件。
 */
val resolvedJavacPath: String by lazy {
    val toolchainJavac = runCatching {
        javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
            .get().metadata.installationPath.file("bin/javac").asFile
    }.getOrNull()
    val picked = toolchainJavac ?: File(System.getProperty("java.home"), "bin/javac")
    logger.lifecycle(
        "display server javac: ${picked.absolutePath}" +
            if (toolchainJavac == null) "（Java 21 工具链不可用，退回守护进程 JDK —— 若 d8 报 class 版本不支持，就是这里）" else "",
    )
    picked.absolutePath
}

val buildDisplayServerJar = tasks.register<BuildDisplayServerJar>("buildDisplayServerJar") {
    group = "build"
    description = "把 displayserver/ 编成只含 classes.dex 的 jar，供 app_process 以特权身份加载"
    serverSources.set(rootProject.layout.projectDirectory.dir("displayserver/src/main/java"))
    sharedSources.from(
        fileTree("src/main/java/me/bmax/apatch/display") {
            include("IDisplayService.java", "IDisplayVideoSink.java", "DisplayBinderContainer.java")
        },
    )
    compileSdkVersion.set(androidCompileSdkVersion)
    buildToolsVersion.set(androidBuildToolsVersion)
    minSdkVersion.set(androidMinSdkVersion)
    androidSdkPath.set(resolvedAndroidSdkPath)
    javacPath.set(resolvedJavacPath)
    outputDir.set(displayServerJarDir)
}

android.sourceSets.getByName("main").assets.srcDir(displayServerJarDir.get().asFile)

// assets 是各 variant 合并后再打包的，合并任务必须排在这个任务之后。
tasks.matching { it.name.matches(Regex("merge.*Assets")) }.configureEach { dependsOn(buildDisplayServerJar) }
