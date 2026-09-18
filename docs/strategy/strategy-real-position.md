# 策略 · 实仓（持仓管理 + 做T/反T）

> 最后更新：2026-08-13 | 代码位置：`strategy/trade/`、`RealHoldingQuantFragment.kt`、`AppBackgroundRunner.monitorTTradeOpportunities`

---

## 一、模块构成

```
strategy/trade/
├── RealPositionModels.kt      — 持仓模型（RealPosition / RealPositionSummary）
├── TTradeModels.kt            — 做T模型（TTradeType / TTradeRecommendation / TTradeRecord / TTradeSuccessRate）
├── TTradeEngine.kt            — 做T引擎（信号生成/执行/结算/成功率）
├── TradeDecisionHelper.kt     — 交易决策辅助
├── TradeModels.kt             — 交易模型（TradeOrder 等）
├── AutoSellEngine.kt          — 自动卖出引擎
├── HotSectorStockPool.kt      — 热门板块股票池
└── StrategyFittingEngine.kt   — 策略拟合
```

## 二、持仓模型（real_position 表）

| 字段 | 说明 |
|------|------|
| code / name | 股票代码/名称 |
| position / available | 总持仓 / 可卖（底仓） |
| costPrice / currentPrice | 成本 / 现价 |
| frozen | 冻结（做T占用） |
| swapCount / lastSwapDate | 腾龙换鸟次数 |
| openDate / closeDate | 开仓/平仓日期 |
| status | OPEN / CLOSED |
| orderType | 来源（MidTermQuant / LongTermQuant / 手动） |
| latestReportId | 关联最新报告 |

`RealPositionSummary` 聚合：总市值 / 总盈亏 / 当日盈亏 / 仓位使用率。

## 三、做T/反T 四腿配对

| TTradeType | 含义 | 配对 |
|-----------|------|------|
| T_BUY | 做T买入（先买后卖） | 与 T_SELL 配对 |
| T_SELL | 做T卖出（当日卖出底仓，回落再买回） | 与 T_BUY 配对 |
| RT_SELL | 反T卖出（先卖后买） | 与 RT_BUY 配对 |
| RT_BUY | 反T买入（回落买回） | 与 RT_SELL 配对 |

开仓腿 vs 配对腿：T_BUY 的配对腿是 T_SELL，RT_BUY 的配对腿是 RT_SELL，反之亦然。配对成功后另一腿自动作废（避免孤儿记录）。

## 四、做T信号生成（generateSignals）

1. **支撑/阻力位**：
   - 支撑 = max(最高价, MA5×0.98, MA10×0.97)（近期最高价线）
   - 阻力 = 前期高点（rolling high）
2. **贴近触发**：现价距支撑/阻力 2% 以内
3. **期望利润** > 0.5%
4. **数量**：`max(底仓×40%, 100)` 取整至 100 倍数
5. **辅助确认**：RSI（超卖加分）、量比（缩量回踩加分）、K线形态、趋势方向

### 置信度评分公式（100 分制）

```
50 分基准
+ 机构意图：建仓吸货 +20 / 震仓洗盘 -15 / 拉升中 -30 / 出货判定（大减）
± K线形态：利多 +15 / 利空 -10
± 外盘：利好 +10 / 利空 -5
+ 新闻：利空 -20 / 利好 +5
± 技术指标：超卖 +10 / 超买 -10
± 日内冰点(pricePosition≤0.2) +15 / 沸点(≥0.8) -10（买点）；卖点反向
- 大盘弱 -10
× 时段调整（tBuyScoreAdj / rtSellScoreAdj / confidenceScale）
```

- 做T买入要求置信度 ≥ 70；反T卖出要求置信度 ≥ 60。

## 五、做T 执行与结算

### 5.1 执行（executeTTrade）

- 卖出腿：先生成卖出建议（可自动执行），再生成配对买回建议
- 买入腿：生成买入建议（自动执行）
- 持仓冻结逻辑：卖出底仓 → `frozen -= amount`；买入 → `available` 增加

### 5.2 配对（matchTTradePairs）

按方向（做T/反T）与股票配对，配对成功后标记 pairId，另一腿 `status=CANCELLED`。

### 5.3 收盘结算（markDayEnd / closeTTrade）

- 15:00 后自动结算：已执行交易的买卖价差计入 `profit`
- 配对目标触及 / 收盘平仓：更新 `isTargetHit`、`isStopHit`、`executedProfit`

## 六、成功率统计（TTradeSuccessRate / getSuccessRate）

按**方向**统计：

| 方向 | 统计字段 |
|------|---------|
| 做T (t) | totalTrades / successTrades |
| 反T (rt) | totalTrades / successTrades |

- `isExecutedProfitable`：已执行交易执行价差 > 0 判定为盈利
- 通知中展示「近 7 日成功率」`getSuccessRate(7)`

## 七、持仓风控（HoldingGuardNode）

硬止损 / 最大回撤止损 / 阶梯止盈 / 时间强制平仓 / 趋势反转 / RSI超买 / 放量滞涨 / 板块走弱 —— 详见 [做T系统文档](../topology/t-trade-system.md) 与 DAG 风控节点。

## 八、后台监控

`AppBackgroundRunner.monitorTTradeOpportunities`：
- 每 5 分钟运行，覆盖 4 个周期 + RealPosition
- 15:00-15:05 收盘结算
- 发送通知（含近 7 日成功率）
- `autoExecuteIfEligible()`：置信度 ≥ `auto_execute_threshold`（默认 0.7）且开关开启时自动执行

## 九、相关文档

- [做T系统（全流程）](../topology/t-trade-system.md)
- [做T时段策略](../topology/t-trade-time-slots.md)
- [后台服务架构](../background/background-services.md)
