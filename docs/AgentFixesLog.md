# Agent Framework 编译错误修复日志

## 错误分类

| 类别 | 根本原因 | 影响文件数 |
|------|---------|-----------|
| **AgentContext vs Context 类型混淆** | `StockDatabase.getInstance(ctx)` 中 `ctx` 是 `AgentContext` 而非 `android.content.Context`，每个 Tool 类已有 `ctx: Context` 字段应使用 `this.ctx` | ChatAgent, NewsMonitoringAgent, RiskManagementAgent, StockAnalysisAgent, StockPickingAgent, TradeExecutionAgent |
| **实体字段名错误** | AI 生成的代码使用了不存在的字段名 | 全部 Agent 文件 |
| **不存在的 DAO 方法** | 假定了不存在的数据库方法 | 全部 Agent 文件 |
| **框架 API 错误** | `resumeWithException` 不存在于 `CancellableContinuation` | AgentBase.kt |
| **作用域问题** | `parsePlan()` 中的 `goal` 参数不在作用域内 | AgentBase.kt |
| **过时引用** | `MultiAgentOrchestrator` 引用不存在的 `key` | MultiAgentOrchestrator.kt |
| **遗留代码** | `PipelineProgressView` 引用 `STEPS`、`ShortTermQuantFragment` 调用 `updateSteps` | PipelineProgressView.kt, ShortTermQuantFragment.kt |
| **API 不匹配** | `AnalysisRouter` 引用 `StockAnalyzerService` 中不存在的属性 | AnalysisRouter.kt |

## 实体字段映射表

| AI 生成字段 | 实际字段 | 实体 |
|-----------|---------|------|
| `stockName` | `name` | DailySnapshotEntity |
| `stockCode` | `code` | DailySnapshotEntity |
| `mainBusiness` | `business` | StockBasicEntity |
| `chainLogic` | `chainRationale` | StockBasicEntity |
| `turnover` | `turnoverRate` | DailySnapshotEntity |

## 数据库方法映射表

| AI 生成方法 | 实际等价方法或处理方式 |
|-----------|-------------------|
| `getLatestByCode(code)` | `getByDateAndCode(today, code)` |
| `getRecentByCode(code)` | `getByDateAndCode(today, code)` |
| `getActiveByStockCode(code)` | 不存在，替换为 `getByDateAndCode` |
| `getActiveOrders()` | `getRecent(200).filter { it.status in listOf("BUYING","PENDING") }` |
| `getSectorsByStockCode(code)` | `getSectorNamesByStockCode(code)` |
| `getTopSectorsByDate(date)` | 不存在，简化/注释待实现 |
| `getByDateAndSector(date, sector)` | 不存在，简化/注释待实现 |
| `getStocksBySector(sector)` | `getStockCodesBySector(sector)` |
| `searchByKeyword(keyword)` | `searchByName(keyword)` |
| `getLatestActive(code)` | 不存在，简化/注释待实现 |
| `updateStatusByCode(code, status)` | 不存在，使用 `updateSellInfo` 或直接操作 |
| `getActiveByCode(code)` | 不存在，替换为 `getRecent` |

## 各文件修复清单

### ✅ ChatAgent.kt — 已修复
- `s.stockName` → `s.name`
- `s.stockCode` → `s.code`
- `StockDatabase.getInstance(ctx)` → `StockDatabase.getInstance(this.ctx)` (Tool 类)
- `getLatestByCode(code)` → `getByDateAndCode(today, code)`
- `it.mainBusiness` → `it.business`
- `it.turnover` → `it.turnoverRate`
- `listOf()` → `listOf<String>()` (类型推断)

### ✅ AgentBase.kt — 已修复
- `cont.resumeWithException(...)` → `cont.resumeWith(Result.failure(...))`

### 🔲 AgentBase.kt — 待修复
- `parsePlan()` 中的 `goal` → 添加 `fallbackGoal` 默认参数

### 🔲 MultiAgentOrchestrator.kt — 待修复
- 第 63 行 `.key` 引用错误

### 🔲 其余所有 Agent 文件 — 批量修复
- 一致的 `AgentContext` → `Context` 类型修正
- 一致的字段名修正
- 一致的 DAO 方法修正