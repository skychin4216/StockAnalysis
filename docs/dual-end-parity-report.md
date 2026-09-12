# exe 与 APK 功能同步：差异盘点 + 本轮同步结果

> 判定方式：**用仓库自带的节点执行审计工具实测**，不靠肉眼猜。
> 工具：`smalltools/_nodes_exec_report.py`（22 个 usecase 逐个跑一遍 XML DAG，记录每个节点是否真正执行）
> 复现：`cd smalltools && python _nodes_exec_report.py --selfcheck`

---

## 0. 结论摘要

| 指标 | 本轮改动前 | 本轮改动后 |
|---|---|---|
| 被 bypass 的节点实例 | 128 | **120** |
| 其中「未实现 module」（真缺口） | 124 | **116** |
| 自检退出码 | 0 | **0**（无回归） |
| 新修复的节点 | — | **8 处** |

本轮实际同步了 **2 个 module / 8 处节点实例**：
- `base_position_guard`（打底仓守门）→ mid / long / direction / complete_closed_loop
- `direction_label`（个股方向标签）→ mid / long / direction / complete_closed_loop

---

## 1. 本轮已同步（已实测生效）

| module | APK 实现 | PC 侧新增实现 | 生效管线 |
|---|---|---|---|
| `base_position_guard` 打底仓守门 | `strategy/topology/nodes/BasePositionGuardNode.kt` + `strategy/analysis/BasePositionAnalyzer.kt` + `StockCheckHelpers.kt`(ABOVE_PRIOR_MIN) | `usecase_pipeline.py::_base_position_guard` + `_base_position_analyze` | mid、long、direction、complete_closed_loop |
| `direction_label` 个股方向标签 | `strategy/topology/nodes/PeriodV23Nodes.kt` + `strategy/data/IndividualDirection.kt`(DirectionAnalyzer) | `usecase_pipeline.py::_direction_label` + `_direction_of` | mid、long、direction、complete_closed_loop |

**同步口径（两端口径一致，逐字对齐 Kotlin）**
- 打底仓：`三天不新低` + `MA5/10/30 离散率 <2% 且 MA5 上翘` → ready；买入(score>50)未就绪 −20（LONG −30）、粘合向上 +12（LONG +20）、卖出且逃顶急 +12（LONG +18）
- 方向标签：判定优先级 `突破 > 蓄势 > 上升 > 下降 > 震荡`；`exclude=DOWNTREND,OSCILLATION` 时扣 `penalty=25`（只扣分不硬删，与 APK 一致）；标签同时写入 stage 键 `direction_labels` 供下游复用
- 接线修正：`ancestral_rules` 改为优先读 `n_base_guard`（mid/long/direction 链为 `n_strict → n_base_guard → n_ancestral`；无该节点的管线自动回落 `n_strict`）

---

## 2. 待同步清单：APK 已有、PC(Python 引擎) 缺失（16 个 module）

> 这些 module 在 APK 有 Kotlin 实现，但 PC 侧 `usecase_pipeline.py` 没注册 → 跑同一份 XML 时该节点被标注「未实现 module」并跳过，
> 导致 exe 与 APK 的选股/交易结果不一致。

