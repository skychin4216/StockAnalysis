# Engine Sync Skill — smalltools 选股引擎三端同步与验证

## 触发条件

当 smalltools 的**选股逻辑/边界条件/拟合参数**发生变化后，必须执行本 Skill，将引擎同步到 exe(AutoQuant) 与 APK，并做逻辑一致性验证。

典型场景：
- `backtest_guangmo.py` / `_trend_proto.py` / `_pool_filters.py` 的形态阈值或流程被修改
- `backtest_params.json`（单一事实源）的 `select_params` / `trend_follow` / `sell_rules` 区块被重新拟合
- 新增大盘状态判定规则（`market_state` / `triple_vote`）

## 架构原则：单一事实源，三端只读同一份数据

```
smalltools(Python 拟合) ──产出──> app/src/main/assets/backtest_params.json  ← 唯一参数源
                                        │
              ┌─────────────────────────┼─────────────────────────┐
              ▼                         ▼                         ▼
         APK(Kotlin)               exe/AutoQuant(Python)       smalltools(重跑验证)
     BacktestParamsLoader         screen_smalltools.py          原生引擎对照
     读 JSON 做选股/回测           桥接 smalltools 引擎           读 JSON 验证一致
```

**核心决策**：APK 不能调用 Python（体积/性能/构建链冲突），靠**数据化**；exe 与 smalltools 同为 Python，靠**直接桥接**（`sys.path` 指向 `../smalltools`），不复制代码，避免逻辑漂移。

## 同步步骤

### Step 1: 确认 smalltools 引擎真实链路

smalltools 内部有两条选股链，都必须覆盖：

| 链路 | 入口 | 说明 |
|------|------|------|
| 粘合链 | `backtest_guangmo.analyze_snaps()` + `_pool_filters.extra_filter()` | 13 项粘合检查，中/长线 + 非牛市超短/短 |
| 趋势跟随链 | `_trend_proto.trend_follow_scan()` | 超短/短线 BULLISH 时启用（`_profit_backtest.select()` 路由） |

### Step 2: 更新参数事实源 `backtest_params.json`

- `select_params.<周期>`：粘合链阈值（convergenceThreshold/useMA60/minChangePct/minPassCount 等）
- `trend_follow.<周期>`：趋势跟随阈值（useMA60/volumeRatio/nearHighDrawdownPct/minPassCount/requireChangePct）
- `sell_rules.<周期>.by_state`：9 格卖出矩阵（nextday/streak/hold + maxHold/tp/sl/streakDays/maBreak）
- `meta.pc_hold/pc_step`：持仓上限与采样步长

### Step 3: exe 同步（AutoQuant）

编辑 `AutoQuant/screen_smalltools.py`（若新增参数字段）：
- 它已桥接 smalltools 引擎：`load_cache()` / `market_state()` / `analyze_snaps()` / `trend_follow_scan()` / `extra_filter()` / `sell_rule_for()`
- 数据源 `smalltools/_kline_cache.json`，与拟合同源
- 运行：`cd AutoQuant && python screen_smalltools.py [--asof 2026-08-20] [--period 短线] [--out data/xxx.json]`

### Step 4: APK 同步（Kotlin）

- `BacktestParamsLoader.kt`：
  - `applySelectOverrides()` 重建 pipeline 时必须**保留 trend 字段**（否则覆盖丢失）
  - `applyTrendFollowOverrides()` 从 `trend_follow` 节覆盖超短/短线
  - `sellParams()` 读 9 格卖出规则（CRASH→BEARISH 回退）
  - `maxHold()` 取周期内最大持仓缓冲
- `StockCheckPipeline.kt`：
  - `ParamsFactory.ultraShortParams()/shortTermParams()` 依次调用 `applySelectOverrides` → `applyTrendFollowOverrides`
  - `analyzeTrendSnaps()` 使用 `trendUseMA60/trendNearHighDrawdownPct/trendVolumeRatio/trendMinPassCount/trendRequireChangePct` 字段（不硬编码）
  - `FullCycleBacktestEngine` 的 `simulateStreak` 用 `params.maBreakDays`（非硬编码 5 日线）

