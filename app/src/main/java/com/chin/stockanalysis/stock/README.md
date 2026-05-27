# Stock 模块架构

```
stock/
├── README.md                 ← 你在这里
├── StockQueryEngine.kt       ★ 总入口：构建注入实时数据的 system prompt
├── StockService.kt           旧版具体股票查询（代码/名称识别）
├── StockContext.kt           查询上下文数据类
├── StockRealtime.kt          实时行情数据类
├── IntentRecognizer.kt       意图识别（代码模式匹配）
├── ProcessResult.kt          处理结果数据类
├── RemoteDataService.kt      远程数据服务
├── analysis/                 AI 智能分析模块
├── data/                     多源数据仓储 + 缓存 + HTTP 客户端
├── database/                 ★ Room 本地数据库（v5.0 新增）
├── formatter/                数据格式化（构建 prompt 文本）
├── intent/                   意图处理（链式处理器）
├── prefetch/                 后台预取调度
├── realtime/                 实时数据处理
├── autorefresh/              ★ 自动刷新（TLL 架构）
└── theme/                    主题/板块识别 + 内置股票库 + 用户偏好
```

## 调用链

```
ChatTabFragment / ChatActivity
  └─ StockQueryEngine.buildSystemPrompt()
       ├─ UserPreferenceManager (theme/)      偏好学习
       ├─ ThemeStockService (theme/)          主题/板块识别 → 东方财富API + ThemeStockLibrary
       ├─ StockService + StockFormatter       具体股票查询 → MultiSourceStockRepository
       ├─ StockDatabaseManager (database/)    本地 SQLite 查询
       ├─ StockAutoRefreshManager (autorefresh/) ★ TLL 自动刷新
       └─ StockPrefetchScheduler (prefetch/)  后台预热缓存
