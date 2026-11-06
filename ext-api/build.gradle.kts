@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.jvm.toolchain.JavaLanguageVersion

/**
 * 扩展 API 契约模块。
 *
 * 为什么单独一个模块（而不是留在 :app 里）：
 * 扩展市场里的扩展由**别的仓库**编译，它必须能依赖一份稳定的 API。而 `:app` 是
 * application 模块 —— AGP 明确禁止别的工程把它当依赖，所以 API 只要留在 :app，
 * 第三方就永远 import 不到 `DshExtension`。
 *
 * 这个模块里**只放契约**：接口、数据类、注册中心。任何引用宿主内部实现（DshRuntime、
 * DshEnv、DshState …）的类型都不许进来 —— 那等于把整个 app 变成公开 API。
 * 宿主侧的实现在 :app（AppExtensionFs / ExtensionHostImpl / ExtensionEngine …）。
 *
 * 插件声明有两点是踩过 CI 才对的：
 *  1. `com.android.library` 与 `com.android.application` 是同一份 AGP 产物，必须先在根
 *     build.gradle.kts 里 `apply false` 声明，否则会报「already on the classpath with an
 *     unknown version」；
 *  2. **不要**再应用 `org.jetbrains.kotlin.android`：AGP 9 起 android 插件已内置 Kotlin 支持，
 *     重复应用会被直接判错（提示 Remove the org.jetbrains.kotlin.android plugin）。
 */
plugins {
    alias(libs.plugins.agp.lib)
    alias(libs.plugins.kotlin.compose.compiler)
}

val androidCompileSdkVersion: Int by rootProject.extra
val androidMinSdkVersion: Int by rootProject.extra

android {
    namespace = "me.bmax.apatch.dsh.ext"
    compileSdk = androidCompileSdkVersion

    defaultConfig {
        minSdk = androidMinSdkVersion
    }

    buildFeatures {
        compose = true
    }
}

// 与 :app 完全一致的 JDK/Kotlin 目标：两边产物会进同一个 APK，
// 目标版本不一致会在 dex 合并时报「class file has wrong version」。
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

dependencies {
    // 只依赖 Compose 与协程：扩展要画界面、要读状态流，这两样是门槛最低的公共面。
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.core)
}
