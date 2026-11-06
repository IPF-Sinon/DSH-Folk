plugins {
    alias(libs.plugins.agp.lib)
    // kotlin-android 才是「编译 Kotlin」的那个插件；compose-compiler 只是它的编译器插件，
    // 单写后者会在 ext-api 里一个 .kt 都编不出来。
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlin.compose.compiler)
}

/**
 * 扩展 API 契约模块。
 *
 * 为什么单独一个模块（而不是留在 :app 里）：
 * 扩展市场里的扩展由**别的仓库**编译，它必须能依赖一份稳定的 API。而 `:app` 是
 * application 模块 —— AGP 明确禁止别的工程把它当依赖，所以 API 只要留在 :app，
 * 第三方就永远 import 不到 `DshExtension`。
 *
 * 这个模块里**只放契约**：接口、数据类、注册中心。任何引用宿主内部实现（DshRuntime、
 * DshEnv、DshState …）的类型都不许进来 —— 那等于把整个 app 变成公开 API，以后谁都改不动。
 * 宿主侧的实现在 :app（AppExtensionFs / AppExtensionHost / ExtensionEngine …）。
 */
android {
    namespace = "me.bmax.apatch.dsh.ext"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    // 只依赖 Compose 与协程：扩展要画界面、要读状态流，这两样是门槛最低的公共面。
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.core)
}
