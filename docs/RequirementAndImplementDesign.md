# 📋 需求与实现计划（v4.0）

## 1. 项目目标

构建一个 A 股智能分析 Android App，支持：
1. AI 聊天（多厂商 API，流式输出）
2. 主题/板块查询（自动注入实时数据，输出结构化表格）
3. 具体股票实时行情查询
4. 五档盘口低吸分析
5. 用户偏好记忆（持久化过滤条件）

---

## 2. 版本计划

### v1.0 — 基础 AI 聊天（已完成）
- [x] ChatActivity + ChatAdapter + Message.kt
- [x] ApiProvider 接口 + OpenAiCompatibleProvider 通用实现
- [x] ApiConfigManager（多提供商 SharedPreferences 持久化）
- [x] DeepSeek / 硅基流动 / 豆包 / 阿里云 API 支持
- [x] SSE 流式输出

### v2.0 — 多 API + 股票行情（已完成）
- [x] 5源并发：新浪 / 腾讯 / 东方财富 / 聚宽 / AKShare
- [x] SmartStockCache（交易时段感知 TTL）
- [x] MultiSourceStockRepository（并发竞速）
- [x] StockDataSourceFactory（优先级工厂）
- [x] IntentProcessorChain（职责链意图识别）
- [x] StockService（业务门面）

### v3.0 — ChatTabFragment 重构（已完成）
- [x] ChatTabFragment 替代 ChatActivity 作为主界面
- [x] Fragment 生命周期安全处理
- [x] IntentRecognizer 新流程对比测试

### v4.0 — 主题/板块查询 + 可扩展引擎（已完成）

### v5.0 — 量化策略 + 自动刷新 + 智能Skill（2026-05-27 完成）
- [x] 修复多股票查询价格错误（StockNameHandler + FuzzyStockNameHandler 多股票拆分）
- [x] TLL 架构自动刷新（stock/autorefresh/）
- [x] androidTest 自动化测试（16个用例）
- [x] 量化选股策略框架（strategy/ — 3种策略 + 策略引擎）
- [x] 智能追问 + 个人Skill引擎（skill/ — FollowUpGenerator + SkillEngine）

#### 新增文件

| 文件 | 路径 | 功能 |
|------|------|------|
| `StockQueryEngine.kt` | `stock/` | **统一查询引擎**，ChatTabFragment + ChatActivity 共用 |
| `ThemeStockLibrary.kt` | `stock/theme/` | 内置主题库（商业航天/有色金属/AI/军工/半导体等10大主题） |
| `ThemeStockService.kt` | `stock/theme/` | 主题整合层（方案A+B + 盘口数据） |
| `UserPreferenceManager.kt` | `stock/theme/` | 用户偏好持久化（剔除科创板/市值过滤等） |
| `EastMoneySectorSource.kt` | `stock/data/sources/` | 东方财富板块成分股 API（40+行业概念板块） |
| `EastMoneyBidAskSource.kt` | `stock/data/sources/` | 东方财富五档盘口 API（买卖比 + 低吸评级） |

#### 修改文件

| 文件 | 改动 |
|------|------|
| `ChatTabFragment.kt` | 删除 `initStockService`/`buildSystemPromptWithStockData`，改用 `StockQueryEngine` |
| `ChatActivity.kt` | 同上，删除重复字段，改用 `StockQueryEngine` |

#### StockQueryEngine 调用链

```
ChatTabFragment / ChatActivity
  └─ StockQueryEngine.buildSystemPrompt(userText, basePrompt)
       │
       ├─ [阶段0] UserPreferenceManager.learnFromMessage()
       │    └─ SharedPreferences（剔除科创板/市值/价格区间偏好持久化）
       │
       ├─ [阶段1] ThemeStockService.processThemeQuery()
       │    │    识别"商业航天/有色金属/化工/半导体"等关键词
       │    ├─ 方案A: ThemeStockLibrary（内置10大主题库）
       │    │    └─ MultiSourceStockRepository.getRealtime()
       │    ├─ 方案B: EastMoneySectorSource.fetchByName()
       │    │    └─ 东方财富板块成分股 API
       │    └─ EastMoneyBidAskSource.fetchBidAsk()（含"买手/卖手"时触发）
       │         └─ 五档盘口 → 买卖比 → 低吸评级
       │
       ├─ [阶段2] StockService.processUserInput()
       │    │    识别股票代码（600519）或名称（贵州茅台）
       │    ├─ IntentProcessorChain.process()
       │    │    ├─ StockNameHandler
       │    │    ├─ FuzzyStockNameHandler
       │    │    └─ GeneralStockHandler
       │    ├─ MultiSourceStockRepository.getRealtime()
       │    │    ├─ SinaStockSource（新浪）
       │    │    ├─ TencentStockSource（腾讯）
       │    │    ├─ EastMoneyStockSource（东方财富）
       │    │    ├─ AKShareSource（AKShare Python服务）
       │    │    └─ JoinQuantsSource（聚宽）
       │    └─ StockDataFormatter.format()
       │
       └─ [阶段3] 通用回答（AI 基于训练知识，无实时数据）
```

