pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        // 2026-09-20：腾讯云官方 Maven —— COS Android SDK（com.tencent.qcloud:cosxml）
        // 发布在此，Maven Central 上并没有（这是先前 "Could not find" 的原因）。
        maven { url = uri("https://mirrors.tencent.com/repository/maven/tencent_public/") }
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
    }
}

rootProject.name = "AQuant"
include(":app")

