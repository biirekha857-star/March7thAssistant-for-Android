import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    // 代码包名（R 类、清单里 `.MainActivity` 这类相对名都基于它）。
    // 这个可以随便改。
    namespace = "com.miguanm7a.hsr"

    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        // 包名是 com.m7ahsr（10 字符）。
        //
        // ⚠️ 这个长度不是随便定的，是**硬上限**：
        //
        // 内置 Termux bootstrap 的二进制把 /data/data/<包名>/files/usr 作为
        // $PREFIX 硬编码在 ELF 的 .dynstr 里（DT_RUNPATH 指向它）。字符串表里
        // 所有动态符号按偏移量引用，所以该字符串**只能改短、不能改长** —— 改长
        // 会让后面全部错位、整张符号表作废。
        //
        //     len("/data/data/") + len(包名) + len("/files/usr") = 21 + len(包名)
        //     原前缀 31 字节  =>  len(包名) <= 10
        //
        // bootstrap 资产已用 scripts/reprefix_bootstrap.py 重打前缀
        // （等长纯字节替换，无重定位）。**改这一行必须同时：**
        //   1. 重跑 reprefix_bootstrap.py 更新 assets/bootstrap/*.zip
        //   2. 同步 TermuxPaths.APP_PACKAGE
        // 否则 bash/proot 找不到自己的库，表现为「点了没反应」。
        //
        // TermuxPrefixInvariantTest 守着这个一致性。
        applicationId = "com.m7ahsr"

        minSdk = 24
        // Termux 本身 targetSdk 28。保持 28 才允许从应用私有目录 exec 二进制，
        // 并沿用旧版 /sdcard 存储模型 —— 这是整套方案能跑起来的前提。
        targetSdk = 28

        versionCode = 13
        versionName = "1.0.12-beta"

        ndk {
            // 内置 bootstrap 与容器镜像都是 aarch64，其它架构需各自重建资产
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // BuildConfig.APPLICATION_ID / VERSION_NAME / DEBUG 都在用
        buildConfig = true
    }

    androidResources {
        // 两个大资产必须原样存储，不能被 AGP 再压一次
        noCompress += listOf("zip")
    }

    packaging {
        jniLibs {
            // 保持 .so 未压缩，targetSdk 28 下可从私有目录直接 exec
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // AGP 9 的 K2 UAST 在分析 .gradle.kts 时会崩
        // (findFirCompiledSymbol on non-compiled declaration)，
        // 导致 release 构建的 lintVitalRelease 失败。故关闭。
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 内置终端（Termux 上游模块，Apache-2.0）
    implementation(project(":terminal-emulator"))
    implementation(project(":terminal-view"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)

    // 实时监看：用 OkHttp 的 WebSocket 连 Chrome DevTools Protocol
    implementation(libs.okhttp)

    // 测试直接读真实的 bootstrap / rootfs 资产做校验
    testImplementation(libs.junit)
    testImplementation(libs.commons.compress)

    debugImplementation(libs.androidx.ui.tooling)
}