---

## 3. AI 输出格式规范（v4.0 新增）

### 主题/板块查询输出格式

当用户输入如 `帮忙分析化工前20的股票` 时，AI 应按如下格式输出：

```
化工行业前 20 龙头股（2026.05.21）
说明：按总市值 + 行业地位 + 业绩确定性综合排序；"尾盘低吸参考" 基于今日盘口与形态，仅作短线参考，非投资建议。

| 排名 | 股票代码 | 股票名称 | 核心赛道 | 市值（亿） | 核心依据（龙头逻辑） | 尾盘低吸参考 |
|------|---------|---------|---------|----------|------------------|------------|
| 1    | 600309  | 万华化学 | 聚氨酯/新材料 | 2620 | 全球 MDI 龙头（市占35%+），技术壁垒强 | 🟡 买卖比1.3 低吸观察 |
| 2    | 600028  | 中国石化 | 石油化工综合 | 4890 | 全国最大炼化企业，成品油终端定价权 | ⚪ 均衡观望 |
...

投资主线总结：
1. ...
2. ...

⚠️ 投资有风险，入市需谨慎，以上均为信息参考，不构成投资建议。
```

### 尾盘低吸评级规则（买卖比）

| 评级 | 条件 | 含义 |
|------|------|------|
| 🟢 强烈低吸 | 买卖比 ≥ 1.5 | 买盘明显主导 |
| 🟡 低吸观察 | 买卖比 1.2~1.5 | 轻微买盘优势 |
| ⚪ 均衡观望 | 买卖比 0.8~1.2 | 多空平衡 |
| 🔴 暂不介入 | 买卖比 < 0.8 | 卖盘偏多 |

---

## 4. 用户偏好记忆（v4.0 新增）

### 自动学习的偏好类型

| 偏好类型 | 触发关键词示例 | 持久化 Key |
|---------|-------------|-----------|
| 剔除科创板 | "剔除科创板"、"排除科创" | `exclude_exchange_kcb` |
| 剔除创业板 | "剔除创业板"、"排除创业" | `exclude_exchange_cyb` |
| 最低市值 | "200亿以上"、"500亿以下过滤" | `min_market_cap` |
| 价格区间 | "50元以下"、"10-50元" | `price_min` / `price_max` |

### 使用方式
- 偏好一旦学到，持久化到 SharedPreferences，App 重启后继续有效
- 菜单 → "已记忆的偏好" 可查看当前生效的偏好摘要
- 菜单 → "清除偏好记忆" 可一键清除所有偏好

---

## 5. 扩展新主题的方式

在 `ThemeStockLibrary.kt` 的 `THEMES` 列表中追加：

```kotlin
ThemeInfo(
    name = "储能",
    keywords = listOf("储能", "锂电池", "电化学储能"),
    validStocks = { listOf(
        ThemeStock("300750", "宁德时代", "锂离子电池", "全球锂电龙头，市占35%+"),
        ThemeStock("002594", "比亚迪", "动力电池+整车", "电池+整车垂直整合，全球销量第一"),
        // ...
    )}
)
```

---

## 6. API Key 配置

### 支持的 AI 提供商

