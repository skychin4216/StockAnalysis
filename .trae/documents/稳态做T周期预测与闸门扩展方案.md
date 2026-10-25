# 三层 SSA 稳态做T → 周期预测 + 指标闸门扩展方案

## Context（为什么改）

当前「三层 SSA 稳态做T」页以小时线**三态机**（UP/WINDOW/FLAT，cycle 符号定态）为主线，
日线层/周线层在引擎里**不参与主状态判定**，只作统计口径与两个保留选项的数据源。
用户提出三点需求：

1. 日线层/周线层的**页面展示**去掉（引擎保留，既有回测结论不得改变）；
2. 新增一个「**拟合周期的函数**」模块：用滚动窗口每天更新，预测**未来一天走势 + 目标价位**；
3. 主图可见区间增加「**近1个月**」选项；
4. 并询问：为**减少回撤**可加哪种指标辅助判断（用户猜「动能指标」）。

已确认口径（用户选择）：
- 日/周层：**只去页面展示**（`render()` 里的 ③④ 两张卡），引擎 `d/w` 一律保留。
- 预测模块定位：**纯展示 + 命中率回测**，不进信号、不进回测损益。
- 指标验证：先用 PC 回测壳跑 **4 类闸门**证据（下沿止损 winStopDn / 波动目标T仓 / SNR闸门 / ADX闸门），
  跑出证据后再决定哪些做成 App opt-in 勾选框（复刻现有 `winHalfCb` 模式）。

> 注：用户"减少回测"按「减少回撤(drawdown)」理解（回撤主源是 WINDOW 段满仓接盘）。

**关于"动能指标"的结论（如实回答）**：小时层 cycle 本身就是平滑动能/振荡度量，RSI/MACD 与其高度冗余；
既有教训是把状态判定换成更慢的日线 cycle（动能代理）→ 收益腰斩、回撤翻倍。
因此**不优先加纯动能指标**，改为按证据矩阵在「趋势强度 ADX / 波动率 σ / 振幅 SNR / 下沿止损」里选，
且新规则**优先做买入过滤**，UP 段不新增卖出类规则（既有铁律）。

---

## 改动 1｜UI：去 ③④ 卡 + 主图「近1月」

文件：`app/src/main/java/com/chin/stockanalysis/ui/SsaT0Fragment.kt`

1. `render()`：删除 ③日线层卡 与 ④周线层卡；⑤权益卡保留。
2. `hourRangeBar(...)`：`opts` 追加 `"近1月" to barCountSinceMonths(bars, 1)`（**追加到列表末尾**，
   保持默认选中"近3个月"的索引不漂移）。`barCountSinceMonths` 已通用，`months=1` 直接可用。

## 改动 2｜预测模块（新建）

文件：**新建** `app/src/main/java/com/chin/stockanalysis/strategy/ssa/SsaCycleForecast.kt`（纯 Kotlin `object`）

- **只读** `SsaT0Engine.Result.h` 的公开数组（`cycle/trend/mid/nUp/nDn`），**不修改** `simulate()`/`Result`/`Params`。
- 算法：对小时层 cycle 在最近约 1.5~2 个主周期的窗口上，拟合**单谐波** `A·cos(2πft+φ)+B`；
  主周期由**自相关**(首选)或**零穿越**初估，再在 `[0.75T0, 1.25T0]` 上步长 1 精修（3 元最小二乘）。
- 外推未来 **4 根 1H（≈1 个交易日）**：`price[k] = trend[last] + cycHat[last+k]`；
  目标价位 = 按方向取路径极值（`targetKind` ∈ 上探/下探/持平）；
  扇形 = `±1.5σ / ±2.0σ`（σ 由 `(nUp−mid)/cycleMult` 反解，兜底 `tailStd(close−mid, lookbackN)`）。
- 输出：`Forecast`（enabled/confNote/主周期/振幅/R²/σ/trendHold/4点路径/目标价/方向）
  与 `ForecastEval`（train/test 方向命中率、MAE、±1.5σ 与 ±2.0σ 覆盖率）。
- 入口：`forecast(h, last, horizonH=4, cycleMult, noiseMult, lookbackN)`、
  `evaluate(h, splitIndex, horizonH=4, ...)`。
- **命中率回测（无未来函数）**：逐根 t 只用 `≤t` 历史拟合→外推 4 根→与真实收盘比对，
  按 `splitIndex` 分 train/test。加单测断言：重复调用结果一致、且 t 之后 K 线置 NaN 不改 t 处预测。
- **降级**：有效点 <24 / 方差≈0 → `enabled=false` + 平坦路径 + `confNote`；
  ACF 无峰且零穿越 <2 → `T0=hour.embedL`；振幅 >3σ → 钳制并标注。
- **UI 卡**：`render()` 在 ②分量卡后插入「🔮 周期预测（下一交易日，外推·非信号）」卡——
  4 点路径表 + 目标价位 + `statGrid`(主周期/振幅/R²) + 小扇形图 + 命中率 `statGrid` + 明示"非信号"。
  在 `onRun()` 的 `Dispatchers.Default` 块内与 `adviceOf` 同批计算（避免主线程耗时）。
- **性能**：`evaluate()` 为 O(n·F)，单票 ~800 根可接受；**池内逐票默认不跑预测**（仅单票页展示）。

## 改动 3｜引擎闸门扩展（4 类，默认全关）

