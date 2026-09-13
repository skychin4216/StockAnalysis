# exe 与 APK 功能同步：差异盘点 + 本轮同步结果

> 判定方式：**用仓库自带的节点执行审计工具实测**，不靠肉眼猜。
> 工具：`smalltools/_nodes_exec_report.py`（22 个 usecase 逐个跑一遍 XML DAG，记录每个节点是否真正执行）
> 复现：`cd smalltools && python _nodes_exec_report.py --selfcheck`

---

## 0. 结论摘要

| 指标 | 本轮改动前 | 本轮改动后 |
|---|---|---|
| 被 bypass 的节点实例 | 128 | **106**（↓22） |
| 自检退出码 | 0 | **0**（无回归） |
| 新修复的节点 | — | **22 处** |
| 落地的 module | — | **4 个** |

本轮同步的 4 个 module：
| module | 生效管线 | 处数 |
|---|---|---|
| `base_position_guard` 打底仓守门 | mid / long / direction / complete_closed_loop | 4 |
| `direction_label` 个股方向标签 | mid / long / direction / complete_closed_loop | 4 |
| `sector_strength` 板块强弱监测 | common / short / mid / long / ultra_short / direction / stock_deep_analysis | 7 |
| `style_rotation` 风格轮动判断 | 同上 7 条 | 7 |

---

## 1. 本轮已同步（已实测生效）

| module | APK 实现 | PC 侧新增实现 | 同步口径 |
|---|---|---|---|
| `base_position_guard` | `BasePositionGuardNode.kt` + `analysis/BasePositionAnalyzer.kt` + `StockCheckHelpers.kt`(ABOVE_PRIOR_MIN) | `usecase_pipeline.py::_base_position_guard` + `_base_position_analyze` | 三天不新低 + MA5/10/30 离散率 <2% 且 MA5 上翘 → ready；买入(score>50) 未就绪 −20（LONG −30）、粘合向上 +12（LONG +20）、卖出且逃顶急 +12（LONG +18） |
| `direction_label` | `PeriodV23Nodes.kt` + `data/IndividualDirection.kt`(DirectionAnalyzer) | `::_direction_label` + `_direction_of` | 判定优先级 突破 > 蓄势 > 上升 > 下降 > 震荡；`exclude=DOWNTREND,OSCILLATION` 只扣 `penalty=25` 不硬删；标签写入 stage `direction_labels` |
| `sector_strength` | `MarketPublicPipelineNodes.kt::SectorStrengthNode` | `::_sector_strength` + `_sector_members` + `_sector_avg_ret` | 排序逐条一致：热门天数 ↓ → 综合分 ↓ → 日均涨跌 ↓ → 主力净流入 ↓ |
| `style_rotation` | `MarketPublicPipelineNodes.kt::StyleRotationNode` | `::_style_rotation` | 大盘环境分支 + 强势板块风格倾向 + 季节提示；大盘缺失时走「均衡震荡」兜底（与 APK 的 `market == null` 分支一致） |

**接线修正**：`ancestral_rules` 改为优先读 `n_base_guard`（mid/long/direction 链为 `n_strict → n_base_guard → n_ancestral`；无该节点的管线自动回落 `n_strict`）。

### 1.1 跨端口径折中（必须知道）
`sector_strength` 在 APK 读 **`sector_daily_record` 表**（含板块热度标签 `isHot=S/A` 与主力资金流）；
PC 无该表，改为用 **`hot_sector_config.HOT_SECTOR_CONFIG` 的板块成分股 K 线重算**——
这与本仓库既有的 `sector_ambush` 完全同源（见 `usecase_pipeline.py` 2678 行注释）。

差异项：
- **热门天数**：APK = 板块热度为 S/A 的天数；PC = 板块成分股平均「当日上涨」天数（量纲都落在 0~lookback）
- **综合分**：APK = DB 里的 `compositeScore`；PC = 动量口径 `0.5*r10 + 0.3*r20 + 0.2*r5`（与 sector_ambush 同源）
- **主力净流入**：PC 无资金流数据，恒为 `0.0`

> 排序**规则**逐条一致，**输入口径**因数据源不同而有差异。若要完全一致，需给 PC 补板块热度表与资金流数据（见第 3 节）。

---

## 2. 待同步清单：APK 已有、PC(Python 引擎) 缺失（14 个 module）

> 这些 module 在 APK 有 Kotlin 实现，但 PC 侧 `usecase_pipeline.py` 没注册 → 跑同一份 XML 时该节点被标注「未实现 module」并跳过，
> 导致 exe 与 APK 的选股/交易结果不一致。

