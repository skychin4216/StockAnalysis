# 量化选股策略模块 (strategy/)

## 架构

```
strategy/
├── README.md
├── Strategy.kt                  # 策略接口 + StrategyCategory + StrategySource
├── StrategyConfig.kt            # 参数配置（全市场/池扫描/自定义/板块/自选股）
├── StrategyEngine.kt            # 策略引擎（注册/删除/启用/并发扫描）
├── data/
│   └── StockScreener.kt         # 市场数据扫描器（东方财富全市场 + 板块 + 自选池）
├── models/
│   ├── ScreeningResult.kt       # 扫描结果模型
│   ├── StrategySignal.kt        # 单个策略信号（强度/操作建议/详情）
│   └── WeightFactor.kt          # 权重因子模型（key/label/weight/description）
├── strategies/
│   ├── MovingAverageStrategy.kt # 均线金叉策略（趋势类）
│   ├── VolumeBreakStrategy.kt   # 放量突破策略（量价类）
│   ├── LowValuationStrategy.kt  # 低估值策略（价值类）
│   ├── GapUpMomentumStrategy.kt # 高开高走策略（动量类）
│   └── TurnoverFilterStrategy.kt# 换手率活跃策略（量价类）
└── ui/
    ├── StrategyAdapter.kt       # 策略列表卡片适配器
    ├── StrategyFragment.kt      # 列表页（扫描+板块选择+自选池+表格结果）
    └── StrategyDetailFragment.kt# BottomSheet 详情/编辑页（权重拖拽+名称/描述编辑）
```

## 策略分类

| 类别 | 图标 | 说明 | 内置策略 |
|------|------|------|---------|
| TREND | 📈 | 趋势类 — 均线金叉、趋势跟踪 | 均线金叉策略 |
| MOMENTUM | 🚀 | 动量类 — 价格动量突破 | 高开高走策略 |
| VALUE | 💎 | 价值类 — 低估值筛选 | 低估值策略 |
| VOLUME | 📊 | 量价类 — 放量突破 | 放量突破策略、换手率活跃 |
| CUSTOM | 🔧 | 自定义策略 | 用户自定义 |

## 内置策略（5个）

### 1. 均线金叉策略 (`ma_golden_cross`) 📈 TREND
- 5 日均线上穿 20 日均线 + 成交量放大确认
- 权重：动量40% + 量比30% + 价格位置30%

### 2. 放量突破策略 (`volume_break`) 📊 VOLUME
- 成交量放大 2 倍以上 + 价格突破近期高点
- 权重：量比40% + 突破30% + 涨幅30%

### 3. 低估值策略 (`low_valuation`) 💎 VALUE
- 市盈率 < 行业均值 70% + 价格企稳
- 权重：估值40% + 企稳30% + 流动性30%

### 4. 高开高走策略 (`gap_up_momentum`) 🚀 MOMENTUM
- 高开 2% 以上 + 持续放量 + 涨超 5%
- 权重：开盘强度40% + 盘中动量40% + 成交量20%

### 5. 换手率活跃策略 (`turnover_active`) 📊 VOLUME
- 换手率 > 5% + 涨幅 > 3% + 流通市值适中
- 权重：换手率50% + 涨幅30% + 流通性20%

## 扫描范围

策略扫描支持三种范围：

| 范围 | 说明 | UI 入口 |
|------|------|--------|
| 全市场 | 东方财富前200只（默认） | ▶ 执行全部策略 |
| 指定板块 | 化工/半导体/医药/新能源... | 扫描前选择板块 |
| 自选股池 | 用户自选股票列表 | 扫描前选择自选股 |

## 权重编辑（详情页）

```
┌────────────────────────────────┐
│ 📈 均线金叉策略 [系统内置]       │
├────────────────────────────────┤
│ 策略名称: [均线金叉策略______]  │
│ 策略描述: [5日均线上穿20日...]  │
├────────────────────────────────┤
│        权重配置 (合计=100%)     │
│ 动量得分  [━━━━━━━60%━━━] 60% ✕│
│ 量比得分  [━━━━━30%━━]   30% ✕ │
│ 价格位置  [━━10%━]        10% ✕│
│        合计: 100% ✅           │
│ [+ 添加因子]                   │
├────────────────────────────────┤
│        [💾 保存修改]           │
└────────────────────────────────┘
```

## 扫描结果（表格输出）

```
📊 扫描结果
┌────────────────────────────────────────┐
│ 📈 均线金叉策略 (12只/850ms)             │
│ 名称      代码       强度   价格   涨幅   │
│ 贵州茅台  sh600519   85%  1650.00 +2.5% │
│ 比亚迪    sz002594   78%   285.40 +3.2% │
│ 宁德时代  sz300750   72%   225.60 +5.1% │
└────────────────────────────────────────┘
```

## 使用方式

```kotlin
val engine = StrategyEngine(context, screener)
engine.registerStrategy(MovingAverageStrategy(screener))
engine.registerStrategy(GapUpMomentumStrategy(screener))

// 全市场扫描
engine.runAll(scope, onComplete = { results -> ... })

// 指定板块扫描
strategy.apply { config.stockPool = screener.getSectorStocks("半导体") }
engine.runOne(strategy.id, scope, onResult = { result -> ... })

// 自选股池扫描
strategy.apply { config.stockPool = watchlistCodes }
engine.runAll(scope, onComplete = { results -> ... })

// 增删策略
engine.registerStrategy(myCustomStrategy)
engine.removeStrategy("ma_golden_cross")