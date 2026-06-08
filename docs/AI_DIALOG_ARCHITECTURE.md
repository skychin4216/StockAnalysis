# AI 对话架构

本模块记录 AI 股票对话系统的架构设计、实现方案和版本迭代。

## 版本历史

### v6.0 - 个股+板块+新闻综合分析 (2026-06-08)

**核心需求：**
- 用户输入个股名称/代码时，AI 自动综合分析
- 分析维度包括：板块前景、高低位、行情、溢价、活跃度、业绩、上下游供应链、新闻
- 无需用户明确要求分析这些维度，系统自动提供

**实现方案：**

#### 核心类
- `StockQueryEngine.kt` - 主入口，构建包含完整数据的 system prompt
- `buildEnrichedStockPrompt()` - 新增方法，整合个股+板块+新闻数据

#### 数据流程

用户输入（如"兆易创新最新价格"） ↓ StockQueryEngine.buildSystemPrompt() ↓ ├─ 1. 个股实时行情数据（原有 StockService） │ └─ MultiSourceStockRepository.getRealtime() │ ├─ 2. 所属板块查询（StockDatabaseManager） │ └─ dbManager.getSectorsByStock(stockCode) │ ├─ 3. 板块实时热度数据（EastMoneyHotSectorSource） │ ├─ industrySectors - 行业板块 │ ├─ conceptSectors - 概念板块 │ └─ 包含数据：涨跌幅、换手率、主力资金净流入、领涨股、热度评分 │ ├─ 4. 相关利好/利空新闻（NewsFactorManager） │ ├─ searchByStockCode() - 按股票代码搜索 │ ├─ searchByCompany() - 按公司名称搜索 │ └─ 包含数据：新闻标题、摘要、日期、情绪、影响强度 │ └─ 5. AI 分析指令（明确告诉 AI 分析什么） └─ 注入完整的 system prompt 给 AI


#### 注入的 System Prompt 结构

【个股实时行情数据】 {stockName} | {price} | {changePercent}% | ...

【所属板块】

{sector1}
{sector2} ...
【板块实时热度】

[行业/概念] {sectorName} 涨跌幅: {x.xx}% | 换手率: {x.xx}% 主力资金净流入: {x.xx}亿元 | 热度评分: {x.xx} 领涨股: {stockName} ({code}) {x.xx}% ...
【相关利好/利空新闻】 📈利好【{title}】{content}... ({date}, 影响强度:{score}) 📉利空【{title}】{content}... ({date}, 影响强度:{score}) ...

【AI 综合分析指令】 请基于以上数据，为用户提供综合分析：

个股当前走势分析（技术面）
板块前景分析（当前是低位/高位？整体行情如何？）
板块溢价情况和活跃度评估
业绩预期和上下游供应链分析
相关新闻对股价的潜在影响
最后给出一个综合的投资参考评估
⚠️ 注意：不要给出具体的买卖建议，只提供分析参考。投资有风险，入市需谨慎。


#### 关键实现点

1. **板块-股票映射查询**
    - 通过 `StockDatabaseManager.getSectorsByStock()` 获取股票所属板块
    - 模糊匹配板块名称：`hs.name.contains(sectorName) || sectorName.contains(hs.name)`

2. **板块实时热度数据**
    - 从 `EastMoneyHotSectorSource` 获取缓存的实时板块数据
    - 行业板块（`industrySectors`）+ 概念板块（`conceptSectors`）
    - 最多展示 3 个相关板块

3. **新闻数据整合**
    - 同时按股票代码和公司名称搜索新闻
    - 合并去重，最多展示 8 条新闻
    - 用 emoji 标识利好/利空/中性情绪

4. **AI 分析指令明确化**
    - 直接告诉 AI 需要分析的 6 个维度
    - 让 AI 自动综合判断，而不是让用户逐个要求

---

### v5.0 - 本地数据库 + 板块映射 (2026-06-07)

**核心改进：**
- 引入 Room 本地数据库（`StockDatabase`）
- 建立股票-板块映射关系
- 本地缓存股票基本信息

**核心类：**
- `StockDatabaseManager` - 数据库管理
- `StockDataCenter` - 数据中心

---

### v4.0 - 后台预取调度 (2026-06-06)

