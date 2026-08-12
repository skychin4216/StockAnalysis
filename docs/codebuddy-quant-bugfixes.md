# codebuddy - 量化交易 Bug 修复清单（P0/P1/P2）

> 文档生成时间：2026-08-11
> 状态：**全部 13 项已完成，7 个文件已修改并通过 lint 校验**

本文档记录针对量化交易模块的 13 项 Bug 修复，按严重程度分为 P0（确定性误操作/数据错误）、P1（逻辑矛盾/统计错误）、P2（死代码/冗余/风险）。

---

## 一、P0 级（确定性误操作 / 数据错误）

### P0-1 自动卖出误卖所有周期
- **文件**：`app/src/main/java/com/chin/stockanalysis/strategy/trade/QuantFragmentBase.kt`
- **问题**：`executeAutoSell` 重新执行 `evaluateAll()` 时未按周期过滤，会把**所有周期**的持仓一起评估，导致误卖不属于当前策略周期的持仓。
- **修复**：改为直接使用已按周期过滤的缓存 `holdingToSell`（其内已含 `shouldSell` 过滤），不再重新全局评估。

### P0-2 清仓使用日期删除导致误删
- **文件**：`QuantFragmentBase.kt`、`TradeModels.kt`
- **问题**：`confirmAndClearPositions` 使用 `deleteByDate` 删除，可能误删同一天的其他订单。
- **修复**：
  - `TradeModels.kt` 新增 DAO 方法 `deleteById(id)` 与 `updateReason(id, reason)`；
  - `confirmAndClearPositions` 改为逐条 `deleteById(order.id)` 删除。

### P0-3 实时行情覆盖 OCR 持仓成本价
- **文件**：`app/src/main/java/com/chin/stockanalysis/strategy/trade/RealHoldingQuantFragment.kt`
- **问题**：`enrichWithRealtimeData` 中 `avgBuyPrice = rt.price` 用 API 现价覆盖了 OCR 截图的**持仓成本价**，导致后续盈亏统计恒为 0（把成本当现价）。
- **修复**：移除 `avgBuyPrice` 覆盖逻辑，`copy` 仅更新 `currentPrice / pe / turnoverRate / stockName`，保留 OCR 真实成本价。

### P0-4 阶梯止盈重复减仓
- **文件**：`app/src/main/java/com/chin/stockanalysis/strategy/trade/AutoSellEngine.kt`
- **问题**：多个止盈档位触发条件重叠（如 10% 与 15% 档在价格继续上行时都被触发），导致同一仓位被**多次减仓**。
- **修复**：
  - 在 `evaluatePosition` 中加入 `takenTpTiers(snap.order.reason)` 检查，已减仓档位跳过；
  - 部分卖出后通过 `updateReason` 在 reason 追加 `[TP_TIER_n]` 标记；
  - 新增辅助函数 `takenTpTiers(reason: String): Set<Int>` 解析已减档位。

---

## 二、P1 级（逻辑矛盾 / 统计错误）

### P1-5 胜率统计方向错误
- **文件**：`app/src/main/java/com/chin/stockanalysis/strategy/trade/TTradeEngine.kt`
- **问题**：原 `executedPrice > 0` 仅判断"是否已成交"，无法反映真实盈亏方向。
- **修复**：新增 `isExecutedProfitable()` 按方向判定盈利：
  - `T_BUY / T_SELL`：执行价 > 建议价 → 盈利；
  - `RT_SELL / RT_BUY`：执行价 < 建议价 → 盈利。
  - 应用于 `getDailySummary` / `getDailyAllPeriodSummary` 的胜率统计。

### P1-6 配对腿生成孤儿 OPEN 记录
- **文件**：`TTradeEngine.kt`
- **问题**：配对腿（T_SELL / RT_BUY）找不到对应开仓腿时仍落库 OPEN 记录，形成**永久未平仓孤儿**。
- **修复**：`executeTTrade` 中配对腿找不到开仓腿时直接 `return 0L`，不再落库。