## 验证闭环（必须全部通过）

```bash
# 1) 9 格卖出参数链路（矩阵↔JSON↔sell_rule_for↔CRASH回退）
cd smalltools && python _verify_9grid.py

# 2) exe 桥接引擎与 smalltools 原生引擎逐股一致
cd AutoQuant && python screen_smalltools.py --asof <BULLISH日>   # 与 _profit_backtest 对照
cd AutoQuant && python screen_smalltools.py --asof <OSCILLATION日> # 与 analyze_snaps+extra_filter 对照

# 3) walk_forward 状态路由注入
cd smalltools && python -c "import _walk_forward as wf; print(wf.rule_for('短线', None, 'BEARISH'))"

# 4) Kotlin 编译
cd <repo root> && gradlew.bat :app:compileDebugKotlin --console=plain
```

**一致性判据**：同一 asof 下，`screen_smalltools.py` 与 smalltools 原生引擎选出的**股票集合逐股一致**（代码+通过项数量）。

## APK 端回归验证（方案 A / 方案 B）

> **重要前提**：「🚀 一键建仓」在**交易时间**（周一~五 9:30-11:30 / 13:00-15:00）会触发**四周期真实买入订单**；**只有非交易时间**（周末/节假日/盘后）点它才走 `saveAsAiOnly=true`——只选股保存到 股票Tab→精选→AI 精选，**不落单**。以下两个方案都必须在非交易时间执行。

### 方案 A：回测逻辑回归（首选，零风险）

一键建仓与内置回测走的是**同一个** `StockCheckPipeline`（`FullCycleBacktestEngine.pipelineFor` → `ultraShortParams/shortTermParams`），因此回测足以回归引擎逻辑。

1. 周期页 → 内置**回溯/回测**，asof 取最近交易日。
2. 对比改动前后基线：
   - 超短/短线：仅受 `trend_follow` 5 参数影响（BULLISH 时走趋势跟随），关注选股数量、命中率、胜率、平均收益；
   - 中/长线：走粘合链（`analyze_snaps`），本类改动应**完全不受影响**，指标应持平；
   - 卖出侧：`sell_rules` 未动，9 格矩阵行为不变。
3. 判定：指标与基线持平（±合理波动）且无异常/报错 → **通过**。

### 方案 B：选股对齐验证（逐股一致性，最强证据）

同一引擎选出的股票，APK 与 exe 应**逐股一致**：

1. **exe 侧**：`cd AutoQuant && python screen_smalltools.py --asof <最近交易日>` 生成选股报告。
2. **APK 侧**：非交易时间点「🚀 一键建仓」→ 四周期选股保存 → 股票Tab → 精选 → **AI 精选**。
3. **对比**：AI 精选中的 超短/短线/中线/长线 股票 vs exe 报告，按周期逐股比对（代码 + 通过项数量）。
4. 判定：与下方「已知对齐基准」表格一致（如 08-19 BULLISH 超短 5 只/短线 6 只；08-20 震荡中短线 0 只/长线 1 只）→ **通过**，证明 APK 与 smalltools 引擎零漂移。

**APK/exe 一致性根源**：两边共用 `app/src/main/assets/backtest_params.json`（含 `trend_follow` 节），APK 经 `BacktestParamsLoader` 读取、exe 经 `_tf_cfg()` 读取，同一份参数 + 同一套判断逻辑。

## 已知对齐基准（2026-08 验证通过）

| asof | 大盘 | 超短 | 短线 | 中线 | 长线 |
|------|------|------|------|------|------|
| 2026-08-19 | BULLISH | 5 只（趋势跟随） | 6 只（趋势跟随） | 0 | 0 |
| 2026-08-20 | OSCILLATION | 0 | 0 | 0 | 1 只（华能水电） |