**核心改进：**
- 预测性缓存预热
- 学习用户高频查询的股票/主题
- 后台周期性拉取数据并缓存
- 缓存命中时毫秒级响应

**核心类：**
- `StockPrefetchScheduler` - 预取调度器
- `UserQueryHistory` - 查询历史

---

### v3.0 - 意图识别链

**核心改进：**
- 链式意图处理器
- 支持股票代码、名称、模糊匹配等多种识别方式

**核心类：**
- `IntentProcessorChain` - 意图处理链
- `FuzzyStockNameHandler` - 模糊股票名称处理器

---

### v2.0 - 多源数据仓储

**核心改进：**
- 整合东方财富、新浪、腾讯、聚宽、AKShare 等 5 个数据源
- 智能健康检查和数据源切换
- 本地缓存机制

**核心类：**
- `MultiSourceStockRepository` - 多源数据仓储

---

## 完整调用链



---

## 文件结构

stock/ ├── AI_DIALOG_ARCHITECTURE.md ★ 本文档：AI 对话架构 ├── StockQueryEngine.kt ★ 总入口：构建 system prompt │ └─ buildEnrichedStockPrompt() ★ v6.0 新增：个股+板块+新闻综合 ├── StockService.kt 具体股票查询 ├── StockContext.kt 查询上下文 ├── StockRealtime.kt 实时行情数据 ├── IntentRecognizer.kt 意图识别 ├── ProcessResult.kt 处理结果 ├── RemoteDataService.kt 远程数据服务 ├── analysis/ AI 智能分析模块 ├── data/ 多源数据仓储 + 缓存 + HTTP 客户端 ├── database/ ★ Room 本地数据库（v5.0） │ ├── StockDatabaseManager.kt 数据库管理 │ ├── StockDataCenter.kt 数据中心 │ └── StockDatabase.kt Room 数据库 ├── formatter/ 数据格式化 ├── intent/ 意图处理（链式处理器） │ ├── IntentProcessorChain.kt 处理链 │ ├── FuzzyStockNameHandler.kt 模糊匹配 │ └── ... ├── prefetch/ 后台预取调度（v4.0） │ ├── StockPrefetchScheduler.kt 预取调度器 │ └── UserQueryHistory.kt 查询历史 ├── realtime/ 实时数据处理 ├── autorefresh/ 自动刷新 └── theme/ 主题/板块识别 ├── ThemeStockService.kt 板块查询 ├── ThemeStockLibrary.kt 内置主题库 └── UserPreferenceManager.kt 用户偏好管理

../news/ ├── NewsFactorManager.kt ★ v6.0 整合：新闻利好/利空因子 ├── NewsFactorEntity.kt 新闻数据实体 └── HotSectorNewsUpdater.kt 热点板块新闻更新

../data/sources/ ├── EastMoneyHotSectorSource.kt ★ v6.0 整合：板块实时热度数据源 ├── EastMoneySectorSource.kt 板块数据源 ├── EastMoneyStockSource.kt 东方财富股票数据 ├── SinaStockSource.kt 新浪股票数据 ├── TencentStockSource.kt 腾讯股票数据 ├── JoinQuantsSource.kt 聚宽数据 └── AKShareSource.kt AKShare 数据



---

## 关键设计原则

1. **渐进增强**
    - 每个版本在前一版本基础上增加功能
    - 保持后向兼容，不破坏现有功能

2. **数据优先**
    - 尽可能多地获取相关数据
    - 让 AI 基于充分的数据做出判断

3. **自动化分析**
    - 不要让用户逐个要求分析维度
    - 系统自动提供完整的综合分析

4. **缓存优化**
    - 充分利用预取和本地缓存
    - 减少网络请求，提升响应速度

5. **明确的 AI 指令**
    - 注入的数据结构清晰
    - 告诉 AI 具体需要分析什么

---

## 未来优化方向

- [ ] v7.0: 引入 AI 驱动的板块-股票关联分析
- [ ] 增加更多新闻源，提升新闻时效性
- [ ] 支持自定义分析维度（让用户选择关注哪些方面）
- [ ] 历史对话中的数据关联（结合之前的分析）
- [ ] 可视化展示（K线图、板块轮动图等）