### P1-7 板块弱势数据源不真实
- **文件**：`AutoSellEngine.kt`
- **问题**：`getSectorChangePct` 返回的数据与真实板块涨跌不符。
- **修复**：改为通过 `sectorStockDao().getSectorNamesByStockCode(code)` 映射股票→板块，再经 `sectorDailyRecordDao().getByDate(date)` 读取真实板块涨跌，取**最差（最小）板块跌幅**作为保守判断。

### P1-8 文档注释与实现不符
- **文件**：`AutoSellEngine.kt`
- **问题**：类注释写"时间强平"，与实际逻辑（≥10 天 + 近 3 日动量 < 1%）不符。
- **修复**：文档对齐实现，改为"持仓 ≥ 10天 且 近3日动量 < 1% → 卖出（死钱换股）"。

### P1-9 周期分类边界与配置不符
- **文件**：`RealHoldingQuantFragment.kt`
- **问题**：`classifyPeriod` 边界与 `HoldingPeriod.holdingDays` 不一致。
- **修复**：对齐为：
  - `daysHeld <= 1` → `ULTRA_SHORT`
  - `daysHeld <= 29` → `SHORT`
  - `daysHeld <= 180` → `MID`
  - `else` → `LONG`

---

## 三、P2 级（死代码 / 冗余 / 风险）

### P2-10 清理重复冗余条件
- **文件**：`AutoSellEngine.kt`
- **问题**：`extractStrategyFromReason` 中存在重复的 `||` 条件。
- **修复**：清除重复条件。

### P2-11 选股数据源大小写不一致
- **文件**：`QuantFragmentBase.kt`
- **问题**：选股保存用小写 source（`getWatchlistSource()`），部分查询却用 camelCase（`getQuantType()`），导致查询不到数据。
- **修复**：`runAutoSellEvaluation` 与 `checkFundamentalHealth` 统一改为 `getBySource(getWatchlistSource())`。

### P2-12 买入价为 0 导致除零 Infinity
- **文件**：`UltraShortQuantFragment.kt`、`HardcodeCompatNodes.kt`
- **问题**：`buyPrice <= 0` 时计算盈亏会产生 Infinity，误判止盈。
- **修复**：`checkT1AutoSell` 与 `T1AutoSellNode` 均加入 `if (order.buyPrice <= 0) continue` 跳过。

### P2-13 做T目标触及与盈亏结算不完整
- **文件**：`TTradeEngine.kt`
- **问题**：`trackOutcomeForRecommendations` 与 `markDayEnd` 未覆盖配对腿 T_SELL / RT_BUY 的目标触及判定与虚拟盈亏结算。
- **修复**：
  - `trackOutcomeForRecommendations` 补充 T_SELL（价 ≥ 目标）与 RT_BUY（价 ≤ 目标）的触及判定；
  - `markDayEnd` 补充配对腿的方向盈亏计算。

---

## 四、修改文件汇总

| 文件 | 涉及修复项 |
| --- | --- |
| `strategy/trade/AutoSellEngine.kt` | P0-4, P1-7, P1-8, P2-10 |
| `strategy/trade/QuantFragmentBase.kt` | P0-1, P0-2, P2-11 |
| `strategy/trade/TradeModels.kt` | P0-2 |
| `strategy/trade/RealHoldingQuantFragment.kt` | P0-3, P1-9 |
| `strategy/trade/TTradeEngine.kt` | P1-5, P1-6, P2-13 |
| `strategy/trade/UltraShortQuantFragment.kt` | P2-12 |
| `strategy/topology/nodes/HardcodeCompatNodes.kt` | P2-12 |

---

## 五、完成状态核对

所有 13 项修改均已落实到代码并可通过 lint 校验：

