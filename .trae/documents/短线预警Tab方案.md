# 三层 SSA 稳态做T页 · 「⚠ 预警」Tab 实施方案

## 1. 摘要（Summary）

在做T页现有三个模式（🔍 单票建议 / 📋 持仓池 / ⏱ 每小时简报）之外，新增第 4 个模式 **「⚠ 预警」**。
该模式取**末 120 根 60 分钟 K 线**（≈30 个交易日，满足用户"一个月"口径）做短线技术指标分析：

- 主图：K 线 + **JAX（济安线）** 的 A 线 / J 线
- 副图 1：**OBV** + MAOBV
- 副图 2：**KDJ**
- 副图 3：**WR**
- 副图 4：**BB（=BBI）** + BBI 均线

按用户给出的 5 条买入、5 条卖出、1 条特殊规则，逐根 K 线判定信号，输出**买入/卖出价格列表**（含触发价与触发原因文案），并对同一根 K 线上多条信号共现做**共振高亮**。

**定位：纯展示 / 辅助预警，不接入 SsaT0Engine 的损益与回测**，不做盈亏统计（避免过拟合与未来函数争议）。全部指标只使用 `≤ i` 的历史数据，无未来函数。

已确认口径（本轮 AskUserQuestion）：
- K 线周期：**60 分钟(1H)，120 根 ≈ 30 交易日**
- 信号组合：**独立预警 + 共振高亮**（每条规则独立触发；同根共现时高亮标注）
- 输出形态：**信号列表 + 五张图**（1 主图 + 4 副图）
- JAX 口径：**A 线 = JAX 线，J 线 = 济安线，N=5**
- BB 口径：**BB 即是 BBI 指标**

---

## 2. 现状分析（Current State Analysis）

### 2.1 现有文件与可复用资产

