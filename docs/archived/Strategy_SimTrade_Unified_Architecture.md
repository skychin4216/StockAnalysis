# 策略与模拟交易统一架构设计

> 基于 2026-06-07 讨论，梳理策略Tab和模拟交易Tab的职责边界、共享机制和改造方案。

---

## 1. 当前架构问题

### 1.1 重复实例化

```
StrategyListFragment               SimulationTradeFragment
    │                                    │
    ├─ StrategyEngine (实例A)            ├─ StrategyEngine (实例B)
    │   ├─ 7个策略                       │   ├─ 7个策略
    │   └─ 注册/启用状态独立             │   └─ 注册/启用状态独立
    │                                    │
    ├─ StockScreener (实例A)             ├─ StockScreener (实例B)
    └─ cachedResults (内存)              └─ 无缓存共享
```

**问题**：两个 Fragment 各维护一套 `StrategyEngine` + `StockScreener`，策略注册、启用状态、缓存完全隔离，浪费内存且行为不一致。

### 1.2 "拟合" 名不副实

- 按钮 "🔧 拟合" (`showFittingParams()`) ：仅读取 `strategy_trade_fitting_params` 表中的历史拟合数据
- 实际拟合逻辑：藏在 `SimulationTradeEngine.runTradeSession()` 内部，最多200轮，**用户无法控制**
- 业界标准："拟合" 应该是独立可控的迭代过程：调参 → 跑策略 → 模拟交易 → 对比次日真实数据 → 算准确率 → 保存最佳

### 1.3 模拟交易结果展示

当前 `showTradeReport()` 是纵向滚动卡片，不够直观。用户期望：**表格化展示** — 策略 × 周期 → 精选3只 → 买入价 → 卖出价 → 收益。

---

## 2. 目标架构

### 2.1 数据持久化决策

| 数据 | 保存？ | 理由 |
|------|--------|------|
| 执行策略结果 | ❌ 不保存 | 与模拟交易数据重复，模拟交易结果更完善(含多周期+买卖价+收益) |
| 模拟交易结果 | ✅ 保存到 DB | `daily_period_result` + `strategy_trade_orders`，可导出对比 |
| 调优参数 | ✅ 保存到 SharedPrefs | `tuned_weights_{strategyId}`，两Tab共享 |
| 调优历史 | ✅ 保存到 DB | `strategy_trade_fitting_params`，可导出分析 |

### 2.2 两个 Tab 的职责明确

```
┌──────────────────────────────────────────────────────────────┐
│                     Application 级                            │
│  ┌────────────────────────────────────────────────────────┐  │
│  │         StrategyEngine (单例)                           │  │
│  │  共享 8 个策略 + 启用状态 + 调优权重(SharedPrefs)       │  │
│  └───────────────────────┬────────────────────────────────┘  │
└──────────────────────────┼───────────────────────────────────┘
                           │
          ┌────────────────┴────────────────┐
          ▼                                 ▼
┌─────────────────┐               ┌─────────────────────────┐
│ 策略 Tab         │               │ 模拟交易 Tab             │
│ (快速筛查)       │               │ (完整交易验证)           │
│                 │               │                         │
│ 功能:            │               │ 功能:                    │
│ ├─ 执行策略      │               │ ├─ 选交易日+周期         │
│ │  跑7个策略     │               │ ├─ 策略×周期独立执行      │
│ │  即时看结果    │               │ │  (包括AI策略)          │
│ │  结果不保存    │               │ │  结果保存到DB           │
│ ├─ 调优(90%)     │               │ ├─ Top3精选+新闻/轮动调权│
│ │  快捷入口      │               │ ├─ 主板过滤              │
│ │  存SharedPrefs │               │ ├─ 模拟买入/卖出         │
│ └─ AI预测(异步)  │               │ ├─ 回溯:查次日涨跌       │
│                 │               │ ├─ 调优(90%)              │
│                 │               │ │  多周期数据迭代         │
│                 │               │ │  存SharedPrefs+DB       │
│                 │               │ └─ 数据导出/导入          │
└─────────────────┘               └─────────────────────────┘
```

### 2.3 调优引擎（两个入口，一个引擎）

```
┌──────────────────────────────────────────────────────┐
│              统一的 "调优引擎"                         │
│                                                      │
│  快捷入口 (策略Tab "调优(90%)")                        │
│    └─ 用30天单日数据, 快速调优, 保存到 SharedPrefs     │
│                                                      │
│  完整入口 (模拟交易Tab "调优(90%)")                    │
│    ├─ 选历史N天 × 多个周期                            │
│    ├─ 循环 (最多1000轮):                               │
│    │   ├─ 微调权重参数                                │
│    │   ├─ 对每天×每周期跑模拟交易                      │
│    │   ├─ 对比次日真实数据 → 算准确率+收益             │
│    │   └─ 连续N轮无提升 → 退出                        │
│    ├─ 更新 SharedPrefs (weightFactors)                │
│    └─ 写入 DB (strategy_trade_fitting_params)          │
│                                                      │
│  效果: 调优一次, 两处生效                              │
└──────────────────────────────────────────────────────┘
```