- [x] P0-1 自动卖出改为使用已按周期过滤的缓存 `holdingToSell`
- [x] P0-2 新增 `deleteById` / `updateReason` DAO，清仓逐条删除
- [x] P0-3 `enrichWithRealtimeData` 保留 OCR 成本价（已验证 copy 不再含 `avgBuyPrice = rt.price`）
- [x] P0-4 阶梯止盈档位标记 `[TP_TIER_n]` + `takenTpTiers()` 防重复减仓
- [x] P1-5 新增 `isExecutedProfitable()` 方向感知胜率统计
- [x] P1-6 配对腿找不到开仓腿时 `return 0L`，不落孤儿 OPEN
- [x] P1-7 `getSectorChangePct` 映射真实板块涨跌（最差板块）
- [x] P1-8 类注释对齐实现（≥10 天 + 近 3 日动量 < 1%）
- [x] P1-9 `classifyPeriod` 边界对齐 `HoldingPeriod.holdingDays`
- [x] P2-10 清理重复冗余条件
- [x] P2-11 统一 `getWatchlistSource()`（小写）数据源
- [x] P2-12 `buyPrice <= 0` 防除零（两处）
- [x] P2-13 配对腿 T_SELL / RT_BUY 目标触及与虚拟盈亏结算

---

## 六、UI 完善计划（2026-08-12）

> 背景：已完成 Pipeline 拓扑编辑器（`TopologyEditorActivity` / `NodeCanvasView`）的沉浸式全屏重构，
> 修复了"点击策略 → 各周期 pipeline 显示空白"的根因（`openPipelineEditor()` 传参由 `getQuantType()` 改为 `getDefaultUseCaseId()`）。
>
> 本节为**其余 UI 面板**的体检结论与修改计划，按严重程度分为 P0 / P1 / P2，未实施项待后续逐步落地。

### P0 级（功能缺失 / 用户看到假数据）

#### UI-P0-1 股票列表全部为写死的示例数据
- **文件**：`ui/StockListFragment.kt`
- **位置**：`L20-21`（注释"使用示例数据演示布局"）、`L78-84`（6 个 Type 分支全部返回 sample 数据）、`L95-170+`（`aShareSampleData()` 等 6 组假数据）
- **问题**：A股/ETF/热门/涨幅榜/跌幅榜/主线共 6 个 Tab 展示的**全是静态假行情**，用户无法获得真实市场数据，与"股票"页定位严重不符。
- **建议**：接入 `MultiSourceStockRepository` / `StockQueryEngine` 真实行情源；列表页保留骨架屏/加载态；各 Tab 对应真实接口（涨跌幅榜、板块主线等）；失败时显示重试提示。
- **工作量**：中（涉及数据层调用 + UI 状态接入）

#### UI-P0-2 股票搜索在示例数据中检索
- **文件**：`ui/StockBrowserFragment.kt`
- **位置**：`L242-264` `buildAllStockList()` 聚合的仍是示例数据；`L214` `filterStocks()` 只在此假集合中过滤
- **问题**：用户搜索真实股票代码/名称（如"600000"、"宁德"）大概率搜不到；搜索结果是假数据，误导决策。
- **建议**：搜索改走真实数据源（`StockNameTrie` / 东方财富搜索接口）；空结果显示"未找到相关股票"占位；搜索防抖 + loading 指示。
- **工作量**：中

### P1 级（交互残缺 / 显示误导）

#### UI-P1-3 个股详情"加仓 / 减仓 / 清仓"按钮是占位
- **文件**：`ui/StockDetailFragment.kt`
- **位置**：`L1342-1366`（加仓只弹 Toast"请在建仓流程中加仓"；减仓/清仓弹"功能开发中"）
- **问题**：按钮存在但无实际功能，点击后仅 Toast，体验断裂。
- **建议**：若暂不做真实交易，改为跳转对应周期的量化页（传入股票代码，走 `openPipelineEditor` 或持仓导入）；或移除按钮改为纯展示；文案至少改为可操作的引导。
- **工作量**：小-中

#### UI-P1-4 "拟合调优"按钮在长线/超短线是纯提示占位
- **文件**：`strategy/trade/LongTermQuantFragment.kt` `L43-55`、`UltraShortQuantFragment.kt` `L72-81`
- **问题**：两个子类 `onFittingClick()` 仅弹固定文本对话框，而短线/中线子类调用 `showFittingParamsReport()` 真正执行 `autoFit` 并输出参数报告，功能不一致。
- **建议**：长线/超短线也接入真实拟合流程（可复用 `StrategyFittingEngine`，只是参数集不同）；或如确为参数固定，把按钮改为"参数说明"并保持文案清晰。
- **工作量**：小-中

