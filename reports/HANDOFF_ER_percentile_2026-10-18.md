# 交接报告 · ER 分位接入（PC 推送 + App 展示 + App 本机备选）

> 生成日期：2026-10-18
> 面向对象：接手的同事 / 后续的 Trae（IDE Agent）
> 一句话：把上一轮**已回测验证**的「跨票横截面 Kaufman 效率比（ER）分位」接入选股推送链，
> PC 端出数、App 端展示，并加了 **App 本机计算降级备选**（PC 推送不可用时仍可用）。
> **池口径（2026-10-18 末次修正）**：PC 推送列 / `er_pct_map` / App 本机备选**三者统一**为
> **每日荐股核心池**（`app/src/main/assets/core_stock_pool.json`，去重 171 只），K 线均为日线，W=60。

---

## 0. 先读这里（30 秒概览）

| 问题 | 答案 |
|------|------|
| 这轮做了什么 | ① PC 推送表加「ER分位」列 + 推送 JSON 注入**每日荐股核心池** `er_pct_map`；② App「三层稳态做T」新增 `ER分位` tab（**三分区**：🎯 ER Top10 + 候选票 + 持仓票）；③ App 预警「一键更新持仓股」补 ER分位；④ PC 推送不可用时 **App 本机算 ER 分位** 兜底；⑤ ER tab 顶部新增「🎯 ER Top10」**池内** ER 分位降序观察榜 |
| 核心结论（沿用上一轮回测） | ER 的**唯一有效用法 = 跨票选择**（只做横截面 ER 高位），不是逐票择时 → 组合收益 9.57%→28.97%、夏普 1.12→2.32（详见 §5） |
| 池口径（2026-10-18 末次修正） | PC 推送列 / `er_pct_map` / App 本机备选**三者统一池** = `app/src/main/assets/core_stock_pool.json`（**每日荐股核心池，去重后 171 只**）；池外票 ER分位显示「—」 |
| 参数字段 | 窗口 `W=60`；推荐闸门 `P70`（分位 ≥70 标 `↑`） |
| 上轮报告在哪 | `reports/er_router_backtest.html`（+`.png`）、数据 `app/build/er_router_summary.json`、脚本 `smalltools/_er_router_backtest.py` |
| 复现命令 | 回测：`python smalltools/_er_router_backtest.py [--fetch]`；App 编译：`.\gradlew.bat :app:compileDebugKotlin` |

---

## 1. 数据流（谁产出、谁消费）

```
                ┌─────────────────────────────────────────────┐
   PC 守护脚本   │ smalltools/_publish_candidates.py            │
   (零 LLM)     │  build_candidates(cache, industry, ctx)      │
                │    ├─ data["er_pct_map"] = _er_pct_of_pool() │ ← 新增：每日荐股池 ER 分位表 {6位码:0~100}
                │    │    池 = _core_pool_codes()（core_stock_pool.json，171 只；缺池退回全池）
                │    ├─ data["er_pct_pool"] = "daily_reco"/"all"、["er_pct_pool_n"]│ ← 新增：池来源与规模
                │    └─ round_pages() 用 er_pct_map 回填        │ ← 新增：表格「ER分位」列（与端上同源）
                └───────────────┬─────────────────────────────┘
                                │ OUT_DEFAULT = AutoQuant/data/candidates_quant.json
                                │ COS 上传为 candidates.json
                                ▼
                ┌─────────────────────────────────────────────┐
   App          │ PcBridgeClient.fetchCandidates()             │
                │   → CosRelayClient（腾讯云 COS 中继命令        │
                │     candidates.fetch）                        │
                │   → 兜底：filesDir/pc_candidates.json          │
                └───────────────┬─────────────────────────────┘
                                ▼
                ┌─────────────────────────────────────────────┐
   App UI       │ SsaT0Fragment（三层稳态做T）                  │
                │  mode 4 = 「📊 ER分位」tab                    │
                │    ⓪ 🎯 ER Top10（池内分位前 10·观察榜）       │ ← 新增
                │    ① 策略推送候选票卡片（按 ER 降序）         │
                │    ② 持仓票卡片（持仓池 + ER分位 + 末次评分）  │
                │  预警页「一键更新持仓股」→ OverlayRow.erPct    │
                │                                             │
                │ 若 PC 表缺失 → SsaErLocal.corePoolPercentiles()│ ← 新增：本机备选（每日荐股池·日线）
                └─────────────────────────────────────────────┘
```