### 2.4 数据流

```
点击"执行策略"
  │
  ├─ 策略Tab: runSelectedStrategies()
  │   ├─ DB快照 / StockScreener实时扫描
  │   ├─ 7个非AI策略依次 screenWithData(stockList)
  │   ├─ 跳过 AI策略 (异步在对话框中做)
  │   └─ showResults() → 对话框 (含异步AI追加)
  │
  └─ (结果不持久化, 仅内存展示)

点击"模拟交易"
  │
  ├─ 读取 Engine.lastResults 或 重新执行策略
  ├─ for 每个策略 × 每个选中周期:
  │   ├─ 执行策略 → 原始信号
  │   ├─ (AI策略: 本周期其他策略结果注入后执行)
  │   ├─ 新闻力度评分 + 板块轮动惩罚
  │   ├─ 主板过滤
  │   ├─ 精选 Top3
  │   └─ 生成买入订单(模拟)
  ├─ 写入 daily_period_result + strategy_trade_orders
  ├─ 表格展示: 策略|周期|Top3|买入价|卖出价|收益
  └─ 次日点击"回溯" → 自动更新卖出价+收益

点击"调优" (任一入口)
  │
  ├─ 读 SharedPrefs 当前权重
  ├─ 循环调参 → 模拟交易 → 验证 → 保存最佳
  ├─ 写入 SharedPrefs → 策略Tab和模拟交易Tab自动读取
  └─ 写入 DB → 可导出对比历史调优记录
```

---

## 3. AI 策略在多周期中的执行方式

用户确认：**每周期独立执行**。

```
模拟交易：选中 周期 [当日, 近3日, 近10日]

  周期=当日
    ├─ 均线金叉策略(screenWithData) → 结果1
    ├─ 放量突破策略(screenWithData) → 结果2
    ├─ ...
    ├─ 超短线筛选(screenWithData)     → 结果7
    ├─ AI量化选股 ← 注入 [结果1~7] → screen()
    └─ 精选Top3 → 生成订单

  周期=近3日
    ├─ 均线金叉策略(screenWithData) → 结果1
    ├─ 放量突破策略(screenWithData) → 结果2
    ├─ ...
    ├─ AI量化选股 ← 注入 [结果1~7] → screen()
    └─ 精选Top3 → 生成订单
  
  ... (每个周期独立跑)
```

---

## 4. 模拟交易结果展示表格

### 4.1 表格设计 (用户需求)

| 策略 | 周期 | 精选3只 | 买入价 | 卖出价 | 收益 |
|------|------|---------|--------|--------|------|
| 均线金叉 | 当日 | 贵州茅台(600519) 1820.50 / 宁德时代(300750) 205.30 / 北方华创(002371) 388.60 | — | — | — |
| 均线金叉 | 近3日 | 比亚迪(002594) 265.00 / 海光信息(688041) 162.80 / 中际旭创(300308) 145.20 | — | — | — |
| AI量化 | 当日 | 中芯国际(688981) 98.50 / 寒武纪(688256) 112.30 / 韦尔股份(603501) 95.80 | — | — | — |

**首次执行**：买入价填充，卖出价/收益显示 "—"（待回溯）

**点击"回溯"后**：
| 策略 | 周期 | 精选3只 | 买入价 | 卖出价 | 收益 |
|------|------|---------|--------|--------|------|
| 均线金叉 | 当日 | 贵州茅台(600519) 1820.50 / 宁德时代(300750) 205.30 / 北方华创(002371) 388.60 | 1820.50 | 1856.91 | +2.00% |
| 均线金叉 | 近3日 | 比亚迪(002594) 265.00 / 海光信息(688041) 162.80 / 中际旭创(300308) 145.20 | 265.00 | 258.38 | -2.50% |

### 4.2 每行可展开

点击行 → 弹出详情：
- 精选3只完整列表（代码/名称/价格/强度/理由）
- 被过滤掉的股票及过滤原因
- 新闻评分/轮动惩罚明细
- AI精选理由

---

## 5. 改造实施清单

### 5.1 Phase 1: 共享引擎 (低风险)

| # | 改动 | 文件 |
|---|------|------|
| 1 | 创建 `StrategyEngineHolder` 单例，Application 初始化 | 新建 `strategy/StrategyEngineHolder.kt` |
| 2 | `StrategyListFragment` 改用 `StrategyEngineHolder.get()` | `ui/StrategyListFragment.kt` |
| 3 | `SimulationTradeFragment` 改用 `StrategyEngineHolder.get()` | `strategy/trade/SimulationTradeFragment.kt` |
| 4 | 删除两处 `initEngine()` 中的重复注册代码 | 两处 Fragment |