| ID | 名称 | Base URL | Model | 费用 |
|----|------|----------|-------|------|
| `deepseek-official` | DeepSeek 官方 | `https://api.deepseek.com/v1/chat/completions` | `deepseek-chat` | 付费（新用户赠 500万 tokens） |
| `siliconflow` | 硅基流动 V2.5 | `https://api.siliconflow.cn/v1/chat/completions` | `deepseek-ai/DeepSeek-V2.5` | ✅ 免费 |
| `siliconflow-v3` | 硅基流动 V3 | `https://api.siliconflow.cn/v1/chat/completions` | `Pro/deepseek-ai/DeepSeek-V3` | ✅ 免费 |
| `doubao` | 豆包 | `https://ark.cn-beijing.volces.com/api/v3/chat/completions` | `ep-xxx` | 按量付费 |

> 扩展新提供商：在 `ApiConfigManager.kt` 的 `builtInProviders` 列表添加一项即可，无需修改其他代码。

---

## 7. v4.1 — 对话历史 + 取消请求 + 旧数据迁移（已完成）

### 需求背景
1. 发送对话后点击 Home 键，对话应停止
2. 需要在左侧 ≡ 菜单里保存历史对话（参考 DeepSeek / 豆包）
3. 安装新版本时查询手机特定路径下是否有旧版对话记录和配置信息

### 新增文件

| 文件 | 路径 | 功能 |
|------|------|------|
| `ConversationEntity.kt` | `conversation/` | Room 实体：conversations 表（id, title, subtitle, timestamp, messagesJson） |
| `ConversationDao.kt` | `conversation/` | Room DAO：增删查 + Flow 实时观察 |
| `ConversationRepository.kt` | `conversation/` | 对话持久化仓库（DB 读写 + JSON 序列化/反序列化 + 外部路径迁移导入） |

### 修改文件

| 文件 | 改动 |
|------|------|
| `ApiProvider.kt` | 新增 `cancel()` 接口方法，用于取消进行中的流式请求 |
| `OpenAiCompatibleProvider.kt` | 使用 `AtomicReference<Call>` 持有 OkHttp 请求引用；实现 `cancel()` 取消 HTTP 调用；`onFailure`/`onResponse` 检测 `call.isCanceled()` |
| `ChatTabFragment.kt` | ① `onPause()`/`onStop()` 调用 `cancelApiCall()` 取消协程 Job + provider.cancel()；② `onStop()` 自动保存当前会话到 Room DB；③ `showConversationHistory()` 从 DB 加载历史列表；④ `loadConversation()` 恢复完整对话；⑤ `startNewConversation()` 先保存再清空 |
| `ConversationListFragment.kt` | 改用 DB 加载历史数据（替代内存 sessions 列表）；长按可删除；点击回调改为传 `sessionId(String)` |
| `stock/database/StockDatabase.kt` | 升级到 v2；新增 `ConversationEntity`；新增 `conversationDao()`；新增 `MIGRATION_1_2` 迁移；新增 `getInstance()` 单例方法 |
| `stock/database/StockDatabaseManager.kt` | 改用 `StockDatabase.getInstance()` 确保唯一 DB 实例 |
| `ui/MainActivity.kt` | `onCreate()` 中调用 `migrateLegacyConversations()` 自动从外部存储路径导入旧对话数据 |

### 需求 1 — Home 键取消 API 调用

```
用户按 Home 键 / 切换 App
  └─ ChatTabFragment.onPause()
       └─ cancelApiCall()
            ├─ currentStreamingJob?.cancel()   // 取消协程
            └─ apiProvider?.cancel()           // 取消 OkHttp Call
                 └─ OpenAiCompatibleProvider
                      └─ activeCall.getAndSet(null)?.cancel()
                           └─ onFailure/onResponse 检测 call.isCanceled() → 静默返回
```

### 需求 2 — DB 持久化历史对话

```
ChatTabFragment
  ├─ onStop() → saveCurrentConversation()
  │    ├─ serializeMessages()     → JSONArray → messagesJson
  │    ├─ generateTitleAndSubtitle() → 从首条用户消息/末条 AI 回复提取
  │    └─ convRepo.saveConversation(entity)  → Room insertOrUpdate
  │
  ├─ ≡ 按钮 → showConversationHistory()
  │    ├─ ConversationListFragment (全屏 BottomSheet)
  │    │    ├─ convRepo.getAllConvs()         → Room DB 查询
  │    │    ├─ 点击 → onSessionClick(convId)  → loadConversation()
  │    │    │    ├─ convRepo.getById()         → 获取完整消息
  │    │    │    ├─ deserializeMessages()      → JSON 反序列化
  │    │    │    └─ 恢复 messages → adapter.notifyDataSetChanged()
  │    │    └─ 长按 → 确认删除 → convRepo.deleteConversation()
  │
  └─ ⋮ 菜单 → "删除全部历史" → convRepo.deleteAll()
```

