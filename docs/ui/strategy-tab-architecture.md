# 策略 TAB 架构

> 最后更新：2026-08-13 | 代码位置：`ui/StrategyFragment.kt`、`ui/UltraShortQuantFragment.kt`、`ui/ShortTermQuantFragment.kt`、`ui/MidTermQuantFragment.kt`、`ui/LongTermQuantFragment.kt`、`ui/RealHoldingQuantFragment.kt`

---

## 一、入口层级

```
MainActivity Tab[3] 策略
└── StrategyFragment
    ├── Tab 0: UltraShortQuantFragment（超短线 · 持有1天）
    ├── Tab 1: ShortTermQuantFragment（短线 · 1-14天）
    ├── Tab 2: MidTermQuantFragment（中线 · 30-180天）
    ├── Tab 3: LongTermQuantFragment（长线 · 180-365天）
    └── Tab 4: RealHoldingQuantFragment（实仓 · 持仓管理）
```

## 二、执行入口

四个周期 Fragment 共用 `DagTradeExecutor.executePipeline()` 执行各自 UseCase：

| 周期 | UseCase / Pipeline | 说明 |
|------|-------------------|------|
| 超短 | UltraShortUseCase → ultra_short_pipeline | 含盘口K线分析 |
| 短线 | ShortTermUseCase → short_term_pipeline | 含盘口K线分析 |
| 中线 | MidTermUseCase → mid_term_pipeline | 含打底仓守门/持仓风控 |
| 长线 | LongTermUseCase → long_term_pipeline | 含打底仓守门/持仓风控 |
| 实仓 | — | 持仓列表 + 做T入口 + 风控 |

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