### 5.2 Phase 2: 模拟交易改造

| # | 改动 | 文件 |
|---|------|------|
| 5 | `executeTrade()` 直接使用引擎中的 enabled strategies 和 DB | `SimulationTradeFragment.kt` |
| 6 | 每个周期执行时，将本周期非AI结果注入 `AIPredictionStrategy` 然后再调 `screen()` | `SimulationTradeEngine.kt` |
| 7 | 结果展示改为 TableLayout（表格行可展开） | `SimulationTradeFragment.kt` |

### 5.3 Phase 3: 拟合改造

| # | 改动 | 文件 |
|---|------|------|
| 8 | "拟合"按钮改为执行独立拟合流程 | `SimulationTradeFragment.kt` |
| 9 | 实现 `runFitting()`：选30天 → 迭代调参 → 保存最佳 | `SimulationTradeEngine.kt` |
| 10 | 拟合结束更新策略 `weightFactors` | Engine |

### 5.4 Phase 4: 清理

| # | 改动 |
|---|------|
| 11 | 删除 `SimulationTradeEngine` 中内嵌的拟合代码（`runTradeSession` 里最多200轮那段） |
| 12 | `showResultsDialog()` 中的 AI 调用改为读取 `AIPredictionStrategy` 执行结果（已在 Phase 2 中由 `doExecute` 注入） |
| 13 | 删除 `runAIPredict()` (已完成) |

---

## 6. 改造后用户操作流程

### 日常流程
1. 打开 App → 策略Tab
2. 配置热门板块、交易日
3. 点击 **执行策略** → 看到7个策略的命中结果 → 底部 AI 预测追加
4. 切换到模拟交易Tab
5. 选择周期 (勾选当日/近3日/近10日等)
6. 点击 **模拟交易** → 看到表格：每行 策略×周期→Top3→买入价
7. 次日 → 点击 **回溯** → 自动更新卖出价+收益
8. 每周末 → 点击 **拟合** → 自动迭代优化 → 看到准确率提升

### 两个Tab不合并的理由
- 策略Tab满足了"快速扫一眼今天的策略在干嘛"
- 模拟交易Tab是"验证策略在历史上真实能赚多少"
- 两者的输入（当日数据 vs 多周期历史）和使用频率不同

---

## 7. 技术要点

### 7.1 StrategyEngineHolder 单例

```kotlin
// 文件: strategy/StrategyEngineHolder.kt
object StrategyEngineHolder {
    private var engine: StrategyEngine? = null
    
    fun init(context: Context) {
        if (engine != null) return
        val repo = StockDataSourceFactory.createDefaultRepository(context)
        val screener = StockScreener(repo)
        engine = StrategyEngine(context, screener).apply {
            registerStrategy(MovingAverageStrategy(screener))
            registerStrategy(VolumeBreakStrategy(screener))
            registerStrategy(LowValuationStrategy(screener))
            registerStrategy(GapUpMomentumStrategy(screener))
            registerStrategy(TurnoverFilterStrategy(screener))
            registerStrategy(EarlyMorningChaseStrategy(screener))
            registerStrategy(TailLowPickStrategy(screener))
            registerStrategy(AIPredictionStrategy(context))
        }
    }
    
    fun get(): StrategyEngine = engine ?: throw IllegalStateException("未初始化")
}
```

### 7.2 模拟交易结果 TableLayout 伪代码

```kotlin
fun showTradeResultTable(report: TradeSessionReport) {
    for (periodResult in report.periodResults) {
        val row = TableRow()
        row.addView(TextView(strategyName))
        row.addView(TextView(periodLabel))
        row.addView(TextView(top3Summary))
        row.addView(TextView(buyPrice))
        row.addView(TextView(sellPrice ?: "—"))
        row.addView(TextView(profit ?: "—"))
        row.setOnClickListener { showDetail(periodResult) }
        table.addView(row)
    }
}
```

---

## 8. 风险与回退

| 风险 | 缓解方案 |
|------|---------|
| 共享引擎后两处同时修改策略状态 | 引擎内部加锁或只读访问 |
| 模拟交易结果表格过长 | 按策略分组折叠，默认展开当日 |
| AI策略跑多周期 LLM 调用次数多 | 增加内存缓存(相同输入不重复调) |
| Backtrack 对比的次日数据不存在 | show "回溯数据缺失" |

**回退方案**：如果共享引擎出问题，恢复两处独立的 `initEngine()` 即可。

---

**最后更新**: 2026-06-07  
**状态**: 设计文档，待确认后进入实施