### 需求 3 — 外部路径旧数据迁移

`ConversationRepository.migrateLegacyConversations()` 按优先级扫描 3 个路径：

| 优先级 | 路径 | 说明 |
|-------|------|------|
| 1 | `/storage/emulated/0/StockAnalysis/conversations/` | 旧版默认路径 |
| 2 | `/storage/emulated/0/Android/data/com.chin.stockanalysis/files/StockAnalysis/conversations/` | Android 应用私有路径 |
| 3 | `/storage/emulated/0/Documents/StockAnalysis/conversations/` | 文档路径 |

每个 `.json` 文件表示一个会话，格式：
```json
{
  "id": "1716998400000",
  "title": "贵州茅台现在多少钱？",
  "subtitle": "贵州茅台当前价格...",
  "timestamp": 1716998400000,
  "messages": [{"id":"...","content":"...","isUser":true,"timestamp":...}]
}
```

`MainActivity.onCreate()` 启动时自动调用，如果导入成功弹出 Toast 提示导入数量。

---

## 8. 项目文件结构（v4.1 最新）

```
app/src/main/java/com/chin/stockanalysis/
├── ChatActivity.kt              # Activity入口（备用，主入口是Fragment）
├── ApiProvider.kt               # AI API 提供商接口
├── OpenAiCompatibleProvider.kt  # 通用 OpenAI 兼容实现（SSE 流式）
├── ApiConfigManager.kt          # 多API配置管理器
│
├── ui/
│   ├── ChatTabFragment.kt       # ✅ 聊天主Fragment（纯UI层）
│   ├── ChatAdapter.kt           # RecyclerView Adapter（4种ViewType）
│   └── Message.kt               # 消息数据模型
│
└── stock/
    ├── StockQueryEngine.kt      # 🆕 统一查询引擎（Fragment/Activity共用）
    ├── StockService.kt          # 股票服务门面（意图识别→数据获取→格式化）
    ├── StockRealtime.kt         # 实时行情数据类
    ├── StockContext.kt          # 查询上下文（意图+数据+prompt前缀）
    │
    ├── theme/                   # 🆕 主题/板块模块
    │   ├── ThemeStockLibrary.kt    # 内置主题库（商业航天/有色金属/AI等10大主题）
    │   ├── ThemeStockService.kt    # 主题整合层（方案A+B+盘口）
    │   └── UserPreferenceManager.kt # 用户偏好持久化
    │
    ├── data/
    │   ├── MultiSourceStockRepository.kt  # 并发多源仓储（5源竞速）
    │   ├── SmartStockCache.kt            # 交易时段感知缓存
    │   ├── StockDataSourceFactory.kt     # 数据源优先级工厂
    │   └── sources/
    │       ├── SinaStockSource.kt         # 新浪行情
    │       ├── TencentStockSource.kt      # 腾讯行情
    │       ├── EastMoneyStockSource.kt    # 东方财富行情
    │       ├── AKShareSource.kt           # AKShare Python服务
    │       ├── JoinQuantsSource.kt        # 聚宽行情
    │       ├── EastMoneySectorSource.kt   # 🆕 东方财富板块成分股API
    │       └── EastMoneyBidAskSource.kt   # 🆕 东方财富五档盘口API
    │
    ├── intent/
    │   ├── IntentProcessorChain.kt  # 意图识别职责链
    │   ├── IntentResult.kt          # 意图识别结果
    │   ├── StockIntent.kt           # 意图枚举
    │   └── handlers/
    │       ├── StockNameHandler.kt
    │       ├── FuzzyStockNameHandler.kt
    │       └── GeneralStockHandler.kt
    │
    ├── formatter/
    │   └── StockDataFormatter.kt    # 格式化为 AI prompt 文本
    │
    ├── realtime/
    │   ├── RealtimeDataAccessor.kt  # 实时数据访问器
    │   ├── RealtimeDataProcessor.kt # 实时数据处理器
    │   └── RealtimeConfig.kt        # 实时数据配置
    │
    └── analysis/
        └── AiStockAnalyzer.kt       # AI 辅助分析
```
