pluginManagement {
    repositories {
        // 2026-09-25：国内镜像优先，修复 Android Studio 直连官方仓库
        // “Connection timed out: getsockopt”（TCP 能连但大数据量传输易被掐）。
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        // 官方源兜底（网络好时自动走，镜像缺件时不至于失败）
        gradlePluginPortal()
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 2026-09-25：阿里云镜像优先（google/public），修复依赖下载超时
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        // 腾讯云官方 Maven —— COS Android SDK（com.tencent.qcloud:cosxml）
        // 发布在此，Maven Central 上并没有（这是先前 "Could not find" 的原因）。
        maven { url = uri("https://mirrors.tencent.com/repository/maven/tencent_public/") }
    }
}

rootProject.name = "AQuant"
include(":app")