#### UI-P1-5 策略准确率表格"均收益"恒为 0
- **文件**：`ui/StrategyStatsFragment.kt`
- **位置**：`L149` `avgReturn = 0.0  // getAccuracyStats() 不含 avgReturn，后续扩展`
- **问题**：表格显示"均收益"列但数据恒为 0%，误导用户以为策略无收益。
- **建议**：由 `strategyPredictionDao()` 按 `strategy_id` 聚合真实收益（复用 `L197-222` 已实现的历史收益计算逻辑）；短期不可行则隐藏该列。
- **工作量**：小-中

#### UI-P1-6 策略详情 BottomSheet 存在死代码入口
- **文件**：`ui/StrategyDetailFragment.kt`
- **位置**：`L247-253` `newInstance(strategyId)` 写入 `strategy_id` 参数但从未读取；`L40` `val s = strategy ?: return root` 直接返回空面板无提示
- **问题**：若任何入口改用 `newInstance` 会得到**空白弹窗**且无任何提示；当前调用方传对象所以未暴露。
- **建议**：删除 `newInstance` 死代码，或将 `onCreateView` 增加空策略兜底提示；`strategy == null` 时显示"策略数据缺失"。
- **工作量**：小

#### UI-P1-7 一键评估串行弹出多个 Dialog 叠加
- **文件**：`strategy/trade/QuantFragmentBase.kt`
- **位置**：`L778-813` `runAllEvaluations()`
- **问题**：串行调用 `showTTradeMenu / runAutoSellEvaluation / showBuyEvaluation / checkFundamentalHealth`，每个都会 `show()` 新 Dialog，前一个未 dismiss 即弹下一个，造成**多层 Dialog 叠加**、返回键需按多次。
- **建议**：改为"单 Dialog 内分步执行 + 结果按序追加到同一对话框/结果区"；或执行完一个再 dismiss 后弹下一个。
- **工作量**：小

#### UI-P1-8 精选页切换依赖脆弱 Tag 查找
- **文件**：`ui/StockTabFragment.kt`
- **位置**：`L106-108` `findFragmentByTag("f1")`
- **问题**：依赖 ViewPager2 内部固定 tag `"f1"` 定位子 Fragment，版本/布局调整后可能静默失效；`switchToInstitutional()` 在子页未创建时取 null 无兜底。
- **建议**：改为遍历 `childFragmentManager.fragments.firstOrNull { it is WatchlistUnifiedFragment }`（与 `MainActivity` 中做法一致）。
- **工作量**：极小

#### UI-P1-9 底部搜索面板硬编码 82% 屏幕高度
- **文件**：`ui/StockBrowserFragment.kt`
- **位置**：`L59` `(resources.displayMetrics.heightPixels * 0.82).toInt()`
- **问题**：横屏/分屏/平板下布局异常；BottomSheet 应使用 `peekHeight` 或内容自适应。
- **建议**：改用 `BottomSheetBehavior` 状态或 `WRAP_CONTENT` + 最大高度约束（`0.82` 系数仅作上限）。
- **工作量**：小

### P2 级（健壮性 / 一致性 / 遗留清理）

#### UI-P2-10 持仓刷新协程静默吞异常
- **文件**：`strategy/trade/QuantFragmentBase.kt` `refreshPositions()`
- **位置**：约 `L3513-3601`，整体 `catch (_: Exception) {}` 且内部多处 `requireContext()` 未先判 `isAdded`
- **问题**：Fragment detach 后刷新静默失败、UI 无任何反馈。
- **建议**：异常打印日志 + 主线程 Toast 提示；`requireContext()` 前统一 `if (!isAdded) return`。
- **工作量**：小

#### UI-P2-11 持仓表格固定列宽，窄屏靠横向滚动
- **文件**：`strategy/trade/QuantFragmentBase.kt`（约 `L3828/3888` 持仓表格）
- **问题**：列宽固定（60/45/72/50dp），价格列多时超出屏宽，窄屏体验一般。
- **建议**：列宽改 `weight` 自适应 + 关键列（股票名/盈亏）优先显示；或默认隐藏次要列。
- **工作量**：小

