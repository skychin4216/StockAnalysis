plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.chin.stockanalysis"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.chin.stockanalysis"
        //minSdk = 21
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    // Fragment & ViewPager2 (新增)
    implementation("androidx.fragment:fragment-ktx:1.6.1")
    implementation("androidx.viewpager2:viewpager2:1.0.0")

    // A股K线图
    //implementation("com.github.wusea:StockChart:1.1.0")
    // ✅ 绝对可用、免费、公开、无 401 的 A 股 K 线库
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // 网络请求
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Room 数据库（本地持久化）
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")
    // 独立 org.json 库，确保单元测试环境中 JSONObject 可用（Android 系统的 org.json 在纯 JVM 测试中不可用）
    implementation("org.json:json:20240303")

    // 协程（由 fragment-ktx 等 AndroidX 库间接依赖，无需显式声明版本）
    // 如果需要显式指定，可取消下面两行注释，并确保网络能访问 Maven Central
    // implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    // implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Test
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}