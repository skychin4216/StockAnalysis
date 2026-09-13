# 策略 · 中线（30-180 天）

> 最后更新：2026-08-13 | Pipeline: `mid_term_pipeline.xml`（20 节点）| UI: MidTermQuantFragment

---

## 一、策略定位

- **持有周期**：30 ~ 180 天
- **风格**：打底仓守门 + 主力过滤 + 跨日聚合 + 防守高息
- **特点**：含板块精选池、候选池过滤、防守高息（熊市防御）、腾龙换鸟
- **最大持仓**：5 只

## 二、Pipeline 拓扑（11 层）

```
Layer 0 (并行): data_import(60天) · adaptive_params · multi_period_hot · market_context · bg_manager
Layer 1 (并行): stock_pool · market_ma_unified(0.02) · sector_stock_pool(板块精选池) · holding_guard
Layer 1.5:      candidate_pool
Layer 2 (并行): signal_merge · heat_score · cross_day_aggregation(跨日聚合 5日/top20)
Layer 3:        sector_boost → bounce_reversal
                ∥ strict_selection(均线粘合严选)
                ∥ base_position_guard(打底仓守门·MID)
                ∥ ancestral_rules(大A祖训·MID)
                ∥ inst_tips(机构线索)
Layer 4 (并行): news_strength · rotation_penalty · defensive_dividend(防守高息)
Layer 5:        smart_money_filter(minScore=55)
Layer 6:        news_guard(新闻拦截)
                ∥ candle_pattern(K线形态)
Layer 7:        ai_predict(取 top5)
Layer 8:        generate_orders(maxHoldings=5, orderType=MidTermQuant)
Layer 9 (并行): swap_weak(腾龙换鸟) · fitting_save
Layer 9.5:      position_merge(换鸟后再入库)
Layer 10:       crosstab_publish
```

## 三、严选参数（strict_selection）

| 参数 | 值 | 含义 |
|------|-----|------|
| convergenceThreshold | 2.5 | 均线粘合阈值（中线放宽） |
| useMA60 | true | 使用 60 日线 |
| convergenceDurationDays | 15 | 粘合持续天数 |
| moderateVolumeLower / Upper | 1.2 / 1.8 | 温和放量区间 |
| minDrawdownPct | 30.0 | 最低回撤 ≥30% |
| requireMA60Rising | true | 60 日线向上 |
| requireAboveAllMAs | true | 收盘站上所有均线 |
| lookbackDays | 120 | 回望窗口 |
| minPassCount | 7 | 至少 7 项通过 |

## 四、中线专属节点

### 4.1 打底仓守门（base_position_guard）

「长线看势，中线看价，短线看量，超短看情绪；逃顶要快，抄底要慢」
两条件：
1. **三天不新低**：最近 3 天 low 均 ≥ 前 3 天最低 low
2. **MA5/MA10/MA30 均线粘合向上**

满足才允许打底仓；逃顶信号（3 天急跌>5% 或跌破 3 日最低 low）触发「逃顶要快」提示。

### 4.2 跨日聚合（cross_day_aggregation）

`windowDays=5 / topN=20`：近 5 日多信号跨日聚合，取前 20。

### 4.3 防守高息（defensive_dividend）

熊市/震荡时启动，寻找银行/电力/高速公路等高息防御股：
- `maxPb=1.5` / `maxDebt=70` / `maxCandidates=5`
- 高股息代理评分：低 PB + 防御板块 + 大市值近似

### 4.4 机构线索（inst_tips）

机构调研/持仓线索加分。

## 五、大A祖训（中线档）

- 高开要跑：→ **-5**（不敏感，除非估值过高）
- 买无人问津：→ +12（中线主场之一）
- 卖人声鼎沸：→ **-10**
- 低位利空=利好：→ +15
- 高位利好=利空：→ **-15**

## 六、相关文档

- [策略 · 短线](strategy-short-term.md)
- [策略 · 长线](strategy-long-term.md)
- [策略 · 实仓](strategy-real-position.md)
- [选股思路](stock-picking-methodology.md)
