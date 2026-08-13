# 策略 · 长线（180-365 天）

> 最后更新：2026-08-13 | Pipeline: `long_term_pipeline.xml`（19 节点）| UI: LongTermQuantFragment

---

## 一、策略定位

- **持有周期**：180 ~ 365 天
- **风格**：价值 + 趋势 + 打底仓守门，弱化短线扰动
- **特点**：估值过滤（PE 低估）+ 打底仓守门 + 防守高息 + 机构积累
- **最大持仓**：5 只

## 二、Pipeline 拓扑（10 层）

```
Layer 0 (并行): data_import(120天) · adaptive_params · multi_period_hot · market_context · bg_manager
Layer 1 (并行): stock_pool · market_ma_unified(0.02) · sector_stock_pool(板块精选池) · holding_guard
Layer 1.5:      candidate_pool
Layer 2 (并行): signal_merge · cross_day_aggregation(跨日聚合 10日/top20)
Layer 3:        sector_boost → bounce_reversal
                ∥ strict_selection(均线粘合严选)
                ∥ base_position_guard(打底仓守门·LONG)
                ∥ ancestral_rules(大A祖训·LONG)
                ∥ inst_tips(机构线索)
Layer 4 (并行): news_strength · rotation_penalty · defensive_dividend(防守高息)
Layer 5:        smart_money_filter(minScore=55)
                ∥ candle_pattern
Layer 6:        news_guard(新闻拦截)
Layer 7:        ai_predict(取 top5)
Layer 8 (并行): generate_orders(maxHoldings=5, orderType=LongTermQuant) · fitting_save
Layer 9:        crosstab_publish
```

## 三、严选参数（strict_selection）

| 参数 | 值 | 含义 |
|------|-----|------|
| convergenceThreshold | 2.0 | 均线粘合阈值（长线最宽） |
| useMA60 | true | 使用 60 日线 |
| convergenceDurationDays | 30 | 粘合持续天数 |
| minDrawdownPct | 30.0 | 最低回撤 ≥30% |
| requireMA60Rising | true | 60 日线向上 |
| lookbackDays | 250 | 回望窗口 |
| minPassCount | 6 | 至少 6 项通过 |

## 四、长线专属节点

### 4.1 打底仓守门（base_position_guard，LONG 档）

- 三天不新低：最近 3 天 low 均 ≥ 前 3 天最低 low
- MA5/MA10/MA30 均线粘合向上
- 逃顶提示：3 天急跌 >5% 或跌破 3 日最低 low

### 4.2 防守高息（defensive_dividend）

`maxPb=1.2 / maxDebt=60 / maxCandidates=5`：更严格的估值防守。

### 4.3 跨日聚合（cross_day_aggregation）

`windowDays=10 / topN=20`：10 日窗口聚合，过滤单日噪声。

## 五、大A祖训（长线档）

- 买无人问津：→ +15（长线主场）
- 低位利空=利好：→ +20
- 高位利好=利空：→ **-15**
- 卖人声鼎沸：→ **-8**
- 高开要跑：→ **-5**（最不敏感）

## 六、相关文档

- [策略 · 中线](strategy-mid-term.md)
- [选股思路](stock-picking-methodology.md)
- [策略 · 实仓](strategy-real-position.md)
