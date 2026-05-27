# 量化选股策略模块 (strategy/)

## 架构

```
strategy/
├── README.md
├── Strategy.kt                  # 策略接口 + 5 种分类枚举
├── StrategyConfig.kt            # 参数配置（全市场/池扫描/自定义）
├── StrategyEngine.kt            # 策略引擎（注册/删除/启用/并发扫描）
├── data/
│   └── StockScreener.kt         # 市场数据扫描器（东方财富全市场 API）
├── models/
│   ├── ScreeningResult.kt       # 扫描结果模型
│   └── StrategySignal.kt        # 单个策略信号（强度/操作建议/详情）
└── strategies/
    ├── MovingAverageStrategy.kt # 均线金叉策略（趋势类）
    ├── VolumeBreakStrategy.kt   # 放量突破策略（量价类）
    └── LowValuationStrategy.kt  # 低估值策略（价值类）
```

## 策略分类

| 类别 | 图标 | 说明 |
|------|------|------|
| TREND | 📈 | 趋势类 — 均线金叉、趋势跟踪 |
| MOMENTUM | 🚀 | 动量类 — 价格动量突破 |
| VALUE | 💎 | 价值类 — 低估值筛选 |
| VOLUME | 📊 | 量价类 — 放量突破 |
| CUSTOM | 🔧 | 自定义策略 |

## 内置策略

### 1. 均线金叉策略 (`ma_golden_cross`)
- 5 日均线上穿 20 日均线
- 配合成交量放大确认
- 信号强度：涨幅 40% + 量比 30% + 价格位置 30%

### 2. 放量突破策略 (`volume_break`)
- 成交量放大 2 倍以上
- 价格突破近期高点
- 信号强度：量比 40% + 突破 30% + 涨幅 30%

### 3. 低估值策略 (`low_valuation`)
- 市盈率 < 行业均值 70%
- 价格企稳（横盘/跌幅收窄）
- 信号强度：估值 40% + 企稳 30% + 流动性 30%

## 使用方式

```kotlin
val engine = StrategyEngine(context, screener)
engine.registerStrategy(MovingAverageStrategy(screener))
engine.registerStrategy(VolumeBreakStrategy(screener))
engine.registerStrategy(LowValuationStrategy(screener))

// 并发执行所有启用策略
engine.runAll(lifecycleScope,
    onProgress = { result -> addResultCard(result) },
    onComplete = { results -> updateStatus(results) }
)

// 增删策略
engine.registerStrategy(myCustomStrategy)
engine.removeStrategy("ma_golden_cross")
engine.setEnabled("low_valuation", false)