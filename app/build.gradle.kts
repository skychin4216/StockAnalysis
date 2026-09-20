import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// ── Release 签名（本地 keystore.properties，不入库；缺失时回退 debug 签名以便出包测试）──
val keystoreProps = Properties()
val keystorePropsFile = rootProject.file("keystore.properties")
if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { keystoreProps.load(it) }
}
val hasReleaseSigning = keystorePropsFile.exists() &&
    !keystoreProps.getProperty("storeFile").isNullOrBlank() &&
    !keystoreProps.getProperty("storePassword").isNullOrBlank() &&
    !keystoreProps.getProperty("keyAlias").isNullOrBlank() &&
    !keystoreProps.getProperty("keyPassword").isNullOrBlank()

// 密钥加密主密钥（AES-256-GCM）：仅本地 keystore.properties 持有，注入 BuildConfig，
// 用于解密 assets/data/secrets.enc 中的云端密钥（该密文可安全提交 git）
val secretMasterKey = keystoreProps.getProperty("secret.masterKey", "")

android {
    namespace = "com.chin.stockanalysis"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.chin.stockanalysis"
        //minSdk = 21
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 密钥加密主密钥（本地 keystore.properties，不进 git）
        buildConfigField("String", "SECRET_MASTER_KEY", "\"$secretMasterKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 有正式签名配置则用之，否则回退 debug 签名（便于一键 assembleRelease 出包）
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    viewBinding {
        enable = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

configurations {
    implementation {
        exclude(group = "com.intellij", module = "annotations")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // WorkManager（OS 级定时兜底：实仓日K 每日 11:35/15:05 收盘自动补齐）
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Fragment & ViewPager2 (新增)
    implementation("androidx.fragment:fragment-ktx:1.6.1")
    implementation("androidx.viewpager2:viewpager2:1.0.0")

    // A股K线图
    //implementation("com.github.wusea:StockChart:1.1.0")
    // ✅ 绝对可用、免费、公开、无 401 的 A 股 K 线库
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // 网络请求
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 2026-09-20：腾讯云 COS 官方 Android SDK —— 取代自研 CosSigner 手工签名。
    // 背景：自研签名在 APK 侧持续报 403 SignatureDoesNotMatch，而 PC 侧用**相同算法、
    // 相同输入**（同一 host/key/headers/密钥）PUT 到同一键空间实测 **HTTP 200**；
    // 逐行比对算法、URL 编码、密钥值、键名、host、header 集合后全部一致，
    // 故问题锁定在「Android 平台运行时差异」。交由官方 SDK 处理签名/重试/分片。
    // 自研 CosSigner 保留为 fallback（SDK 初始化失败时自动回退，不影响可用性）。
    // exclude okhttp：避免与项目现有 okhttp 4.12.0 冲突。
    implementation("com.qcloud.cos:cos-android:5.9.52") {
        exclude(group = "com.squareup.okhttp3")
    }

    // Room 数据库（本地持久化）
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")
    // 独立 org.json 库，确保单元测试环境中 JSONObject 可用（Android 系统的 org.json 在纯 JVM 测试中不可用）
    implementation("org.json:json:20240303")

    // Markdown 渲染（Markwon v4.6.2）
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:syntax-highlight:4.6.2") {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
    implementation("io.noties.markwon:ext-latex:4.6.2")

    // ML Kit 文字識別（截圖OCR導入持倉）
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")

    // 协程（由 fragment-ktx 等 AndroidX 库间接依赖，无需显式声明版本）
    // 如果需要显式指定，可取消下面两行注释，并确保网络能访问 Maven Central
    // implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    // implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Test
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}