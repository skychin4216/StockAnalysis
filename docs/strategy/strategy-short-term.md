# 策略 · 短线（1-14 天）

> 最后更新：2026-08-13 | Pipeline: `short_term_pipeline.xml`（22 节点）| UI: ShortTermQuantFragment

---

## 一、策略定位

- **持有周期**：1 天 ~ 2 周
- **风格**：技术面为主 + 资金情绪脉冲，**要热点热点热点**
- **特点**：含热度计算、新闻力度、板块轮动惩罚、新闻拦截、腾龙换鸟等短线专有节点
- **最大持仓**：5 只

## 二、Pipeline 拓扑（11 层）

```
Layer 0 (并行): data_import(60天) · market_context · multi_period_hot(多周期热门) · bg_manager · adaptive_params
Layer 1:        stock_pool · market_ma_unified(0.02) · holding_guard(持仓风控)
Layer 1.5 (并行): candidate_pool · zipline_factor(Zipline因子)
Layer 1.5b:     intraday_analysis(盘中K线 5min)
Layer 2 (并行): signal_merge · heat_score(热度)
Layer 3:        sector_boost → bounce_reversal → inst_tips
                ∥ strict_selection(均线粘合严选)
                ∥ ancestral_rules(大A祖训·SHORT)
Layer 3.5 (并行): news_strength(新闻力度) · rotation_penalty(板块轮动惩罚)
Layer 4:        smart_money_filter(minScore=55)  ← 受罚板块降分
                ∥ candle_pattern(K线形态侦测)
Layer 5:        news_guard(新闻拦截 impact75/sent-30)
Layer 6:        ai_predict(取 top5)
Layer 7:        generate_orders(maxHoldings=5)
Layer 8:        swap_weak(腾龙换鸟)
Layer 9 (并行): position_merge · fitting_save
Layer 10:       crosstab_publish(跨Tab发布)
```

## 三、严选参数（strict_selection）

| 参数 | 值 | 含义 |
|------|-----|------|
| convergenceThreshold | 3.0 | 均线粘合阈值 |
| useMA60 | true | 使用 60 日线 |
| convergenceDurationDays | 10 | 粘合持续天数 |
| volumeBreakoutRatio | 1.5 | 放量突破倍率 |
| minDrawdownPct | 20.0 | 最低回撤 ≥20% |
| minChangePct / requireChangePct | 3.0 / true | 当日涨幅 ≥3% |
| requireAboveAllMAs | true | 收盘站上所有均线 |
| lookbackDays | 60 | 回望窗口 |
| minPassCount | 7 | 至少 7 项通过 |

## 四、短线专属节点

### 4.1 热度计算（heat_score）

多周期热门聚合后的热度打分，作为候选补充。

### 4.2 新闻力度（news_strength）

`lookbackDays=3`：近 3 日新闻热度，为主力过滤提供依据。

### 4.3 板块轮动惩罚（rotation_penalty）

`thresholdDays=3 / penaltyPerExcess=10`：板块涨幅超过阈值天数越多，每超一天罚 10 分（追高惩罚）。

### 4.4 Zipline 因子（zipline_factor）

候选池 Zipline 因子预计算，供策略筛选使用。

## 五、大A祖训（短线档）

- 高开要跑：高开>2% 收阴 → **-10**（看承接，破均线才跑）
- 买无人问津：低位+低量 → +5
- 卖人声鼎沸：高位+放量滞涨 → **-15**
- 低位利空=利好：低位+大跌/长下影 → +8
- 高位利好=利空：高位+大涨/长上影 → **-12**

## 六、相关文档

- [策略 · 超短线](strategy-ultra-short.md)
- [策略 · 中线](strategy-mid-term.md)
- [选股思路](stock-picking-methodology.md)
