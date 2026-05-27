# 自动刷新模块 (autorefresh/)

## TLL 架构

自动刷新策略基于 **TLL (Time-Location-Limitation)** 模型：

| 维度 | 策略 | 说明 |
|------|------|------|
| **Time** | 交易时段 60s / 盘前盘后 5min | 非交易日+已有当日价格 → 不刷新 |
| **Location** | Fragment 可见时启用 | `StockListFragment` 可见 → 启动，离开 → 停止 |
| **Limitation** | 单次最多 10 只 | 网络失败自动降级，不阻塞 UI |

## 文件说明

| 文件 | 职责 |
|------|------|
| `StockAutoRefreshManager.kt` | 核心管理器 — `startWatching()` / `stopWatching()` / `forceRefreshNow()` |
| `RefreshPolicy.kt` | TLL 策略 — 判断是否应刷新 + 计算刷新间隔 |
| `PriceUpdateNotifier.kt` | 观察者模式价格更新通知器 |

## 使用方式

```kotlin
val manager = StockAutoRefreshManager(repository)
manager.startWatching(codes = listOf("sh600519", "sz002594"), scope = lifecycleScope)

// 监听价格变化
PriceUpdateNotifier.addListener(object : PriceUpdateListener { ... })

// 停止
manager.stopWatching()