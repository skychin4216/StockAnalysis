# T0 双通道预警 · APK 落地与 UI 设计

> 依据：2026-10-20 全池回测收敛结论（SKILL: ssa-t0-variant-backtest）
> 双通道：入场=BBI12+ER70+Δ5ER+固定止损（横截面闸，选股）；做T=T0(rsi+adx+erq)（自身时序闸，择时）
> 2026-10-20

## 1. 总体架构：APK 本地自算为主，PC 推送兜底

```
┌─ APK（主路径，全本地）──────────────────────────────┐
│ 1H K线缓存（已有 kline_store 同步）                    │
│   → Kotlin SsaT0Engine（已有，回写4个开关参数）          │
│   → SsaBbiErEngine（双模式已有，加 Δ5ER+固定止损）       │
│   → ER 自身时序分位 W250（新增小工具类，单票自洽）        │
│   → 信号判定 → AlertStore 落库 → 预警页渲染 + 系统通知    │
└──────────────────────────────────────────────────┘
┌─ PC 守护（兜底/交叉验证，可选）───────────────────────┐
│ _market_scan 每小时 → _t0_alert.py → outbox 推送       │
└──────────────────────────────────────────────────┘
```

**为什么本地自算**：erq 闸单票自洽（无需全池数据）；BBI12 入场闸虽用横截面分位，但 APK 已缓存全池 171 只 K 线，自算无压力。本地算 = 无网络依赖、无推送延迟、口径与回测 100% 一致。

**刷新节奏**（与回测口径一致，bar 收盘决策）：

| 时点 | 触发 | 动作 |
|---|---|---|
| 10:32 / 11:32 / 14:02 / 15:02 | WorkManager 定时 | 增量拉最新 1H bar → 单窗口 SSA（<1s/只）→ 出信号 |
| 15:02 | 同上 | 额外刷新日聚合层 + 当日推荐归档 |
| 任意时刻 | 手动下拉 | 同上（防数据缺失兜底） |
| 盘中 bar 未收盘 | 不刷新 | 中间价信号与回测口径不一致，禁止 |

## 2. 预警页 UI 设计

预警 Tab 重构为**双通道分段页**：

```
┌─────────────────────────────────────┐
│ [入场推荐] [持仓做T] [历史]          ← 顶部 TabRow │
├─────────────────────────────────────┤
│ ⏱ 上次刷新 14:02 · 下次 15:02  [↻]   ← 状态条     │
├─────────────────────────────────────┤
│ （通道内容区）                         │
└─────────────────────────────────────┘
```

### 2.1 入场推荐通道（BBI12+ER70+Δ5ER+固定止损）

信号稀疏（全池日均 0~3 条），卡片式设计：

```
┌──────────────────────────────────┐
│ 🟢 买入  600176 中国巨石          │
│ 信号价 18.42 · 止损价 18.42(成本)  │
│ ER分位 82 ▓▓▓▓▓▓▓▓░░ · Δ5ER +0.03 │
│ BBI12 拐点↑ · 14:02              │
│ [加入自选] [模拟买入]              │
└──────────────────────────────────┘
```

- 信号类型三色：🟢买入 / 🔴拐点卖出 / ⚫止损离场
- 空态：「当前无符合趋势段标准的标的」（这是常态，ER 闸本就过滤 90%+ 时段）
- 排序：信号时间倒序，NEW 徽标 24h 内有效

### 2.2 持仓做T通道（T0 rsi+adx+erq）

每条持仓一张卡片，核心是**点位条**：

```
┌──────────────────────────────────┐
│ 600176 中国巨石 · 状态:UP         │
│ 现价 18.42  成本 16.80  +9.6%    │
│ ├─eDn 17.95──●现价──eUp 19.10─┤  ← 点位条      │
│ 卖T点 19.10 (RSI 68 未确认○)      │
│ 买回点 17.95 (ER闸 放行✓ 分位74)  │
│ ─────────────────────────────    │
│ 策略收益 +12.3% vs 持有 +9.6%    │
│ 已平T单 23 笔 · 胜率 70%         │
└──────────────────────────────────┘
```

- 点位条：eDn—现价—eUp 一维标尺，现价游标实时位置
- 卖T 需 RSI>70 确认：○未确认 / ●已确认（确认才推送）
- 买回需 erq 闸放行：✓放行 / ✗拦截（分位值显示）
- 策略收益 = 持仓浮盈 + 已平T单累计盈亏（FIFO 配对，引擎内记账）
- 状态徽标：UP=红 / FLAT=灰 / WINDOW=橙

### 2.3 历史 Tab

```
┌──────────────────────────────────┐
│ 筛选: [全部|入场|做T] [本周|本月|全部]│
├──────────────────────────────────┤
│ 10-20 14:02 买入 600176 @18.42   │
│   → 当前 19.10  +3.7%  进行中     │
│ 10-17 10:32 止损 002xxx @9.81    │
│   → 完结 -0.9%（止损保护）         │
├──────────────────────────────────┤
│ 统计: 推荐 42 · 胜率 58% ·        │
│   平均持有 6.2 根bar · 累计 +11.3% │
└──────────────────────────────────┘
```

- 每条推荐自动跟踪后续表现（T+1/T+5/当前 三档收益）
- 完结口径：入场通道=止损/拐点卖出；做T通道=该T单平仓
- 顶部统计条：推荐数、胜率、平均持有、累计收益（数据驱动复盘依据）