---

## 2. 本轮改动清单（2026-10-18）

### 2.1 PC 端 —— [smalltools/_publish_candidates.py](file:///d:/Stock/StockAnalysis/smalltools/_publish_candidates.py)

| 位置 | 改动 |
|------|------|
| [L1368-1373](file:///d:/Stock/StockAnalysis/smalltools/_publish_candidates.py#L1368-L1373) | `_TABLE_HEAD` 在 `VOL` 后、`趋势图` 前插入 `"ER分位"`（29→**30 列**）；`_N_CELLS = len(_TABLE_HEAD)-2`（27→**28**） |
| [L1385](file:///d:/Stock/StockAnalysis/smalltools/_publish_candidates.py#L1385) | 新增 `_I_ER = _TABLE_HEAD.index("ER分位") - 2`（值 = **22**，cells 下标） |
| [L2144](file:///d:/Stock/StockAnalysis/smalltools/_publish_candidates.py#L2144) | `_unified_cells` 在技术块后插 `["—"]` 占位，保证 cells 严格 = 28 |
| [L2290-2322](file:///d:/Stock/StockAnalysis/smalltools/_publish_candidates.py#L2290-L2322) | **新增池加载器** `_core_pool_codes()`：读 `app/src/main/assets/core_stock_pool.json` 的 `stocks` 数组 → 6 位代码集合（进程内缓存一次）；文件缺失/为空 → 返回 `None`（调用方退回全池，不阻塞推送） |
| [L2325-2338](file:///d:/Stock/StockAnalysis/smalltools/_publish_candidates.py#L2325-L2338) | `_er_of(closes, W=60)`：末根 Kaufman 效率比（`a, b = closes[k], closes[k-1]` 防越界） |
| [L2341-2374](file:///d:/Stock/StockAnalysis/smalltools/_publish_candidates.py#L2341-L2374) | `_er_pct_of_pool(cache, asof, universe)`：**池内**（universe=None → `_core_pool_codes()`）**中位秩**横截面分位；剔除指数/数据不足票；池外票不入表 |
| [L2377+](file:///d:/Stock/StockAnalysis/smalltools/_publish_candidates.py#L2377) | `_annotate_er_percentile(img_secs, er_map)`：表格列回填（只处理统一表段 cells==28；池外票自然「—」；失败静默不阻塞） |
| [L690-695](file:///d:/Stock/StockAnalysis/smalltools/_publish_candidates.py#L690-L695) | `build_candidates` 末尾注入推送 JSON：`data["er_pct_map"]`、`data["er_pct_pool"] = "daily_reco"/"all"`、`data["er_pct_pool_n"]` |
| [L3539-3542](file:///d:/Stock/StockAnalysis/smalltools/_publish_candidates.py#L3539-L3542) | `round_pages` 回填改为复用同一张表：`er_map = data.get("er_pct_map") or _er_pct_of_pool(...)`（**保证 PNG 列与端上数值同源**） |
| `img_note` | 文案 18 列 → 30 列（含 量比/ER分位/趋势图） |

> 关键设计：ER 计算一次、两处消费（表格列 + 推送 JSON），避免端上显示值与图不一致。
> `_I_INST/_I_NAT/_I_CON/_I_FUND0` 均用 `index()` 自动位移，加列不影响其它列；text/PNG/CSV/XLSX 渲染器按 `len(_TABLE_HEAD)` 动态处理。

### 2.2 App 端

**[SsaT0Fragment.kt](file:///d:/Stock/StockAnalysis/app/src/main/java/com/chin/stockanalysis/ui/SsaT0Fragment.kt)**

| 位置 | 改动 |
|------|------|
| [L156-163](file:///d:/Stock/StockAnalysis/app/src/main/java/com/chin/stockanalysis/ui/SsaT0Fragment.kt#L156-L163) | 新增字段 `modeBtnEr`、`erTools/erUpdBtn/erUpdTv/erBox`、`erMap`、`erSourceLocal` |
| [L296-303](file:///d:/Stock/StockAnalysis/app/src/main/java/com/chin/stockanalysis/ui/SsaT0Fragment.kt#L296-L303) | 模式按钮改两行：`modeRow`(单票/持仓池/简报) + `modeRow2`(预警/ER分位) |
| [L559-570](file:///d:/Stock/StockAnalysis/app/src/main/java/com/chin/stockanalysis/ui/SsaT0Fragment.kt#L559-L570) | ER 容器 + 「🔄 拉取策略推送」按钮 |
| [L659-687](file:///d:/Stock/StockAnalysis/app/src/main/java/com/chin/stockanalysis/ui/SsaT0Fragment.kt#L659-L687) | `switchMode(m)` 支持 `m=4` |
| [L1686-1766](file:///d:/Stock/StockAnalysis/app/src/main/java/com/chin/stockanalysis/ui/SsaT0Fragment.kt#L1686-L1766) | `code6()`、`secidOf()`（6 位码 → sh/sz 前缀）、`erPctChip()`/`erChip()`、`fetchPcCandidatesJson()`、`renderErTab()`（**PC 优先 → 本机备选降级**；并只对 ER Top10 前 10 只取实时行情补名称/现价） |
| [L1768-1841](file:///d:/Stock/StockAnalysis/app/src/main/java/com/chin/stockanalysis/ui/SsaT0Fragment.kt#L1768-L1841) | `parseCands()`、`parseErMap()`、`renderErContent(o?, cands, members, quotes)`（**三分区** + 来源标注） |
| [L1843-1935](file:///d:/Stock/StockAnalysis/app/src/main/java/com/chin/stockanalysis/ui/SsaT0Fragment.kt#L1843-L1935) | `erTopCard()`（**ER Top10 卡片**）、`erCandCard()`（候选票卡片）、`erHoldCard()`（持仓票卡片） |
| [L1568-1637](file:///d:/Stock/StockAnalysis/app/src/main/java/com/chin/stockanalysis/ui/SsaT0Fragment.kt#L1568-L1637) | `onAlertBatchRun()` 补 ER（PC 表非空→用 PC；否则本机算）；`renderAlertBatchResult(rows, erLocal)` 区分口径来源 |
| [L1639-1683](file:///d:/Stock/StockAnalysis/app/src/main/java/com/chin/stockanalysis/ui/SsaT0Fragment.kt#L1639-L1683) | `alertStockCard` 改用**行内 `r.erPct`**（不再读全局 `erMap`，避免与保存值不一致） |

**[SsaErLocal.kt](file:///d:/Stock/StockAnalysis/app/src/main/java/com/chin/stockanalysis/strategy/ssa/SsaErLocal.kt)（新增文件）**
- `const val W = 60`；`erOf(closes, w)`：与 PC `_er_of` 完全同公式。
- `suspend fun corePoolPercentiles(ctx)`：`HistoricalDataFetcher.getCoreStockPool(ctx)`（**每日荐股核心池**，同 PC asset 池）→ Room `dailySnapshotDao().getByCode(code, 300)`（**日线**，DAO 按 date DESC 返回，代码内 `sortedBy{date}` 转升序）→ 过滤 `close>0` → 池内**中位秩**分位；池空或有效样本 <2 返回空表。本地读库、**不联网**。
- 旧的 `percentiles(ctx, codes)`（1H/`IntradayKlineFetcher`）版本已删除，避免与 PC 口径分叉。

**[SsaAlertStore.kt](file:///d:/Stock/StockAnalysis/app/src/main/java/com/chin/stockanalysis/strategy/ssa/SsaAlertStore.kt)**
- `OverlayRow` 末尾新增 `val erPct: Double? = null`；`saveRows`/`loadRows` 同步持久化（缺省 null）。其余调用方用具名参数，向后兼容。

---

## 3. 口径说明（**重要，易踩坑**）

| 口径 | PC 推送列 / `er_pct_map` | App 本机备选 `SsaErLocal` |
|------|--------------------------|---------------------------|
| K 线 | **日线**（`data/kline_store.json`，2008 起） | **日线**（Room `daily_snapshot`，需先跑过每日荐股全量构建） |
| 横截面池 | **每日荐股核心池**（`core_stock_pool.json`，去重 171 只；缺池退回全池） | **同一池**（`getCoreStockPool(ctx)`，约 171 只） |
| 输出 | 0~100 分位，≥70 标 `↑` | 同样 0~100 分位，同样 ≥70 标 `↑` |
| 池外票 | ER分位显示 `—` | 无（池外不入表） |
| 二者关系 | 同池同口径（K 线源不同：PC 用 kline_store，App 用本地库） | 降级备选；PC 恢复后自动改回 PC 口径 |

- ⚠️ **池来源可能漂移**：PC 读的是 **asset** `core_stock_pool.json`；设备读的是 `getPool()`（**SharedPreferences live 优先 → asset 兜底**，live 池每周迭代更新）。二者在日常使用中通常一致，若要严格同步需把 live 池回传 PC。
- UI 在 `erSourceLocal=true` 时显式标注 `🔧 本机备选：每日荐股池 …（日线 W=60 · 本机算）`；PC 源标注 `✅ PC 每日荐股池 $n 只`（`er_pct_pool="daily_reco"`）或 `全池 $n 只`（`="all"`）。
- 分位一律用**中位秩公式** `(严格小于数 + 0.5×相等数)/总数×100`，解决同值塌缩。
- 推送 JSON 顶层键：`groups`（四周期候选，各含 `secid/name/industry/ratio/pass/total/limit_up`）、`er_pct_map`、`er_pct_pool`（`daily_reco`/`all`）、`er_pct_pool_n`、`asof`、`generated_at`。

---

## 4. 之前报告 / 产物所在位置

### 4.1 本主题（ER 路由）相关

| 文件 | 说明 |
|------|------|
| [reports/er_router_backtest.html](file:///d:/Stock/StockAnalysis/reports/er_router_backtest.html) | **上一轮主报告**（HTML，含绩效表 + 净值 SVG + 敏感性 + 结论） |
| [reports/er_router_backtest.png](file:///d:/Stock/StockAnalysis/reports/er_router_backtest.png) | 报告概览图 |
| [app/build/er_router_summary.json](file:///d:/Stock/StockAnalysis/app/build/er_router_summary.json) | 回测明细数据（`portfolio`/`sweep`/`variants`/`eq_*` 逐 bar 净值） |
| [smalltools/_er_router_backtest.py](file:///d:/Stock/StockAnalysis/smalltools/_er_router_backtest.py) | **可重跑回测脚本**（文件头 docstring = 规则规格说明书） |
| [smalltools/_records/_er_router_cache.json](file:///d:/Stock/StockAnalysis/smalltools/_records/_er_router_cache.json) | 1H 行情缓存（100 只） |
| `.trae-html-share-packages/reports/er_router_backtest.html.zip` | 报告 HTML 分享包 |

### 4.2 更早的相关报告（同一 `reports/` 目录）

`regime_phase_report.html`（市场状态分期）、`wr_multistock_validation.html`（WR 多票验证）、
`600176_WR_threshold_report.html`、`600176_WR_smooth_period_report.html`、`600176_bbi12_wr_report.html`、
`BBI_cross_report.html`、`BBI12_pivot_report.html`。

### 4.3 做T/复盘表（PNG）

`data/review_tables/`（`ssa_*_600176_*.png` 等，为三层稳态做T/Ssa 参数复盘出图；另有 `ticket_advice_detail.csv`、`advice_601939.txt` 等）。

---

## 5. 上一轮回测结果说明（ER 路由 v1）

**回测区间**：2026-03-06 → 2026-10-16（1H，100 只票，末 900 根取 `[300,899]`，前 300 根做 ER 预热）
**信号层**：BBI = (MA3+MA6+MA12+MA24)/4 的 SMA(BBI,12) 拐点（谷底确认→买 / 峰顶→卖，成交价=确认根收盘，与 Kotlin `SsaBbiPivotEngine` 同口径）
**路由层**：每根 bar 只把资金**等权**分给「持仓中且 ER 处于横截面高位」的票，其余空仓等待；**决策用 `ER[i-1]`（已修正前视偏差）**

### 5.1 组合绩效（核心数字）

| 组合 | 收益% | 年化% | 最大回撤% | 夏普 | 平均持仓只数 |
|------|------:|------:|----------:|-----:|-----:|
| BBI12 全池等权（基线） | +9.57 | 16.49 | 6.95 | 1.12 | 43.4 |
| 按 ER 权重加权 | +14.22 | 24.86 | 5.98 | 1.44 | 43.4 |
| ER ≥ 横截面 **P60** | +21.66 | 38.72 | 7.97 | 1.95 | 17.6 |
| **ER ≥ 横截面 P70（推荐）** | **+28.97** | **52.92** | 7.39 | **2.32** | 13.3 |
| ER 最高 N=10 只 | +22.03 | 39.44 | 7.51 | 1.77 | 9.9 |
| 等权买入持有（BH） | −7.68 | — | — | — | 100 |

> 三段（各 200 根）收益：基线 `+0.4/+12.9/−2.8`；P70 `+11.0/+17.6/+0.0` → ER 选择**段段不劣于**全池。

### 5.2 参数敏感性（防过拟合）

- 分位阈值：P40 `10.2/1.07` · P50 `16.9/1.62` · P60 `21.7/1.95` · **P70 `29.0/2.32`** · P80 `25.0/1.82`
- top-N：N=5 `−2.3/−0.04`（过度集中，唯一失效点）· N=8 `23.4/1.77` · N=10 `22.0/1.77` · N=12 `11.4/1.03` · N=15 `14.2/1.33` · N=20 `12.7/1.27` · N=30 `7.6/0.86`
- 除 N=5 外，**N=8~30 与 P40~P80 全部优于全池基线 9.57%** → 非单点幸运。

### 5.3 已证伪的用法（逐票闸门）

| 变体 | 组合收益% | 最大回撤% | 夏普 | 持仓% |
|------|------:|------:|-----:|-----:|
| V0 基线 | +0.37 | 4.06 | 0.12 | 43.3 |
| V1 按票性 ER 给 0.25~1.0 权重 | +0.35 | 2.54 | 0.16 | 43.3 |
| V2 仅「趋势态」建仓 | +0.06 | 1.34 | 0.06 | 9.4 |
| V3 = V1+V2 | +0.02 | 0.83 | 0.03 | 9.4 |
| V4 = V3+趋势后期禁入 | −0.07 | 0.89 | −0.12 | 7.2 |

**原因**：BBI12 买点本身是「反转买点」，此时 ER 天然偏低；按「ER 高才买」逐票过滤会滤掉多数买点（持仓 43%→9%），收益归零。
**结论：有效用法 = 跨票选择，不是逐票择时。**（V1 只改善风险，不改变收益）

### 5.4 口径 / 局限（务必知悉）

- BBI12 与 Kotlin 引擎复现校验 **396/400 一致**（最大偏差 0.93%）。
- 样本为 **100 只存活大票 → 存在幸存者偏差**；仅**单一 11 个月区间**，非跨牛熊。
- 回测为 **1H 口径（100 只）**；PC 推送列/`er_pct_map`/App 备选改用**日线 · 每日荐股核心池（171 只）**口径（同参数 W=60）。线上池与回测池、K 线周期均不同，数值尺度不同，故推送列定位为「**仅排序/标注，不改买卖信号**」。

---

## 6. 复现 / 验证命令

```powershell
# PC 端语法校验（本机缺 requests 时可只做 py_compile）
python -m py_compile smalltools/_publish_candidates.py

# 重跑 ER 路由回测（--fetch 强制重抓 1H 行情）
python smalltools/_er_router_backtest.py
# 产物：app/build/er_router_summary.json + reports/er_router_backtest.html/.png

# App 编译验证
.\gradlew.bat :app:compileDebugKotlin
```

---

## 7. 后续优化方向（建议优先级）

1. **回测口径与线上对齐**：线上（PC 列 / `er_pct_map` / App 备选）已统一为「日线 · 每日荐股核心池（171 只）」，但 `_er_router_backtest.py` 仍是「1H · 100 只」。若要结论可直接指导线上，建议用**日线 + 171 池**重跑一遍回测（同一套 TOP/P70 逻辑），确认推荐闸门 P70 是否仍成立。
2. **PC asset 池 vs 设备 live 池漂移**：PC 读 `assets/core_stock_pool.json`，设备 `getPool()` 会优先用每周迭代的 SharedPreferences live 池。如需严格一致，可让守护脚本把 live 池回传 PC（或让 PC 也读 live 池快照）。
3. **推送 JSON 版本协商**：`er_pct_map` 缺失是「PC 版本旧」的判据；建议加 `schema_version` 字段，App 端据此给出精确提示。
4. **ER 分位与买卖信号联动**：当前仅排序/标注。若要把 `ER≥P70` 做闸门，**必须重跑回测**确认（§5.3 已证伪逐票闸门，勿直接套用）。
5. **跨区间/跨样本稳健性**：补 2018-2023 区间 + 全市场（含退市）样本，消除幸存者偏差。
6. **App 备选性能**：`SsaErLocal.corePoolPercentiles` 对 ~171 只逐只调 Room `getByCode`（本地读库、不联网，但仍串行），可改批量查询（`WHERE code IN (...)`）+ 内存缓存一次。
7. **预警/持仓池与 ER tab 数据一致**：`erMap`（tab）与 `OverlayRow.erPct`（预警）目前是两条独立路径，可考虑统一到一个 Store。

---

## 8. 待办 / 未验证项（交接须知）

- [ ] `SsaErLocal.corePoolPercentiles()` 尚未在真机跑通（需设备已跑过**每日荐股全量构建**、Room `daily_snapshot` 有池内日线），**仅通过编译验证**。
- [ ] PC 侧池口径改动已通过 Python 单测（池规模 171 / 池外票零泄漏 / 分位 0.3~99.7 / 缺池退回全池 671），但**未在真实 `data/kline_store.json`（688 只）上跑完整推送**确认新增第 22 列后的列宽与渲染。
- [ ] 报告 §5 的所有数字来自**既有产物**（`er_router_summary.json`），未在本轮重跑。

---

## 9. 参考锚点（快速定位）

- 项目约定：`AGENTS.md`（§3 设计口径、§2 轻量任务路由）
- 主线：`usecases/*.xml` + `usecase_pipeline.py`
- 单一数据源：`data/kline_store.json`（日线，688 只，2008 起）、`app/src/main/assets/`
- 守护进程状态：`powershell -File .codebuddy/skills/stock-daemon/daemon_status.ps1`