| # | module | APK 实现文件 | 涉及的 usecase | 影响 |
|---|---|---|---|---|
| 1 | `sector_strength` | `MarketPublicPipelineNodes.kt` | common、short、mid、long、ultra_short、direction、stock_deep_analysis、complete_closed_loop | **面最广**（8 条管线），板块强弱加分全部失效 |
| 2 | `style_rotation` | `MarketPublicPipelineNodes.kt` | 同上 8 条 | 风格轮动判断失效 |
| 3 | `sector_stock_pool` | `HardcodeCompatNodes.kt` | mid、long、direction、complete_closed_loop | 板块精选池，直接影响候选池构成 |
| 4 | `cross_day_aggregation` | `QuantTradingPipeline.kt` | mid、direction、complete_closed_loop | 跨日聚合 |
| 5 | `defensive_dividend` | `DefensiveDividendNode.kt`（+`PipelineNodes.kt`） | mid、long、direction、complete_closed_loop | 防守高息候选注入 |
| 6 | `seasonality_boost` | `PeriodV23Nodes.kt` | mid、long、direction、complete_closed_loop | 行业季节日历加分 |
| 7 | `leader_track` | `PeriodV23Nodes.kt` | mid、long、direction、complete_closed_loop | 龙头识别与加分 |
| 8 | `rotation_penalty` | `PipelineNodes.kt`、`QuantTradingPipeline.kt`、`TradeModels.kt` | short、mid、direction、complete_closed_loop | 板块轮动惩罚 |
| 9 | `t1_auto_sell` | `HardcodeCompatNodes.kt` | ultra_short、complete_closed_loop | T+1 自动卖出 |
| 10 | `t_holdings_load` | `TTradePipelineNodes.kt` | t_trade | 做 T 持仓加载 |
| 11 | `t_inst_intent` | `TTradePipelineNodes.kt` | t_trade | 机构意图 |
| 12 | `t_recommend_save` | `TTradePipelineNodes.kt` | t_trade | 推荐落库 |
| 13 | `t_signal_synthesize` | `TTradePipelineNodes.kt` | t_trade | 信号合成 |
| 14 | `t_trade_import` | `TTradePipelineNodes.kt` | t_trade | 交易导入 |
| 15 | `sector_leader_analysis` | ⚠️ **仅 `NodeRegistry.kt` 注册，未见独立实现** | real_holding、t_trade | **两端都需补** |
| 16 | `t_trade_eval` | ⚠️ **仅 `NodeRegistry.kt` 注册，未见独立实现** | real_holding | **两端都需补** |

### 建议推进顺序
- **P0（影响面最大）**：#1 `sector_strength`、#2 `style_rotation`（覆盖 8 条管线）
- **P1（中线主链完整性）**：#3 `sector_stock_pool`、#6 `seasonality_boost`、#7 `leader_track`、#8 `rotation_penalty`、#4 `cross_day_aggregation`、#5 `defensive_dividend`
- **P2（超短/做T/实盘）**：#9 `t1_auto_sell`、#10~#14 `t_*`、#15/#16（两端都缺）

> 移植方法沿用本轮已验证的流程：读 Kotlin 源 → 在 `usecase_pipeline.py` 注册同名 module → `ctx.get(上游节点 id) / ctx.stage(node.id, out)` → 跑 `--selfcheck` 确认 `fixed` 增加且 `code=0`。

---

## 3. 另一类差异：代码都有、PC 缺数据 → 自动降级为透传

审计里这类提示是 `xxx 无回测数据，已降级为透传`（**不是代码缺口**，是数据源差异）：

`bg_manager`、`market_sector_leaders`、`intraday_analysis`、`inst_tips`、`news_strength`、`sector_relative_pe`、`heat_score`、`multi_period_hot`、`candle_pattern`、`financial_health`、`news_guard`、`crosstab_publish` 等。

> 处理方向：给 PC 回测库补齐对应数据源（资金流/新闻/财务/行业指数），口径才能与 APK 完全一致；否则这些节点在 exe 上永远等于"没跑"。

---

## 4. 复现与验收

```powershell
cd smalltools
python _nodes_exec_report.py --selfcheck     # 退出码 0；末尾打印 bypass / fixed / new 统计
```

验收标准：
- `code=0`（无 usecase 执行报错）
- `new=0`（没有新增异常）
- 目标 module 出现在 `FIXED:` 列表、且不再出现在 `[bypass]` 列表中

本轮实测输出（节选）：
```
SELFCHECK code=0 bypass=120 new=0 fixed=8 silent=99 error=0 asof=2026-09-11
  FIXED: mid::n_base_guard        FIXED: mid::n_direction
  FIXED: long::n_base_guard       FIXED: long::n_direction
  FIXED: direction::n_base_guard  FIXED: direction::n_direction
  FIXED: complete_closed_loop::n_base_guard
  FIXED: complete_closed_loop::n_direction
```
