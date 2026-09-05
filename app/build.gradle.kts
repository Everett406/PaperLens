import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9.0 起内置 Kotlin 支持，不再（也不能）叠加 org.jetbrains.kotlin.android；
    // compose 编译器与序列化插件仍来自 KGP，正常可用。
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * 版本管理策略：
 * - 默认值仅用于本地开发；CI 从 git tag（vX.Y.Z）注入 -PversionName / -PversionCode。
 * - versionCode = major*1_000_000 + minor*1_000 + patch，保证随 SemVer 单调递增。
 */
val releaseVersionName: String = (project.findProperty("versionName") as String?)?.takeIf { it.isNotBlank() } ?: "1.0.0"
val releaseVersionCode: Int = (project.findProperty("versionCode") as String?)?.trim()?.toIntOrNull() ?: 1

// 签名配置：本地与 CI 均通过 keystore/keystore.properties 提供该文件（不入库），
// 缺失时 release 构建仍可执行（产出未签名 APK，用于 CI 冒烟验证）。
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.paperlens.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.paperlens.app"
        minSdk = 31
        targetSdk = 37
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                // storeFile 相对 keystore/ 目录解析（本地与 CI 一致）
                storeFile = rootProject.file("keystore").resolve(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // 低内存环境（2 核 4G 沙箱）下 lintVital 与 R8 并行会 OOM；
    // release 冒烟以 R8 为主，完整 lint 放到 CI（lintVital 在 CI 内存充裕，同样被禁用保持一致）
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
        }
    }
}

// jvmTarget 由 AGP 内置 Kotlin 跟随 compileOptions（Java 17）自动对齐。

dependencies {
    // AndroidX 基建
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose（BOM 统一版本；刻意不引 material3 / material-icons，
    // 理由：UI 全部走 Miuix 组件 + 少量自绘 HyperOS 风格组件 + 手写矢量图标，
    // 避免 M3 与 Miuix 两套视觉语言混杂，也减小依赖面。）
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.runtime)

    // 持久化
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    // 网络
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // UI 库（HyperOS 风格）与亚克力
    implementation(libs.miuix.ui)
    implementation(libs.miuix.squircle)
    implementation(libs.miuix.preference)
    implementation(libs.haze)
    implementation(libs.haze.materials)

    // Custom Tabs（打开 arXiv / alphaXiv 页面）
    implementation(libs.androidx.browser)

    // 图片加载：当前 MVP 无远程图片位，依赖先接入并预留
    // （未来用于论文图表示例预览；Coil3 网络层随 OkHttp 复用连接池）
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}
