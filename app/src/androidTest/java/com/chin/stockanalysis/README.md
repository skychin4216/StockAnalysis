# Instrumented Test (/androidTest)

## 说明

本目录包含需要在 Android 设备或模拟器上运行的**集成测试**。

## 文件说明

| 文件 | 职责 |
|------|------|
| `StockAnalysisTest.kt` | ★ 主测试类：意图识别 + 价格验证 + 多股票回归 |
| `testdata/TestPrompts.kt` | 16 个预定义测试 prompt（单/多股票查询、指数、技术分析等） |
| `testdata/PriceAssertions.kt` | 价格合理性断言（价格区间/涨跌幅/成交量/时间戳） |
| `ExampleInstrumentedTest.kt` | 示例测试，测试 App 的 applicationId |

## 运行测试

```bash
# 命令行（需要连接设备/模拟器）
./gradlew connectedAndroidTest --tests "com.chin.stockanalysis.StockAnalysisTest"

# Android Studio
右键 StockAnalysisTest → Run 'StockAnalysisTest'
```

## 测试覆盖

| 测试方法 | 用例数 | 覆盖内容 |
|---------|--------|---------|
| `testAllPrompts_intentRecognition` | 16 | 意图识别准确性 |
| `testSingleStockPriceAccuracy` | 1 | 单股票价格查询 |
| `testMultiStockQuery_regression` | 1 | ★ 多股票查询回归（需求1修复） |
| `testPriceDataValidity` | 3 | 实时价格数据合理性 |
| `testStockCodeQuery` | 1 | 代码查询准确度 |
| `testIndexQuery` | 1 | 指数查询 |
| `testNonStockQuery` | 1 | 非股票意图识别 |
| `testEmptyInput` | 1 | 空输入处理 |

## 与单元测试 (test/) 的区别

| 特性 | test/ | androidTest/ |
|------|--------|------------|
| 运行环境 | JVM | Android 设备/模拟器 |
| 速度 | 快 | 慢 |
| 能否用 Android API | ❌ 不能 | ✅ 可以 |
| `ModelDirectTest` 等 | ✅ | ❌ |
| `ProviderFlowTest` 等 | ✅ | ❌ |
| 网络请求 | ❌ 需要 mock | ✅ 可直接发起 HTTP 请求到东方财富/Sina |