## 3. 数据模型

```kotlin
// AlertStore 扩展（现有 SsaAlertStore 加 channel 字段）
data class AlertRecord(
    val id: String,           // code+ts+channel
    val ts: Long,             // 信号 bar 收盘时间
    val code: String,
    val channel: Channel,     // ENTRY_BBI / HOLD_T0
    val action: Action,       // BUY / SELL_PIVOT / STOP_LOSS / T_SELL / T_BUY
    val price: Double,
    val erPct: Float,         // 入场=横截面分位；做T=自身时序分位
    val extra: String,        // JSON: d5er/rsi/eUp/eDn/stopPx
    var status: Status,       // ACTIVE / CLOSED_PROFIT / CLOSED_STOP / EXPIRED
    var trackJson: String     // 后续收益跟踪 {t1, t5, now}
)

// 持仓做T状态（每股一条，引擎状态序列化）
data class HoldT0State(
    val code: String, val costBasis: Double, val shares: Int,
    val regime: Int,          // 1UP 2FLAT 3WINDOW
    val eUp: Double, val eDn: Double, val mid: Double,
    val rsiOk: Boolean, val erqOk: Boolean, val erqPct: Float,
    val closedTs: Int, val tWinRate: Float,
    val stratPnl: Double,     // 策略累计收益（含已平T单）
    val holdPnl: Double       // 买入持有收益（对照）
)
```

## 4. 自动更新与通知

- **WorkManager**：`PeriodicWorkRequest(30min)` 做守时兜底 + 4 个 bar 收盘时点精确触发（`setInitialDelay` 对齐 10:32/11:32/14:02/15:02）
- **通知分级**：入场买入/止损 = 高优先渠道（响铃+横幅）；做T 点位 = 默认渠道（静默进通知栏，不进勿扰）
- **防打扰**：同一信号 4h 内不重复推；止损信号不受限
- **权限**：通知权限引导 + 电池优化白名单引导（首次进预警页弹一次）

## 5. Kotlin 侧引擎改动清单

| 改动 | 文件 | 说明 |
|---|---|---|
| 4 个开关回写 | SsaT0Engine.kt / PoolMember | reduceMode=2 默认、tRsiConfirm(70)、adxGate、erGate 参数补齐（Python 已验证口径，逐行对译） |
| erq 自身分位 | 新增 ErQuantile.kt | 滚动 W250，≥60 有效样本，输入 closes 输出 erPct 序列（只算最新点即可） |
| Δ5ER+固定止损 | SsaBbiErEngine.kt | 入场通道：er[t]-er[t-5]>-0.05 过滤 + 跌破买入价离场 |
| 点位计算 | 预警页 VM | 只算最新 bar 的 mid/eUp/eDn（非全序列回测） |
| 收益跟踪 | AlertStore | 每条 ACTIVE 记录随行情刷新 trackJson |

## 6. 落地步骤（5 步）

1. ✅ **ErQuantile.kt + 四开关回写**（2026-10-20 完成，编译通过）：
   - 新增 `SsaErQuantile.kt`（erSeries W60 / rollingPct W250≥60样本 / erqArrays，与 Python `--erq` 逐行对齐）
   - `SsaT0Engine.Params` 加 tRsiConfirm/adxGate/erGate/erD5/erPctArr/erArr；reduceMode 默认 0→2
   - 新增 `rsiSeries`；simulate UP 分支接三开关；**run() 自动注入 erq**（调用方零改动）
   - adviceOf 镜像三开关；`SsaT0PoolStore` 加 rsiCf/adxG/erG（JSON 向后兼容）
2. ⏸ **数值验收**（等设备）：APK 抽样 5 只 vs Python `_t0_pool_results_rsi_adx_erq.json`，误差 <0.5%
3. ✅ **SsaBbiErEngine 入场通道补齐**（2026-10-20 完成，编译通过）：
   - 引擎加 `gateEr`/`erPctArr`/`fixedStop` 三参数（默认关，旧口径不变）；固定成本止损（优先级：峰顶>固定止损>移动止盈）；新增 `Kind.SELL_STOP`
   - `SsaAlertBatch` 切冠军口径：fixedStop=true；ER70 横截面闸在建议级应用（"关注(ER<70)"+拦截原因）
   - `SsaT0Fragment` 信号表补"止损卖"
4. ✅ **预警页三段式 UI**（2026-10-20 完成，编译通过）：
   - `SsaAlertStore` 推荐台账（Rec + JSONL 滚动 500 条 + 原子写）；`SsaAlertBatch` 可执行建议自动落库
   - `SsaT0Fragment` 预警批结果内嵌三子 Tab：**入场推荐**（OverlayRow 卡）/ **持仓做T**（T0Snapshot 点位条卡：eDn━●━eUp + 带内位置% + 策略收益/回撤/胜率/笔数 + 建议原因）/ **历史**（台账 30 条）
   - `SsaT0PoolStore.T0Snapshot` 扩展 mid/eUp/eDn 末根点位（NaN 安全，旧 JSON 兼容）；`updateOne` 回填
   - 未做：台账收益跟踪（T+1/T+5）、通知渠道——留待真机验证后补齐
5. ⏳ **WorkManager 定时 + 通知渠道**：真机验证 4 时点触发

> 验收标准：APK 本地算出的信号与 PC `_t0_alert.py` 同 bar 输出逐条一致。
