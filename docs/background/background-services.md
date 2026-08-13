# 后台服务架构

> 最后更新：2026-08-13 | 代码位置：`stock/database/AppBackgroundRunner.kt`、`notification/TradeNotifier.kt`、`news/HotSectorNewsUpdater.kt`

---

## 一、总览

```
MainActivity.onCreate
  └─ AppBackgroundRunner.start(context, scope)
       ├─ EastMoneyHotSectorSource.startPoolScheduler(scope)  热门板块池定时刷新
       ├─ StockDataCenter.init(context, scope)                股票资料中心初始化
       ├─ 启动一次性任务（并行）：
       │   ├─ 迁移超5天 AI 精选 → 自选股
       │   ├─ 增量同步 daily_snapshot（缺失交易日）
       │   ├─ 修复 strategy_trade_orders 缺失股票名称
       │   ├─ monitorTTradeOpportunities()  做T机会监控
       │   ├─ SectorPeriodTracker.update()  板块周期摘要
       │   ├─ SectorRotationEngine.saveDailySectorData()  板块每日数据
       │   └─ 热门板块成分股映射刷新（sector_stocks）
       └─ startPositionMonitor(context, scope)  持仓监控（每5分钟）
```

## 二、调度周期

| 任务 | 周期 | 说明 |
|------|------|------|
| 热门板块池刷新 | 定时 | `EastMoneyHotSectorSource` 池调度器 |
| 持仓监控 | 每 5 分钟 | 做T 信号 + 持仓风险 |
| 做T机会监控 | 每 5 分钟 | `monitorTTradeOpportunities`，覆盖 4 周期 + RealPosition |
| 板块每日数据 | 每日 | `SectorRotationEngine.saveDailySectorData` |
| 板块周期摘要 | 每日 | `SectorPeriodTracker.update` |
| 收盘结算 | 15:00-15:05 | 做T 收盘结算 + 成功率统计 |

## 三、量化互斥（isQuantRunning）

量化选股运行时，后台 AI 任务暂停，避免资源争抢：

```kotlin
pauseForQuant()        // 量化开始时调用
ensureNewsFreshThenPause() // 先刷新新闻因子再暂停
resumeAfterQuant()     // 量化结束，取消定时器并立即触发一次监控
```

## 四、持仓监控（startPositionMonitor）

- 每 5 分钟检查：做T 信号生成、持仓风控、自动卖出。
- 发通知：系统通知 + ServerChan（微信）+ PushPlus 多渠道。

## 五、通知服务（TradeNotifier）

### 5.1 渠道

| 渠道 | 配置键 | 默认 | 说明 |
|------|--------|------|------|
| Android 系统通知 | system_enabled | true | 状态栏推送，Channel `trade_signals` |
| 微信 (ServerChan) | wechat_enabled / serverchan_sendkey | false | ServerChan 公众号推送到微信 |
| PushPlus | pushplus_enabled / pushplus_token | false | PushPlus 推送 |
| 自动执行 | auto_execute_enabled / auto_execute_threshold | false / 0.7 | 做T 信号自动执行 |

### 5.2 推送内容

- 做T 信号（含近 7 日成功率 `getSuccessRate(7)`）
- 建仓信号
- 持仓风控提醒
- 自动执行结果

## 六、其他后台能力

| 能力 | 说明 |
|------|------|
| HotSectorNewsUpdater | 板块新闻分析（更新后入库 `sector_news`） |
| 前置预热 | 板块成分股预热 `prefetchSectorStocks()` |
| 数据自愈 | 启动修复缺失股票名 / 增量同步缺失交易日 |

## 七、相关文档

- [做T系统](../topology/t-trade-system.md)
- [策略 · 实仓](../strategy/strategy-real-position.md)
- [股票 TAB 架构](../ui/stock-tab-architecture.md)