#### UI-P2-12 空数据 / 失败无提示
- **文件**：`ui/StockListFragment.kt`、`ui/StockBrowserFragment.kt`、`ui/StrategyStatsFragment.kt`
- **问题**：列表为空、搜索无结果、统计无数据时页面空白无提示。
- **建议**：统一空状态视图（"暂无数据" + 图标 + 重试按钮）。
- **工作量**：小

#### UI-P2-13 买卖评估菜单各周期不一致
- **文件**：`QuantFragmentBase.kt`（`L727-751`）vs `UltraShortQuantFragment.kt`（`L182-226`）
- **问题**：超短线菜单多"⚡ T+1 卖出检查 / 📊 卖出绩效"两项，其他周期菜单不同，用户切换周期后菜单跳变。
- **建议**：基类提供统一菜单项 + 子类按需增删的扩展点，保持结构一致。
- **工作量**：小

#### UI-P2-14 主 Activity 遗留问题
- **文件**：`ui/MainActivity.kt`
- **位置**：`L274-286` `onBackPressed()` 使用已废弃 API（新版 Android predictive back 可能不触发）；`L137-158` `initBackupSystem()` 若用户一直未选备份目录，**每次启动都弹备份引导框**
- **建议**：`onBackPressed` 迁移到 `OnBackPressedCallback`；备份引导改为仅首次启动弹一次（SharedPreferences 标记）。
- **工作量**：小

---

### 实施顺序建议

1. **第一批（P0，数据真实性）**：UI-P0-1、UI-P0-2 — 股票列表与搜索接入真实行情，直接影响用户信任。
2. **第二批（P1，交互闭环）**：UI-P1-3 至 UI-P1-9 — 按钮功能闭环、统计真实化、对话框/切换健壮性。
3. **第三批（P2，打磨）**：UI-P2-10 至 UI-P2-14 — 异常反馈、空态、菜单一致性、遗留 API 清理。

### 完成状态核对（2026-08-12 已全部实施）

- [x] 拓扑编辑器全屏重构（`TopologyEditorActivity` / `NodeCanvasView`）+ pipeline 空白根因修复
- [x] UI-P0-1 股票列表接入真实行情数据（`StockListFragment`：stock_basics + 批量实时行情 + daily_snapshot 排序 + 热门板块）
- [x] UI-P0-2 股票搜索接入真实数据源（`StockBrowserFragment`：`StockDataCenter.searchStocks` + 实时行情）
- [x] UI-P1-3 个股详情交易按钮功能闭环（加仓/减仓/清仓 → 跳转 AI 对话指令）
- [x] UI-P1-4 长线/超短线拟合调优接入真实流程（复用 `showFittingParamsReport`）
- [x] UI-P1-5 策略准确率"均收益"真实化（按策略聚合 `actualNextDayPct`）
- [x] UI-P1-6 删除策略详情死代码 / 空态兜底
- [x] UI-P1-7 一键评估 Dialog 叠加修复（统一 `activeEvalDialog` 单例 + 弹新关旧）
- [x] UI-P1-8 精选页 Fragment Tag 查找加固（遍历 `childFragmentManager.fragments`）
- [x] UI-P1-9 搜索面板高度自适应（`BottomSheetBehavior` + `skipCollapsed` + `STATE_EXPANDED`）
- [x] UI-P2-10 持仓刷新异常可见化（日志 + Toast）
- [x] UI-P2-11 持仓表格列宽自适应（股票列 60→84dp，日期列 72→60dp）
- [x] UI-P2-12 空态统一（股票列表/搜索/统计页均有"暂无数据"提示）
- [x] UI-P2-13 买卖评估菜单统一（基类 `getExtraEvalMenuItems` 扩展点，子类不再重复实现）
- [x] UI-P2-14 MainActivity 遗留清理（`onBackPressed` → `OnBackPressedCallback`；备份引导仅首次弹窗）
