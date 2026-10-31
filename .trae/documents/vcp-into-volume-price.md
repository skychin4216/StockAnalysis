# 量价关系页新增 VCP（波动收缩形态）检测

## Context

用户问「波动收缩状态 VCP 能否加入到量价关系里面看看」。VCP（Mark Minervini 波动收缩形态）判定四要素：高点递降/走平、低点递升、回落幅度逐级收缩、成交量逐级萎缩，末端放量突破为买点。与「量价关系」页的波段链分析（Effort vs Result）天然互补：现有 zigzag 波段、Leg 量价统计正好提供 VCP 所需的全部原料。

目标：在量价页分析管线中新增 VCP 检测，结果以文字卡片 + 主图压力线呈现，只影响量价页，不动选股主线。

## 现状与可复用件

- [VolumePriceFragment.kt](file:///d:/Stock/StockAnalysis/app/src/main/java/com/chin/stockanalysis/ui/VolumePriceFragment.kt)
  - `zigzag(closes, thPct)` → `Pivot(idx, price, isHigh, confirmed)`（L376）
  - `buildLegs(bars, pivots, obv)` → `Leg(chgPct, bars, volAvg, unitVol, obvDelta)`（L534）
  - 分析管线 `analyze()`（L340）：resolveCode → loadBars → zigzag → buildLegs → computeCycle → render
  - 渲染 `render()`（L562）：renderSummary + 图表卡片（K线主图带 pivotLabels 标注 L748）
  - 现有评级文风参考 `acceptanceText()`（L715）：量比 + 回撤比 → ✅/⚠️/➖ 结论
- [CandlePatternDetector.kt](file:///d:/Stock/StockAnalysis/app/src/main/java/com/chin/stockanalysis/strategy/analysis/CandlePatternDetector.kt) 杯柄形态（L1180）可作 PatternMatch 返回结构参考，但不复用（VCP 基于 zigzag 波段更直接）

## 实现方案

### 1. 新增 `VcpDetector.kt`（strategy/analysis 包，纯 Kotlin 无 Android 依赖，便于复用/测试）

输入：`bars: List<KBar 等价物>`（只用 close/vol）、`pivots`、`legs`（类型用简单参数传入，避免依赖 UI 私有类——detector 内定义自己的轻量输入 data class，Fragment 侧做转换）。

检测算法（从最近向前构造 base）：

1. 取 confirmed pivots（末端未确认 pivot 保留，用于"构建中"判断）
2. 定位 base 锚点：最近一段显著上涨后的第一个 high pivot 记为 H0（前面须有一段上升 Leg，涨幅 ≥ 15%）
3. 从 H0 向后收集交替的 low/high pivot 序列：L1,H1,L2,H2,…
4. **收缩判定**：每次回落深度 d_i = (H_{i-1} − L_i)/H_{i-1}，要求 d 序列严格递减且 d_{i+1} ≤ 0.75×d_i；至少 2 次收缩（最多取 4 次）
5. **高点判定**：各 H_i 与 H0 偏差 ≤ 5%（允许微降，不允许大幅新高——新高说明已脱离 base）
6. **低点判定**：L_i 严格递升
7. **量能萎缩**：回落段 volAvg 序列递减，末段 ≤ 首段的 0.7；base 整体 volAvg < 前导上升段 volAvg
8. **突破判定**：最新收盘价 vs 压力位 H0（与后续 H_i 取最大）：
   - close > 压力 ×1.01 且当日量 > base 日均量 ×1.5 → ✅ 已放量突破（买点）
   - close 在压力下方 3% 内 + 末次收缩 ≤ 8% → 🔄 口袋紧缩/临突破
   - 其余 → 🔄 构建中

返回 `VcpResult`：状态（成立/构建中/不成立+原因）、收缩序列明细（每次深度、量）、压力位、距突破百分比、突破量倍数、评分（0-100：收缩次数、紧致度、量缩程度、趋势位置加权）。

### 2. VolumePriceFragment 挂载

- `analyze()` 中 `buildLegs()` 之后调用 `VcpDetector.detect(...)`，结果存入 `lastRun`（LastRun data class 加字段）
- `render()` 在 renderSummary 之后、图表卡片之前插入「🌀 VCP 波动收缩」卡片：
  - 首行结论：✅/🔄/❌ + 一句话原因
  - 收缩序列：`-22% → -13% → -7%`（每次收缩带回落段日均量）
  - 压力位 H0、现价距突破 x%、突破量倍数、评分
- 主图 K线图上：VCP 成立/构建中时画一条压力位 LimitLine（H0 价位，青色虚线，标注"VCP压力"）；base 区间不画阴影（保持图面简洁，后续需要再加）
- 日线/1H 两种模式都自动生效（检测只吃 bars，与周期无关）

### 3. 参数默认值（先做死，后续如需可调再抽 UI）

收缩递减比 ≤0.75、高点偏差 ≤5%、末段量缩 ≤0.7、突破量 ≥1.5×、前导涨幅 ≥15%、末次收缩紧致线 8%。

## 改动文件

| 文件 | 动作 |
|---|---|
| `strategy/analysis/VcpDetector.kt` | 新建（约 200 行，纯算法） |
| `ui/VolumePriceFragment.kt` | analyze/render/LastRun 三处小改 + VCP 卡片渲染函数 + 主图 LimitLine |

## 验证

1. `.\gradlew.bat :app:assembleDebug --console=plain -q *> build_out.txt`，EXIT=0
2. `adb -s 192.168.11.77:38693 install -r app\build\outputs\apk\debug\app-debug.apk`
3. 手机验证：量价页输入一只近期有收敛形态的票（如近期横盘收敛的个股），看 VCP 卡片结论是否合理；再输一只单边下跌/无形态的票，确认输出"不成立 + 原因"
4. 回放模式下确认卡片内容随 lastRun 一致（回放不改卡片，只改图）