| # | module | APK 实现文件 | 涉及的 usecase | 影响 |
|---|---|---|---|---|
| 1 | `sector_stock_pool` | `HardcodeCompatNodes.kt` | mid、long、direction、complete_closed_loop | 板块精选池，直接影响候选池构成 |
| 2 | `seasonality_boost` | `PeriodV23Nodes.kt` | mid、long、direction、complete_closed_loop | 行业季节日历加分 |
| 3 | `leader_track` | `PeriodV23Nodes.kt` | mid、long、direction、complete_closed_loop | 龙头识别与加分 |
| 4 | `rotation_penalty` | `PipelineNodes.kt`、`QuantTradingPipeline.kt`、`TradeModels.kt` | short、mid、direction、complete_closed_loop | 板块轮动惩罚 |
| 5 | `cross_day_aggregation` | `QuantTradingPipeline.kt` | mid、direction、complete_closed_loop | 跨日聚合 |
| 6 | `defensive_dividend` | `DefensiveDividendNode.kt`（+`PipelineNodes.kt`） | mid、long、direction、complete_closed_loop | 防守高息候选注入 |
| 7 | `t1_auto_sell` | `HardcodeCompatNodes.kt` | ultra_short、complete_closed_loop | T+1 自动卖出 |
| 8 | `t_holdings_load` | `TTradePipelineNodes.kt` | t_trade | 做 T 持仓加载 |
| 9 | `t_inst_intent` | `TTradePipelineNodes.kt` | t_trade | 机构意图 |
| 10 | `t_recommend_save` | `TTradePipelineNodes.kt` | t_trade | 推荐落库 |
| 11 | `t_signal_synthesize` | `TTradePipelineNodes.kt` | t_trade | 信号合成 |
| 12 | `t_trade_import` | `TTradePipelineNodes.kt` | t_trade | 交易导入 |
| 13 | `sector_leader_analysis` | ⚠️ **仅 `NodeRegistry.kt` 注册，未见独立实现** | real_holding、t_trade | **两端都需补** |
| 14 | `t_trade_eval` | ⚠️ **仅 `NodeRegistry.kt` 注册，未见独立实现** | real_holding | **两端都需补** |

### 建议推进顺序
- **P1（中线主链完整性）**：#1 `sector_stock_pool`、#2 `seasonality_boost`、#3 `leader_track`、#4 `rotation_penalty`、#5 `cross_day_aggregation`、#6 `defensive_dividend`
- **P2（超短/做T/实盘）**：#7 `t1_auto_sell`、#8~#12 `t_*`、#13/#14（两端都缺）

> 移植方法沿用本轮已验证的流程：读 Kotlin 源 → 在 `usecase_pipeline.py` 用 `@register("<module>")` 注册同名 module →
> 用 `ctx.get(上游节点 id)` 取输入、`ctx.stage(node.id, out)` 输出 → 跑 `--selfcheck` 确认 `fixed` 增加且 `code=0`、`new=0`。

---

## 3. 另一类差异：代码都有、PC 缺数据 → 自动降级为透传

审计里这类提示是 `xxx 无回测数据，已降级为透传`（**不是代码缺口**，是数据源差异）：

`bg_manager`、`market_sector_leaders`、`intraday_analysis`、`inst_tips`、`news_strength`、`sector_relative_pe`、`heat_score`、`multi_period_hot`、`candle_pattern`、`financial_health`、`news_guard`、`crosstab_publish` 等。

> 处理方向：给 PC 回测库补齐对应数据源（**板块热度表 `sector_daily_record`**、资金流、新闻、财务、行业指数），
> 口径才能与 APK 完全一致；否则这些节点在 exe 上永远等于"没跑"。

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

本轮实测输出：
```
SELFCHECK code=0 bypass=106 new=0 fixed=22 silent=99 error=0 asof=2026-09-11
  FIXED: {mid,long,direction,complete_closed_loop}::n_base_guard
  FIXED: {mid,long,direction,complete_closed_loop}::n_direction
  FIXED: {common,short,mid,long,ultra_short,direction,stock_deep_analysis}::n_sector_strength
  FIXED: {common,short,mid,long,ultra_short,direction,stock_deep_analysis}::n_style_rotation
```

---

## 5. 2026-09-13 结论：P2「做T / 实仓」链路的定性

P1（选股主线 6 个 module：`seasonality_boost` / `leader_track` / `sector_stock_pool` /
`cross_day_aggregation` / `rotation_penalty` / `defensive_dividend`）已全部同构移植，
`未实现 module` 归零。

原 P2 列表的 8 个 module 经逐行复核，**全部是 Android 运行时节点**：

| module | 缺什么（APK 侧数据源） |
|---|---|
| `t_holdings_load` | Room `realPositionDao` + `strategyTradeOrderDao` + `dailySnapshotDao` |
| `t_inst_intent` | 上者产物（真实持仓） + 日内快照 |
| `t_signal_synthesize` | 上者 + 实时行情 + 分时 + K线形态 |
| `t_recommend_save` | Room `t_trade_recommendations` 表 + 微信推送 |
| `t_trade_eval` | Room `realPositionDao` + `TTradeEngine` 实时快照 |
| `sector_leader_analysis` | `SectorSignalStore`（盘中每 10 分钟刷新，进程内内存表） |
| `t1_auto_sell` | `strategyTradeOrderDao` + 实时价格 |
| `t_trade_import` | 无（纯逻辑：交易日判定） |

实测证据（PC 侧手机镜像 `smalltools/_records/cloud/phone_20260911_005705/data.json`）：
`real_positions = 0`、`strategy_trade_orders = 0`、`t_trade_records = 0`、
`t_trade_recommendations = 0` —— PC 端**没有任何持仓/成交/做T数据源**，
再完整的移植也只是空跑，属于「代码缺失 vs 数据源缺失」里的后者。

处理方式：
- `t_trade_import`（唯一的纯逻辑节点）已在 `usecase_pipeline.py` 真实现；
- 其余 7 个按仓库既有约定进入 `_passthrough` 的**声明式降级**，并在 `_PASSTHROUGH_WHY`
  里写清每个 module 缺的是哪张表 / 哪个运行时数据源 —— 审计报告从此显示
  「降级为透传（数据源缺失）」而不是「未实现 module（代码缺失）」。

本轮实测输出：
```
SELFCHECK code=0 bypass=82 new=0 fixed=46 silent=99 error=0 asof=2026-09-11
bypass_reasons: {'降级为透传': 82}     # 「未实现 module」= 0
```
