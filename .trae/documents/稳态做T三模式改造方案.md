# 稳态做T页 → 三模式改造（单票建议 / 持仓池 / 每小时简报）

## Context（为什么做）

`SsaT0Fragment`（「🔄 三层 SSA 稳态做T」）目前只有单票回测一种用法。用户希望它像
`SsaHybridFragment`（混合策略建议页）那样，顶部有三个 mode：**单票建议 / 持仓池 / 每小时简报**，
把"看单票"、"盯自己的持仓"、"扫一遍池子拿每小时操作清单"三件事分开。T0 是小时线策略，
故第三个 mode 是**每小时简报**（不是每日）。

复用 Hybrid 页已有的交互范式（3 个 mode 按钮 + `switchMode` + 池卡片/建议徽章），
但 Hybrid 的 `SsaRecoPipeline`/`SsaPoolStore` 与 `SsaHybridEngine` 深度绑定，**不可直接复用**，
需为 T0 新建一套轻量持久化 + 建议派生。

## 关键事实（已核对）

- `SsaHybridFragment.kt:L164-186` mode 按钮、`L424-448` `switchMode`、`L450-455` `advWord`。
- `SsaPoolStore.kt`：`PREF="ssa_hybrid_pool"`、`SNAP_FILE="ssa_hybrid_pool_snapshot.json"`（Hybrid 专用）。
- `SsaRecoPipeline` 跑 688 核心池（`HistoricalDataFetcher.getCoreStockPool(): Set<String>`），**日线**、增量状态机。
- `SsaT0Engine.run(hourBars, dayBars, params): Result`；`Result` 含 `regime/markers/trades/equity/h.d/nUp/nDn/eUp/eDn` 等；**无 next-advice helper**。
- `SsaT0Engine.simulate()`（L386 起）内部已维护 `cash/sh`、t 决策 t+1 开盘成交；`SimOut`（L337-340）**未暴露最终仓位**。
- 时线数据：`IntradayKlineFetcher(ctx).fetchHourlyHistory(code, n)`（默认 760，新浪 scale=60 主、东财 klt=60 兜底）。单票现取 800 根。
- 单票回测耗时约 10~40s（800 根 × 三层滚动 SSA）；**688 核心池按小时线全扫不可行** → 简报默认只跑"持仓池"。

## 文件清单

| 操作 | 路径 |
|---|---|
| 改 | `app/src/main/java/com/chin/stockanalysis/strategy/ssa/SsaT0Engine.kt` |
| 新建 | `app/src/main/java/com/chin/stockanalysis/strategy/ssa/SsaT0PoolStore.kt` |
| 改 | `app/src/main/java/com/chin/stockanalysis/ui/SsaT0Fragment.kt` |

`SsaAnalysisFragment.kt` 不改（外层已随机切换 T0/Hybrid 子 Fragment）。

---

## 1. 引擎改动（SsaT0Engine.kt）

1. **暴露最终仓位**：`SimOut` 增 `finalCash: Double, finalShares: Long`；在 `simulate` 返回处（末根估值之后）填 `cash/sh`。
   `Result` 增 `val finalHoldingPct: Double`（=`sh*close(last)/equity(last)*100`）。`run()` 目前用命名参数构造 `Result`，改动安全。
2. **新增 Advice**：
   ```kotlin
   data class Advice(val index: Int, val time: String, val state: Int,
                     val action: Int, val reason: String, val close: Double, val holdPct: Double)
   fun adviceOf(r: Result): Advice
   ```
   动作常量与 Hybrid 对齐：`BUY=0 / SELL=1 / HOLD_POS=2 / HOLD_EMPTY=3`。
   `adviceOf` 对末根复现 `simulate` 的 `when(reg[last])` 决策（输入 `close/mid/nUp/nDn/eUp/eDn[last]` + `finalHoldingPct`）：
   - **UP(1)**：`hold<corePct`→BUY「UP建仓/回补」；`close>eUp`→SELL「UP超上限卖T」；`close<eDn`→BUY「UP破下限买T」；否则 HOLD_POS。
   - **WINDOW(3)**：`hold>0 && close<stopLine`→SELL；`hold==0 && 上穿中位`→BUY；`hold>0 && close>=nUp`→SELL；`hold>0`→HOLD_POS；否则 HOLD_EMPTY。
   - **FLAT(2)**：`hold>0`→SELL「FLAT峰顶回落·清仓」；`hold==0 && close<eDn`→BUY「FLAT破极限·买T」；否则 HOLD_EMPTY。
   - **暖机(0)** → HOLD_EMPTY「暖机中·观望」。
   - 上穿口径：`close[last-1] < mid[last-1] && close[last] >= mid[last]`。
   - **支持边界**：完整支持默认三态机（含 `winHalf`/`reduceMode 0/1/2`）；对 `useCycleRule / useMidCross / reduceMode==3` 支线返回 HOLD_EMPTY + reason「非默认规则，请以回测末段成交为准」。池/简报默认锁定三态机参数，天然在支持区间内。

## 2. 新建 SsaT0PoolStore.kt（镜像 SsaPoolStore）

- 常量：`PREF="ssa_t0_pool"`、`KEY_MEMBERS="members"`、`SNAP_FILE="ssa_t0_pool_snapshot.json"`（与 Hybrid 完全隔离，互不覆盖）。
- `data class PoolMember(code,name,addedDate, wl,wm,wn, dl,dm,dn, hl,hm,hn, cp,tp,slip, cyRule:Boolean, winHalf:Boolean)`
  - `fun params(): Params` 完全复刻 `SsaT0Fragment.readParams()` 构造顺序（`cyRule`→`useCycleRule=true,cyLayer="D",cyConfirm=2`；`winHalf`→`winPosPct=50.0`），其余字段用默认。