| 文件 | 作用 | 复用点 |
|------|------|--------|
| [SsaT0Fragment.kt](file:///d:/Stock/StockAnalysis/app/src/main/java/com/chin/stockanalysis/ui/SsaT0Fragment.kt) | 做T页 UI（三模式） | 模式栏 L136-159；`switchMode(m)` L401-420；UI helper 全套 |
| [IndicatorBacktestEngine.kt](file:///d:/Stock/StockAnalysis/app/src/main/java/com/chin/stockanalysis/strategy/indicatorbt/IndicatorBacktestEngine.kt) | 纯 Kotlin 指标/回测 | `Bar` L36；`smaSeries` L686；`emaSeries` L701；`kdjSeries` L764；`wrSeries` L794 |
| [IntradayKlineFetcher.kt](file:///d:/Stock/StockAnalysis/app/src/main/java/com/chin/stockanalysis/strategy/data/IntradayKlineFetcher.kt) | 1H 数据源 | `fetchHourlyHistory(code, days)` L65；新浪 scale=60 返回**含 volume**，映射 `IntradayKlineEntity.volume` L114 |
| [SsaCycleForecast.kt](file:///d:/Stock/StockAnalysis/app/src/main/java/com/chin/stockanalysis/strategy/ssa/SsaCycleForecast.kt) | 上一批新增（纯展示外推） | 纯 Kotlin 引擎 + PC 单测的既有范式 |
| [SsaT0PcBacktest.kt](file:///d:/Stock/StockAnalysis/app/src/test/java/com/chin/stockanalysis/strategy/ssa/SsaT0PcBacktest.kt) | PC 单测（直连新浪） | HttpURLConnection 抓 1H K 线 + dump CSV 的既有范式 |

### 2.2 UI 现状（SsaT0Fragment.kt）

- 模式栏 L137-159：3 个 Button（`modeBtnSingle` / `modeBtnPool` / `modeBtnBrief`），横向等分。
- `switchMode(m)` L401-420：`m: 0=单票建议 1=持仓池 2=每小时简报`；用 `pool`/`brief` 两个布尔控制各区块 visibility，末尾按模式重绘。
- 选中态：`modeBtnX.setBackgroundColor(if (选中) TEAL else idle)`；`modeBtnSingle` 当前条件为 `if (!pool && !brief)` —— **加第 4 模式后必须补 `&& !alert`**，否则预警态下「单票建议」按钮会错误高亮。
- 可复用 helper：`card{}` L1479、`row/tableRow` L1487/1504、`hint` L1469、`legend` L1472、`statGrid` L975、`chip` L737、`line(a,nm,c,w)` L1323、`miniLine(sets,bars,h,fmt,zeroLine)` L1328、`linkCharts(charts)` L1184、私有 `RegimeChart` L1053、`entries`、`bandSet`、`dp/status/roundedBg`、颜色常量 RED/GREEN/BLUE/TEAL/PURPLE/GRAY、`resolveCode()`、`loadDaily()`。

### 2.3 数据链缺口

- `IntradayKlineFetcher.fetchHourlyHistory` 返回的实体**含 volume**（L114/L161）。
- 但 `SsaT0Engine.Bar` 丢弃了 volume → 预警引擎需**自建 Bar**（带 volume）以支撑 OBV。
- `IndicatorBacktestEngine.Bar` 已有 `volume: Long = 0L` 字段（L42），可直接承载。

---

## 3. 指标公式落地方案

### 3.1 JAX = 济安线（N=5）

标准公式（通达信/大智慧口径）：

```
VAR1 = (2*CLOSE + HIGH + LOW) / 4
AA   = ABS(VAR1 - MA(CLOSE, N)) / MA(CLOSE, N)      // N=5
JIX  = DMA(VAR1, AA)                                 // 济安线：以 AA 为权重的动态平滑
CC   = CLOSE / JIX
MA1  = MA(CC * VAR1, 3)
MAAA = ((MA1 - JIX) / JIX) / 3
JAX  = MA1 - MAAA * MA1                              // A 线
```

落地要点（**防 NaN 污染 / 防除零**）：
- `DMA(X, A) = A*X + (1-A)*prev`，需递归。**播种策略**：从 `i = N-1`（首个 `MA(CLOSE,N)` 有效点）起，令 `jix[i] = VAR1[i]`，之后递推。
- `AA` 或 `JIX` 为 0 / NaN → 该点输出 NaN，并**跳过递推**（保持上一有效 `jix` 继续，或直接置 NaN 并等下一有效点重置）。**关键**：任何一处 NaN 一旦进入权重递归会污染整条 JIX/JAX，必须显式拦截。
- `CC = CLOSE / JIX` 需防 `JIX == 0`。
- `[0, N+2)` 段全 NaN（暖机区）。

### 3.2 BB = BBI（多空指标）

```
BBI    = (MA(C,3) + MA(C,6) + MA(C,12) + MA(C,24)) / 4
BBIMA  = MA(BBI, bbMaN)      // "其MA线/平均线"，默认 bbMaN = 3
```

### 3.3 OBV / MAOBV

```
OBV[i]   = OBV[i-1] + (close 上涨 ? +vol : close 下跌 ? -vol : 0)   // 窗口首根 OBV = 0
MAOBV    = EMA(SMA(OBV, 10), 3)     // 与 IndicatorBacktestEngine MULTI 分支 L313-345 口径一致
```

注意：OBV 必须以**窗口首根 = 0** 起累加（绝对值无意义），否则 Y 轴被绝对值压平。

### 3.4 KDJ / WR

直接复用 `IndicatorBacktestEngine.kdjSeries(bars, 9)`（K/D/J）与 `wrSeries(bars, 14)`（**国内口径 0~100，越大越超卖**）。

---

## 4. 信号谓词（逐根 Boolean 判定）

设 `i` 为当前根，`i-1` 为上一根。所有判据只用 `≤ i` 数据，在 `i` **收盘确认**。

### 4.1 买入信号（5 条）

| # | 条件 | 判定（在 i 根触发） | 原因文案 |
|---|------|---------------------|----------|
| B1 | JAX：A 线上穿 J 线 | `jax[i-1] <= jix[i-1] && jax[i] > jix[i]` | `JAX: A线(%.3f)上穿J线(%.3f)` |
| B2 | OBV 上穿 MAOBV | `obv[i-1] <= maobv[i-1] && obv[i] > maobv[i]` | `OBV上穿MAOBV` |
| B3 | KDJ 金叉 | `k[i-1] <= d[i-1] && k[i] > d[i]` | `KDJ金叉(K=%.1f,D=%.1f)` |
| B4 | WR 从 >65 下穿至 <65 | `wr[i-1] > 65 && wr[i] <= 65`（触发）；`<25` 为「动能最强」标注 | `WR自%.1f降至%.1f(<65)` |
| B5 | BB 上穿其均线 | `bbi[i-1] <= bbima[i-1] && bbi[i] > bbima[i]` | `BB上穿均线` |

### 4.2 卖出信号（5 条）

| # | 条件 | 判定 | 说明 |
|---|------|------|------|
| S1a | A 线跌落 J 线 | `jax[i-1] >= jix[i-1] && jax[i] < jix[i]` | 趋势转弱，直接卖 |
| S1b | A 线在 J 线上，价格低于 A 线且**超出阈值** | `jax[i] > jix[i] && close[i] < jax[i] * (1 - devPct/100)` | 跌破 A 线支撑超阈值卖；**阈值内持有**（不产生信号） |
| S2 | OBV 下穿 MAOBV 且 MAOBV 趋势向下 | `obv[i-1] >= maobv[i-1] && obv[i] < maobv[i] && maobv[i] < maobv[i-1]` | 双重确认 |
| S3 | KDJ 死叉 | `k[i-1] >= d[i-1] && k[i] < d[i]` | |
| S4 | WR 从 <25 上升越过 25 | `wr[i-1] < 25 && wr[i] >= 25` | **从低到高增加**才卖 |
| S5 | BB 下穿其均线 | `bbi[i-1] >= bbima[i-1] && bbi[i] < bbima[i]` | |

> **S1b 必须实现为「事件」而非「状态」**：仅在**首次**满足（`i-1` 不满足 且 `i` 满足）的那一根产生一条卖出信号，避免状态持续期间每根都刷屏。

### 4.3 特殊情况（买入）

| 条件 | 判定 | 说明 |
|------|------|------|
| X1 BB 均线向上 + BB 在均线上（多头结构），即便 A 线在 J 线下，可逢低买 | `bbima[i] > bbima[i-1] && bbi[i] > bbima[i] && jax[i] < jix[i]` **且** 触发低点：`close[i] ≤ jax[i]`（价格触及 A 线支撑）**或** B5（BB 自下上穿均线） | 逢低买入，原因文案标注「BB多头+触及A线支撑」/「BB多头+BB上穿均线」 |

### 4.4 共振高亮

同一根 K 线上 **buy 侧** 或 **sell 侧** 命中 ≥2 条规则 → 该根标记 `resonance = true`，列表与图上用加粗/描边高亮。
**注明**：共振只是"同根共现"，JAX 与 BB 同属趋势类会天然重复，**不等于独立确认**（页内 hint 明示）。

---

## 5. 代码改动清单

### 5.1 新建 `app/src/main/java/com/chin/stockanalysis/strategy/ssa/SsaAlertEngine.kt`（纯 Kotlin，零 Android 依赖）

```kotlin
object SsaAlertEngine {
    data class Bar(val date: String, val time: String,
                   val open: Double, val high: Double, val low: Double,
                   val close: Double, val volume: Long)

    data class AlertParams(
        val jaxN: Int = 5,        // 济安线 N
        val obvMaN: Int = 10,     // MAOBV 的 SMA 周期
        val kdjN: Int = 9,
        val wrN: Int = 14,
        val bbiMaN: Int = 3,      // BBI 的均线周期
        val devPct: Double = 2.0  // S1b：跌破 A 线的阈值（%）
    )

    data class Signal(
        val index: Int, val date: String, val time: String,
        val price: Double,          // 触发根收盘价（次日开盘执行）
        val buy: Boolean,
        val cond: String,           // "B1".."B5" / "S1a".."S5" / "X1"
        val reason: String,         // 中文原因文案
        val resonance: Boolean,     // 同根同向 ≥2 条
        val strong: Boolean = false // B4 的 <25 动能最强标注
    )

    data class Result(
        val bars: List<Bar>,
        val jax: DoubleArray, val jix: DoubleArray,   // A线 / J线(济安线)
        val obv: DoubleArray, val maobv: DoubleArray,
        val k: DoubleArray, val d: DoubleArray, val j: DoubleArray,
        val wr: DoubleArray,
        val bbi: DoubleArray, val bbiMa: DoubleArray,
        val signals: List<Signal>
    )

    fun analyze(bars: List<Bar>, p: AlertParams = AlertParams()): Result
}
```

- 复用 `IndicatorBacktestEngine.{smaSeries, emaSeries, kdjSeries, wrSeries}`（它们接受 `List<Bar>`；`IndicatorBacktestEngine.Bar` 已含 volume，可直接构造同构列表传入）。
- 自实现：`dmaSeries`（JAX 递归）、`obvSeries` + `maobvSeries`、`bbiSeries`。
- `analyze` 末尾统一扫描 0..n-1 生成 `signals`（含共振判定）。

### 5.2 新建 `app/src/test/java/com/chin/stockanalysis/strategy/ssa/SsaAlertPcTest.kt`（PC 单测）

- 照抄 `SsaT0PcBacktest.kt` 的 HttpURLConnection 范式，直连新浪 scale=60。
- **必须读 volume 字段**（OBV 依赖它）。
- 取**末 200 根计算** → 只展示末 120 根（见 §7 风险）。
- dump 两个 CSV 到 `app/build/`：
  - `build/ssa_alert_signals.csv`（date,time,price,buy,cond,reason,resonance）
  - `build/ssa_alert_indicators.csv`（date,time,close,jax,jix,obv,maobv,k,d,j,wr,bbi,bbima）
- 断言：①无未来函数（对 `i` 输出的指标与只用 `[0..i]` 重算一致，抽样验证）；②确定性（同输入同输出）；③信号条数 > 0；④`[0, N+2)` 段 JAX 全 NaN。

### 5.3 修改 `app/src/main/java/com/chin/stockanalysis/ui/SsaT0Fragment.kt`

**字段区（L97-113 附近）**：
```kotlin
private lateinit var modeBtnAlert: Button
private lateinit var alertTools: LinearLayout   // 输入行 + 刷新按钮
private lateinit var alertBox: LinearLayout     // 信号列表 + 5 图
```

**模式栏（L141-159）**：追加第 4 个 Button：
```kotlin
modeBtnAlert = Button(ctx).apply {
    text = "⚠ 预警"; textSize = 12f; setTextColor(Color.WHITE)
    setBackgroundColor(Color.parseColor("#B0BEC5"))
    layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(6) }
    setOnClickListener { switchMode(3) }
}
// 注意：4 个按钮时把 marginEnd 从第 3 个按钮移除，改用 marginStart 均匀间隔
modeRow.addView(modeBtnAlert)
```

**switchMode（L401-420）**：
```kotlin
private fun switchMode(m: Int) {
    val pool = m == 1; val brief = m == 2; val alert = m == 3
    inputCard.visibility = if (pool || brief || alert) View.GONE else View.VISIBLE
    resultBox.visibility  = if (pool || brief || alert) View.GONE else View.VISIBLE
    // ...pool/brief 各块不变...
    alertTools.visibility = if (alert) View.VISIBLE else View.GONE
    alertBox.visibility   = if (alert) View.VISIBLE else View.GONE
    ...
    modeBtnSingle.setBackgroundColor(if (!pool && !brief && !alert) TEAL else idle)  // ← 必须补 !alert
    modeBtnAlert.setBackgroundColor(if (alert) TEAL else idle)
    if (alert) { status(""); renderAlertDefault() }
}
```

**新增 `onAlertRun()`**：
1. `resolveCode()` 取代码；
2. `IntradayKlineFetcher.fetchHourlyHistory(code, 200)` → 映射为 `SsaAlertEngine.Bar`（**带 volume**）；
3. 取末 120 根 → `withContext(Dispatchers.Default) { SsaAlertEngine.analyze(bars120) }`；
4. `renderAlert(code, name, result)`。

**新增 `renderAlert()`**：
- 顶部：`card {}` 显示代码/名称/根数/末根时间/收盘/**末根未确认标记**（交易时段内）。
- **信号列表**：`tableRow(表头, widths)` + 每条信号一行（时间、价格、买/卖、cond、原因文案）；共振行用 `RED`/加粗；`hint` 说明"触发价=信号根收盘，次日开盘执行"。
- **主图**：复用私有 `RegimeChart`（K 线 + `jax`/`jix` 两条线），用 `triangles` 标买卖点（同 `SsaT0Fragment` 现有买卖点画法）。
- **4 张副图**：`miniLine(...)`：
  - OBV：`obv` + `maobv`，`zeroLine=true`
  - KDJ：`k/d/j`，Y 轴固定 **−20~140**，`addLimitLine` 20/80
  - WR：`wr`，`addLimitLine` 65/25
  - BB：`bbi` + `bbiMa`
- `linkCharts(listOf(主图, obv图, kdj图, wr图, bb图))` 联动缩放/十字线。
- 页内 `hint`：**「展示用信号、不做盈亏回测、无未来函数；共振仅表示同根共现」**。

**optional**：`alertTools` 内放 `devPct` 阈值 EditText（默认 2.0%）+ 「▶ 刷新」按钮，绑定 `AlertParams.devPct`。

---

## 6. 假设与决策（Assumptions & Decisions）

1. **不接入损益/回测**：预警 Tab 纯展示，复用 SsaT0Engine 的损益无意义（该页是三态机，非本指标组）。已在 hint 明示。
2. **JAX 播种规则**：从首个 `MA(CLOSE,N)` 有效点以 `jix=VAR1` 播种 DMA，之后递推；不引入未来函数。
3. **WR 口径**：沿用 `IndicatorBacktestEngine.wrSeries` 的国内口径（0~100，越大越超卖），与用户"65/25"描述一致。
4. **BB = BBI**：`bbiMaN = 3`（可在 `AlertParams` 调整）。
5. **计算 200 根 / 展示 120 根**：BBI 需 MA24 + MA3 ≈ 26 根暖机、JAX 需 N+2 根暖机；120 根对暖机偏紧，故多抓 80 根暖机后再截末 120 展示，保证展示区指标全部有效。
6. **S1b 阈值**：`devPct` 默认 2.0%（可配置），实现为事件型（首次触发）。
7. **末根未确认**：交易时段内末根 1H 未收盘 → 图上与列表标注「末根未确认」。
8. **共振非独立确认**：JAX 与 BB 同属趋势类，共振会天然重复计数，仅作视觉高亮。
9. **模式栏 4 等分**：`modeRow` 4 个按钮 `weight=1`，改用 `marginStart` 均匀分布（避免窄屏挤字）。

---

## 7. 风险清单（Risks）

| 风险 | 说明 | 对策 |
|------|------|------|
| JAX NaN 污染全序列 | DMA 递归中任一 NaN 会传染整条 JIX/JAX | 显式拦截 `AA`/`JIX` 为 0 或 NaN，跳点；`[0,N+2)` 全 NaN |
| AA 权重可 >1 致 DMA 过冲 | 个股波动大时 `AA` 可能 >1 | 数值监测；必要时对 `AA` 做 `coerceIn(0,1)`（在单测中对照） |
| OBV 须以窗口首根 = 0 起累加 | 否则 Y 轴被绝对值压平 | 明确首根 OBV=0 |
| 卖①写状态会每根刷屏 | 状态持续期间每根都满足 | 必须"首次触发"事件型（§4.2 说明） |
| WR「同根下穿 65 且 <25」几乎不可能 | 一天内跳 40 个点罕见 | B4 触发放宽为下穿 65；`<25` 仅作随后若干根的"动能最强"标注 |
| 120 根暖机偏紧 | BBI/JAX 暖机占用较多 | 抓 200 根计算、只展示末 120 |
| 末根 1H 未收盘 | 交易时段内信号会变 | 标注「末根未确认」 |
| 共振重复计数 | JAX/BB 同属趋势类 | hint 明示"非独立确认" |
| 模式栏窄屏挤压 | 4 个按钮 FIXED | 用 weight=1 + marginStart；文案精简为「⚠ 预警」 |

---

## 8. 验证步骤（Verification）

1. **PC 单测**：
   ```
   $env:JAVA_HOME="D:\Program Files\Android\gradle\jdks\eclipse_adoptium-21-amd64-windows.2"
   .\gradlew.bat :app:testDebugUnitTest --tests "*SsaAlertPcTest" --no-daemon --rerun-tasks
   ```
   产物：`app/build/ssa_alert_signals.csv`、`app/build/ssa_alert_indicators.csv`。
   （`CLIXML` 报错 + PowerShell profile 噪声为已知噪声，以 `BUILD SUCCESSFUL` 为准。）
2. **抽查信号**：在 CSV 中核对 JAX/OBV/KDJ/WR/BB 交叉点是否与信号一致；确认卖①为事件型（不刷屏）。
3. **编译 + 打包**：
   ```
   .\gradlew.bat :app:assembleDebug --no-daemon
   ```
4. **装机**：
   ```
   adb -s 192.168.11.72:43227 install -r app\build\outputs\apk\debug\app-debug.apk
   ```
   （必须 `-s` 指定设备；当前有两个设备。）
5. **真机核对**：进入 做T页 → ⚠ 预警 → 输入票代码 → 刷新，检查：5 张图渲染、信号列表价格/原因、共振高亮、模式切换高亮正确（单票建议不被误高亮）。

---

## 9. 执行顺序

1. 新建 `SsaAlertEngine.kt`（引擎 + 公式 + 10 谓词 + 共振）。
2. 新建 `SsaAlertPcTest.kt`，跑 PC 单测，dump CSV 自查。
3. 改 `SsaT0Fragment.kt`（模式栏 + switchMode + onAlertRun + renderAlert + 5 图）。
4. 编译 → `assembleDebug` → `adb install`。
5. 真机核对，回报用户。