文件：`app/src/main/java/com/chin/stockanalysis/strategy/ssa/SsaT0Engine.kt`

**总原则：新字段默认 = 关闭（0/false），默认值下所有代码路径与当前基线逐字节等价。**

`Params` 新增字段（默认关）：

| 字段 | 默认 | 含义 |
|---|---|---|
| `winMinSnr: Double` | `0.0` | >0 时 WINDOW 买入需 `cycle振幅/σ ≥ winMinSnr` |
| `winSnrK: Int` | `0` | 振幅回看根数；0→取 `hour.embedL` |
| `winMinAdx: Double` | `0.0` | >0 时 WINDOW 买入需 1H `ADX(14) ≥ winMinAdx` |
| `volTargetT: Boolean` | `false` | 波动目标 T 仓：`f = min(1, σ_ref/σ_t)` |
| `sigmaRefAbs: Double` | `0.0` | σ_ref 基准；0→全样本 σ 中位数 |

实现要点：
- 新增私有 `adxSeries(bars, n=14)`（**自实现 Wilder**，镜像 `EtfScreenMath.adx` 口径，
  `<2n+1` 前为 NaN）——不可逐 t 调 `EtfScreenMath.adx`（只返末值，会 O(n²)）。需与其末值对拍 <1e-6。
- **WINDOW 买入闸门**：在既有条件 `crossUp && (!p.winDayUp || !dayDownAt(t))` 后追加 `winGateOk(t)`
  = `(winMinSnr<=0 || snrAt(t)>=winMinSnr) && (winMinAdx<=0 || (!adx[t].isNaN() && adx[t]>=winMinAdx))`。
  默认阈值 0 → 短路恒 true → 基线不变。
- **波动目标 T 仓**：仅缩放 **UP 段既有两处卖出 T 的数量**（`qty = sh·tPct/100·f`），
  补回 coreShares 不动，**不新增任何 UP 卖出规则**。
- **`winStopDn`**：引擎与 `adviceOf` 均已实现（WINDOW 破 `nDn`/`eDn` 止损），**保留不动**。
- **`adviceOf()` 镜像一致性**：WINDOW 买入分支加同一 `winGateOk(last)`（仅算末根 1 点）。
- `SsaT0PoolStore.PoolMember` 本次**不改**（新闸门默认关，池参数不受影响）。

## 改动 4｜PC 证据矩阵

文件：`app/src/test/java/com/chin/stockanalysis/strategy/ssa/SsaT0PcBacktest.kt`

- `variants` 追加：`winStopDn=1/2`、`volT·auto`、`snr0.4/0.6/0.8`、`adx15/20/25/30`。
- 对照表**同一张表**增列 `winStopDn,volT,snr,adx`，与原列（总收益/训练/样本外/年化/回撤/T单/胜率/笔均/超额）可比。
- 运行（建议跑 2~3 只票避免单票偶然，如 600176 / 000651 / 300750）：

```powershell
$env:JAVA_HOME="D:\Program Files\Android\gradle\jdks\eclipse_adoptium-21-amd64-windows.2"
$env:SSA_T0_CODE="600176"
.\gradlew.bat :app:testDebugUnitTest --tests "*SsaT0PcBacktest*" --no-daemon
```

## 落地顺序与验证

1. 引擎闸门（含 `adxSeries`）→ `:app:compileDebugKotlin` 通过（默认值不改变逻辑路径）。
2. **先跑基线快照**（改动前）另存 `build/ssa_t0_*.txt/csv`；改动后再跑，对 idx=0 基线与既有 variant 行做 **diff**，
   要求 `layers/markers/trades/equity` CSV **逐字节一致**、`compare.txt` 既有列数值一致（只允许多出新列）。
3. 跑证据矩阵（改动 4），按结果挑选有效闸门。
4. 预测模块 `SsaCycleForecast.kt` + 单测（无未来函数/重复性断言）。
5. UI 改动 1 + 预测卡 → 编译。
6. **按证据**把有效闸门做成 opt-in 勾选框（复刻 `winHalfCb`：`readParams()` 用 `base.copy(...)`，`prefillParams()` 回填；
   如要入池，仿 `winHalf` 追加 `PoolMember` 字段并同步 `params()/of()/load()/save()`）。
7. `:app:assembleDebug` → `adb install -r app\build\outputs\apk\debug\app-debug.apk`。

## 风险清单

1. **预测绝不进信号**：`Forecast` 不得被 `simulate()`/`adviceOf()`/`regimes()`/`Params` 引用。
2. **UP 段不新增卖出类规则**：波动目标仅缩放已有卖出 T 数量。
3. **默认关闸门守恒基线**：任何 `>0/true` 才生效，`||` 短路保证默认走原分支。
4. **数据不足降级**：`Forecast.enabled=false` 时 `forecastView` 需判空，UI 不得崩溃。
5. **主周期异常**：ACF 无峰/零穿越不足/振幅爆表 → 兜底 `T0=embedL`、振幅钳 `3σ` 并标注置信度。
6. **ADX 口径**：自实现 `adxSeries` 与 `EtfScreenMath.adx` 末值对拍，防口径漂移。
7. **性能**：预测只放单票页；池内逐票默认不跑（如需，按 `(code,末根时间)` 缓存）。
8. **可复现**：证据矩阵至少 2~3 只票。