- API 同 `SsaPoolStore`：`load(ctx)`、`add(ctx,m):Boolean`、`remove(ctx,code)`、`updateParams(ctx,code,Params)`、`saveSnapshot/loadSnapshot`，显式字段 JSON 序列化。
- `data class T0Snapshot(code,name,updated, barLabel, close, state, action, reason, holdPct, ret, mdd, winRate, trades)`
  - 绩效取自 `full`：`ret=full.totalReturnPct`、`mdd=full.maxDrawdownPct`、`winRate=full.winRatePct`、`trades=full.tradeCount`。

## 3. SsaT0Fragment 结构改造

- 新增字段：`inputCard`（装现有输入/参数/勾选框，便于整块隐藏）、`modeRow` + `modeBtnSingle/modeBtnPool/modeBtnBrief`、`poolTools/poolBox/lastUpdTv`、`briefTools/briefBox/briefUpdTv/briefFilterRow`、状态 `inPoolMode/inBriefMode/briefFilter=-1/busy=false`。
- 布局顺序（对齐 Hybrid `L139-192`）：标题+说明 → **modeRow（🔍单票建议 / 📋持仓池 / ⏱每小时简报，默认单票高亮 TEAL，其余 #B0BEC5）** → `inputCard` → `poolTools`(GONE)/`lastUpdTv`(GONE)/`poolBox`(GONE) → `briefTools`(GONE)/`briefUpdTv`(GONE)/`briefFilterRow`(GONE)/`briefBox`(GONE) → `statusTv`/`resultBox`/底部风险 hint。
- `switchMode(m)`（0=单票 1=池 2=简报）：镜像 Hybrid `L424-448` 切 visibility + 按钮底色；`m==1→renderPool()`、`m==2→renderBrief()`。
- **单票模式**：在 `render()` 最前插一张醒目卡「📌 当前状态 & 下一根操作建议」——建议徽章（BUY 红/SELL 绿/HOLD_POS 蓝/HOLD_EMPTY 灰）+ 状态 chip（UP 红/WINDOW 绿/FLAT 灰）+ `statGrid(截至/收盘/持仓%/中线/正常上下轨/极限上下轨)` + `hint(依据 ${reason})`。数据源 `SsaT0Engine.adviceOf(r)`。其余 7 张卡不动。
- **持仓池行** `poolRow(m,snap)`：名称(code)+建议徽章 / 截至·收盘·状态·建议·理由 / 回测收益·回撤·胜率·笔数·当前持仓% / 按钮 `📊详情`（切单票 + 预填参数 + 跑）、`🔄更新`、`🗑移除`。需新增 `prefillParams(Params)` 反填 `cfgInputs/corePctEt/tPctEt/slipEt/cycleRuleCb/winHalfCb`。
- **简报行** `briefRow(rank,snap)`：`rank. 名称(code)` + 建议徽章 + 状态 chip / `理由 · 收盘 · 持仓% · 收益`。排序：`BUY<SELL<HOLD_POS<HOLD_EMPTY`，同档按 `ret` 降序；顶部 chip「全部/买入N/卖出N/持有N/观望N」。

## 4. 异步/线程

- 全部 `lifecycleScope.launch`；网络/DB 走 `Dispatchers.IO`，`SsaT0Engine.run`+`adviceOf` 走 `Dispatchers.Default`。
- `updateOne(member, nHourly): T0Snapshot?`：IO 拉日线+时线 → Default 跑引擎+adviceOf → 返回快照。
- `onPoolAdd`：`AlertDialog` 输入 → `resolveCode` → 拉数据 → 用**当前 UI `readParams()`** 锁定入库 → 立即 `updateOne` 生成首份快照 → `renderPool()`。
- `onPoolUpdate` / `onBriefRefresh`：读 members **串行**跑 `updateOne`，逐票 `status("⏳ ${i+1}/${size}")` 并覆盖快照，完成后 `renderPool()`/`renderBrief()`。
- `busy` 保护 + 对应按钮禁用，防并发重入。**简报仅手动刷新**，不加后台定时器（省电）。

## 5. 建议默认值（本方案采用，可在批准时改）

- 池/简报的小时线**截到最近 ~500 根**（单票仍 800），把 N 票串行耗时压到每票约 6~25s。
- 池成员**锁定"加入时 UI 参数"**（用户可调）。
- 单票回测行为**不变**。

## 6. 验证

- 编译：`.\gradlew :app:compileDebugKotlin --console=plain`（JAVA_HOME 用 adoptium-21）。
- 打包：`.\gradlew :app:assembleDebug`，`adb install -r`。
- 手测：
  1. 单票跑 `600176`：顶部新卡状态与主图末段背景分区一致、建议方向与最近 ▲/▼ 不矛盾。
  2. 持仓池加 2 票 → 显示状态/建议/收益/持仓%；更新/移除生效且杀进程后仍在。
  3. 简报刷新 → 按 买入→卖出→持有→观望 排序，chip 计数正确。
  4. 回归：Hybrid 页三模式不受影响（pref/文件名隔离）。
- 持久化核查：`adb shell run-as com.chin.stockanalysis ls files/ | grep ssa_t0` 与 `cat shared_prefs/ssa_t0_pool.xml`。

## 7. 需用户确认的决策

1. **耗时**：池 N 票串行 ≈ N×(6~25s)。是否接受池/简报把时线截到 ~500 根？（否则 800 根更慢）
2. **adviceOf 支持边界**：默认三态机（含 winHalf）完整支持；`日线cycle拐点规则 / 中线上穿 / reduceMode==3` 只给"观望+提示"。是否接受？
3. **引擎 API 变更**：允许在 `Result`/`SimOut` 增 `finalHoldingPct`（供建议卡用）？（改动安全，命名参数构造）
