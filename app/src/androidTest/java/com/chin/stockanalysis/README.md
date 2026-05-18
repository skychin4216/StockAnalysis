# Instrumented Test (/androidTest)

## 说明

本目录包含需要在 Android 设备或模拟器上运行的**集成测试**。

## 文件说明

| 文件 | 职责 |
|------|------|
| `ExampleInstrumentedTest.kt` | 示例测试，测试 App 的 applicationId |

## 与单元测试 (test/) 的区别

| 特性 | test/ | androidTest/ |
|------|--------|------------|
| 运行环境 | JVM | Android 设备/模拟器 |
| 速度 | 快 | 慢 |
| 能否用 Android API | ❌ 不能 | ✅ 可以 |
| `ModelDirectTest` 等 | ✅ | ❌ |
| `ProviderFlowTest` 等 | ✅ | ❌ |

## 何时用 androidTest

- 需要测试 Activity/Fragment/Service 等 Android 组件
- 需要测试 SharedPreferences/数据库/文件系统
- 需要测试设备上的特殊行为（如旋转、横竖屏等）
- Espresso UI 测试

## 当前状态

目前本目录只有 `ExampleInstrumentedTest.kt` 示例测试。模型直接测试和 Provider 流程测试都在 `test/` 目录下。