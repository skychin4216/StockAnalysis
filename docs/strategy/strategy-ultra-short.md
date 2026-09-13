# 策略 · 超短线（1 天）

> 最后更新：2026-08-13 | Pipeline: `ultra_short_pipeline.xml`（20 节点）| UI: UltraShortQuantFragment

---

## 一、策略定位

- **持有周期**：1 天
- **风格**：纯技术面，隔夜情绪溢价，快进快出
- **特点**：流程最精简，盘中 K 线分析（5 分钟级），T+1 自动卖出
- **最大持仓**：3 只

## 二、Pipeline 拓扑（10 层）

```
Layer 0 (并行): data_import(30天) · market_context · bg_manager · adaptive_params(大盘防守)
Layer 1 (并行): stock_pool · market_ma_unified(0.015) · holding_guard(持仓风控)
Layer 1.5:      candidate_pool(候选池过滤)
Layer 1.5b:     intraday_analysis(盘中K线 5min/≥5根)
Layer 2:        signal_merge(策略动态注入)
Layer 3:        sector_boost → bounce_reversal → inst_tips
                ∥ strict_selection(均线粘合严选)
                ∥ ancestral_rules(大A祖训·ULTRA_SHORT)
Layer 4:        smart_money_filter(minScore=30)
                ∥ candle_pattern(K线形态侦测)
Layer 5:        ai_predict(取 top5)
Layer 6:        generate_orders(maxHoldings=3)
Layer 7:        swap_weak(腾龙换鸟·持仓满卖弱买强)
Layer 8:        position_merge(持仓合并)
Layer 9 (并行): t1_auto_sell(止损-2%/止盈+3%) · fitting_save
```

## 三、严选参数（strict_selection）

| 参数 | 值 | 含义 |
|------|-----|------|
| convergenceThreshold | 3.0 | 均线粘合阈值 |
| useMA60 | false | 不要求 60 日线 |
| convergenceDurationDays | 5 | 粘合持续天数 |
| volumeBreakoutRatio | 2.5 | 放量突破倍率 |
| minChangePct / requireChangePct | 4.0 / true | 当日涨幅 ≥4% |
| minDrawdownPct | 10.0 | 最低回撤 ≥10% |
| requireCloseAboveConvergenceTop | true | 收盘站上粘合区上沿 |
| requireOpenBelowMAs | true | 开盘低于三线+收盘站上5日线 |
| lookbackDays | 30 | 回望窗口 |
| minPassCount | 8 | 至少 8 项通过 |

## 四、T+1 自动卖出（t1_auto_sell）

- `stopLossPct = -2.0`：触发卖出
- `takeProfitPct = 3.0`：触发卖出
- 每个持仓独立评估，异常跳过。

## 五、超短专属：盘中K线分析（intraday_analysis）

交易时段自动获取 5 分钟线（≥5 根），评估日内趋势与买点（冰点/沸点），为超短提供日内择时依据。

## 六、大A祖训（超短档）

- 高开要跑：高开>2% 收阴 → **-15**
- 买无人问津：低位+低量 → +3（不太适用）
- 卖人声鼎沸：高位+放量滞涨 → **-12**
- 低位利空=利好：低位+大跌/长下影 → +5（轻仓博反抽）
- 高位利好=利空：高位+大涨/长上影 → **-10**

## 七、主力资金过滤

`smart_money_filter.minScore = 30` —— 超短 V 型票主力分天然低，阈值放宽。

## 八、相关文档

- [策略 · 短线](strategy-short-term.md)
- [选股思路](stock-picking-methodology.md)
- [策略 TAB 架构](../ui/strategy-tab-architecture.md)
