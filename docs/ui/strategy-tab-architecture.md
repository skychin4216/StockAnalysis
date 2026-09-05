# 策略 TAB 架构

> 最后更新：2026-09-06 | 代码位置：`ui/StrategyFragment.kt`、`strategy/trade/QuantWorkbenchFragment.kt`、`strategy/trade/EtfDipFragment.kt`
> 2026-09 改版：主入口从「四周期直开」改为「工作台 5 内页（短/中/长 + 实仓 + ETF低位）」。

---

## 一、入口层级

```
MainActivity Tab[3] 量化选股
└── StrategyFragment
    ├── Tab 0: QuantWorkbenchFragment（量化工作台，内部再分 5 页签）
    │   ├── 短 / 中 / 长 周期: Short/Mid/LongTermQuantFragment（QuantFragmentBase）
    │   ├── 💰 实仓: RealHoldingQuantFragment
    │   └── 🧲 ETF低位: EtfDipFragment（PC 桥 /etf_live，2026-09-06）
    ├── Tab 1: StrategyListFragment（策略沙盒）
    ├── Tab 2: StrategyImportFragment（数据管理/拟合调优/回溯/PC参数/远程）
    └── Tab 3: AIAnalysisFragment（AI 深度分析）
```

## 二、执行入口

四个周期 Fragment 共用 `DagTradeExecutor.executePipeline()` 执行各自 UseCase：

| 周期 | UseCase / Pipeline | 说明 |
|------|-------------------|------|
| 超短 | UltraShortUseCase → ultra_short_pipeline（并入短线页，2026-09-05） | 含盘口K线分析 |
| 短线 | ShortTermUseCase → short_term_pipeline | 含盘口K线分析 |
| 中线 | MidTermUseCase → mid_term_pipeline | 含打底仓守门/持仓风控 |
| 长线 | LongTermUseCase → long_term_pipeline | 含打底仓守门/持仓风控 |
| 实仓 | RealHoldingUseCase → real_holding_pipeline | 持仓列表 + 做T入口 + 风控 |
| ETF低位 | （数据来自 PC） | EtfDipFragment 拉 `GET /etf_live`（exe data_service），离线读缓存 |

## 三、执行结果展示（DagExecResult）

- ordersCount / mergeSummary / swapSummary / guardSummary
- patternSummary（K线形态摘要）
- stockFlowLines（节点过滤流水，中文原因）
- failureAnalysis（杀手节点定位）
- diagnosticSummary（持仓诊断）

## 四、相关文档

- [策略 · 超短线](../strategy/strategy-ultra-short.md)
- [策略 · 短线](../strategy/strategy-short-term.md)
- [策略 · 中线](../strategy/strategy-mid-term.md)
- [策略 · 长线](../strategy/strategy-long-term.md)
- [策略 · 实仓](../strategy/strategy-real-position.md)