## 文件清单

| 文件 | 作用 |
|------|------|
| `app/src/main/assets/backtest_params.json` | ★ 单一事实源（select/trend/sell/meta） |
| `smalltools/_full_cycle_backtest.py` | 引擎桥接源（load_cache/market_state/sell_rule_for） |
| `smalltools/_trend_proto.py` | 趋势跟随引擎（`_tf_cfg()` 读 JSON） |
| `smalltools/_pool_filters.py` | 硬过滤（ST/主板/板块代理/粘合持续/短线关键项） |
| `AutoQuant/screen_smalltools.py` | exe 选股入口（桥接引擎） |
| `app/.../backtest/BacktestParamsLoader.kt` | APK 参数加载（applySelectOverrides/applyTrendFollowOverrides/sellParams） |
| `app/.../pipelines/StockCheckPipeline.kt` | APK 选股管线（analyzeTrendSnaps 读字段） |
| `smalltools/_verify_9grid.py` | 9 格参数链路验证工具 |
| `AutoQuant/cloudsync/` | exe 云同步包（COS 闭环，原 `AutoQuant/smalltools`，2026-08-23 重命名）。**与选股引擎无关，勿删勿混淆** |

> **两套 smalltools 命名区分**（避免混淆）：
> - 根 `smalltools/` = **选股引擎**（无 `__init__.py`，顶层模块，经 `sys.path` 桥接）——同步对象。
> - `AutoQuant/cloudsync/` = **exe 云同步包**（腾讯云 COS：下载手机数据→自动拟合→App 参数回流），原 `AutoQuant/smalltools`，已重命名。仅 exe 打包使用（`AutoQuant-GUI.spec` hiddenimports 的 `cloudsync.*`），不参与选股。删除会致 exe 启动时云同步后台崩溃。

---

## ETF 低位低吸（etf_dip）—— XML DAG 单一源（2026-09-07）

与三周期同构：规则/参数**只写一份 XML**，APK 与 Python 引擎读同一文件执行。

| 文件 | 作用 |
|------|------|
| `app/src/main/assets/usecases/etf_dip_usecase.xml` | ★ 入口 usecase（与三周期同格式） |
| `app/src/main/assets/usecases/etf_dip_pipeline.xml` | ★ 三阶段 DAG：门控 `etf_gate` → 信号 `etf_dip_signal` → 离场 `etf_exit_policy`（参数全在此） |
| `app/.../topology/nodes/EtfDipNodes.kt` | APK 执行端（与 Python `_etf_*` 模块同构） |
| `AutoQuant/usecase_pipeline.py` | Python 引擎端（`etf_gate/etf_dip_signal/etf_exit_policy` 注册 + `run(with_stages=True)`） |
| `smalltools/_etf_publish.py` | PC 发布/推送入口：XML 执行 → `data/_etf_live_picks.json` → adb 推 `_etf_cache.json` 到手机 |
| `smalltools/_etf_buy.py` | 规则出处与行情抓取（`ensure_data`），v0.3 口径未变 |

**改动纪律**：以后调 ETF 低吸参数/池，只改 `etf_dip_pipeline.xml`（双端零代码同步）。`_etf_buy.py` 内的 `--live` 不再作为发布口径（保留数据抓取），发布走 `_etf_publish.py`（盘段守护 15:12 自动跑）。

**APK 本地化**：`EtfDipFragment.refresh()` 优先 `UseCaseLoader.run("etf_dip")`（读手机 `etf_cache.json`：外部 files 优先、内部 files 兜底）；本地无缓存才回退 PC 桥 `/etf_live`。

**验证**：`python smalltools/_etf_publish.py`（无设备时仅发布名单，已与旧 `--live` 输出逐字段一致验证）；Kotlin `compileDebugKotlin` 通过。
