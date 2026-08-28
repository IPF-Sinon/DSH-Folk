@file:Suppress("UnstableApiUsage")

import com.android.build.gradle.tasks.PackageAndroidArtifact
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.io.File
import java.io.FileInputStream

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
        applicationId = "io.github.ipfsinon.dshfolk"
        minSdk = androidMinSdkVersion
        targetSdk = androidTargetSdkVersion
        versionCode = managerVersionCode
        versionName = managerVersionName
        buildConfigField("boolean", "DEBUG_FAKE_ROOT", localProperties.getProperty("debug.fake_root", "false"))

        base.archivesName = "DSH-Folk_${managerVersionCode}_${managerVersionName}_on_${branchName}"

        ndk.abiFilters.addAll(arrayOf("arm64-v8a"))
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
        // proot/proroot 等预编译 .so 放在 app/libs/arm64-v8a/ 下，由 CI 下载后就位
        jniLibs.directories += "libs"
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
