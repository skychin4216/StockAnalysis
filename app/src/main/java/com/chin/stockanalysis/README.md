# Main 源码目录

## 文件说明

| 文件 | 职责 |
|------|------|
| `ApiProvider.kt` | data class `ApiProviderConfig` 配置定义 + interface `ApiProvider` 接口 |
| `ApiConfigManager.kt` | 预置提供商列表 + SharedPreferences 持久化 + 模型选择 |  
| `OpenAiCompatibleProvider.kt` | **具体实现**：将 ApiProviderConfig 转为 HTTP 请求 + SSE 流式解析 |
| `ApiKeysLoader.kt` | 从 `api_keys_local.properties` 读取本地 API Key（运行时期生效） |
| `ChatActivity.kt` | 聊天页面 Activity |
| `ChatAdapter.kt` | 聊天消息列表适配器 |
| `MainActivity.kt` | 底部导航 Activity |
| `Message.kt` | 聊天消息数据类 |
| `StockDataProvider.kt` | 股票数据提供者 |
| `stock/` | 股票行情模块（5源并发 + 实时数据框架 + 意图识别） |
| `ui/` | UI Fragment（ChatTab, StockTab, Settings） |

## stock/ 包结构（股票行情模块，2026-05-20 最新版）

```
stock/
├── StockService.kt             # 核心服务门面（意图识别 + 数据获取 + 格式化）
├── StockContext.kt             # 处理结果数据类（用于注入 AI prompt）
├── StockRealtime.kt            # 统一数据模型
│
├── intent/                     # 意图识别层 ✅ 全部完成
│   ├── StockIntent.kt          # 意图枚举（9种）
│   ├── IntentResult.kt         # 意图解析结果
│   ├── IntentProcessorChain.kt # 处理器链 + AI兜底
│   └── handlers/
│       ├── IntentHandler.kt    # 处理器接口
│       ├── StockCodeHandler.kt # 股票代码匹配
│       ├── StockNameHandler.kt # 股票名称匹配（28只常见股）
│       ├── IndexHandler.kt     # 指数查询（7个指数）
│       └── AiIntentHandler.kt  # AI 兜底解析 ✅ 新增
│
├── data/                       # 数据访问层（5个数据源 + 双仓储）
│   ├── StockRepository.kt      # 顺序降级仓储（主→备1→备2→...）
│   ├── MultiSourceStockRepository.kt # 并发请求仓储 ✅ 新增
│   ├── StockDataSource.kt      # 数据源接口
│   ├── StockCache.kt           # 智能TTL缓存
│   ├── SmartStockCache.kt      # 智能缓存（大容量版）✅ 新增
│   ├── HttpClientProvider.kt   # 共享OkHttp连接池 ✅ 新增
│   ├── StockDataSourceFactory.kt # 数据源工厂 ✅ 新增
│   └── sources/
│       ├── SinaStockSource.kt       # 新浪（主源, p1）✅ GBK+重试
│       ├── JoinQuantsSource.kt      # 聚宽（专业源, p2,需Token）✅ 新增
│       ├── TencentStockSource.kt    # 腾讯（备源, p2）✅ 重试
│       ├── EastMoneyStockSource.kt  # 东方财富（备源, p3）✅ 重试+单位修正
│       └── AKShareSource.kt         # AKShare（补充源, p4）✅ 新增
│
├── formatter/                  # 数据格式化层
│   └── StockDataFormatter.kt   # 格式化数据用于 AI prompt
│
├── realtime/                   # 实时数据框架
│   ├── RealtimeConfig.kt       # 配置管理 + 3种预设
│   ├── RealtimeDataAccessor.kt # 并发请求 + 智能选源 + 降级模式
│   └── RealtimeDataProcessor.kt# 验证 + 清洗 + 交易时段感知
│
└── analysis/                   # AI 分析层 ✅ 新增
    └── AiStockAnalyzer.kt      # AI 意图解析 + 技术分析
```

## 实时数据框架（realtime/ 包）

### 为什么豆包App能实时获取股票，但豆包API不行？

- **豆包App**：客户端**直连**新浪/腾讯等开源财经 API → 获取实时行情 → 注入 AI prompt → 展示
- **豆包API**：仅调用 AI 模型本身，模型训练数据有截止日期 → **无实时数据**
- **结论**：实时数据必须从**开源财经网站**的 HTTP 接口获取，不能依赖 AI API

### 三个核心组件

| 类 | 职责 | 关键特性 |
|------|------|---------|
| `RealtimeDataAccessor` | **访问实时数据** | 并发请求多源、按延迟智能选源、后台健康检查、频率控制、自动恢复 |
| `RealtimeDataProcessor` | **处理实时数据** | 数据验证（价格/涨跌幅/量合理性）、数据清洗、交易时段感知（5种状态）、formatForAi/formatForUi 双格式化、Flow 自动推送 |
| `RealtimeConfig` | **配置管理** | Builder 模式、3种预设（DEFAULT/FASTEST/LOW_TRAFFIC）、createProcessor() 工厂方法 |

### 一行代码接入

```kotlin
// 创建处理器
val processor = RealtimeConfig.createProcessor()

// 协程中获取实时数据
lifecycleScope.launch {
    val result = processor.getProcessedRealtime(listOf("sh600519"))

    // 用于 AI prompt 注入
    val aiText = processor.formatForAi(result)

    // 或用于 UI 展示
    val uiData = processor.formatForUi(result)
}
```

### 三种预设模式

| 模式 | 缓存TTL | 说明 |
|------|---------|------|
| `DEFAULT` | 3秒 | 稳定模式，并发取最快 |
| `FASTEST` | 2秒 | 极速模式，最新数据 |
| `LOW_TRAFFIC` | 5秒 | 低流量模式，降频率 |

### 数据流

```
用户输入 → IntentProcessor → StockService.processUserInputRealtime()
                                    │
                                    ▼
                            RealtimeDataProcessor.getProcessedRealtime()
                                    │
                           ┌────────┴────────┐
                           ▼                  ▼
                    StockCache(缓存)   RealtimeDataAccessor.fetchRealtime()
                                           │
                              ┌─────────────┼─────────────┐
                              ▼             ▼             ▼
                      SinaSource     TencentSource   EastMoneySource
                      (并发)          (并发)           (并发)
                              │
                              ▼  (取最快返回)
                           验证 → 清洗 → 写入缓存 → 格式化
                                    │
                                    ▼
                                AI Prompt
```

---

## 近期改进汇总（2026-05-19）

根据实际开发过程的问题和优化需求，对实时股票数据方案进行了全面升级。下面是按 Checklist 对应关系列出的关键改进：

### 📦 新增：共享 HTTP 连接池 `HttpClientProvider.kt`

```kotlin
object HttpClientProvider {
    val realtimeClient: OkHttpClient     // 5s超时，10连接池，60s keep-alive
    val healthCheckClient: OkHttpClient  // 3s超时，快速检测健康状态
    val webSocketClient: OkHttpClient    // 长连接，30s心跳（预留给WebSocket）
}
```

所有数据源（新浪/腾讯/东方财富）**共用同一个连接池**，避免重复创建 TCP 连接，降低 DNS 解析次数。

### 🔧 修复：新浪 GBK 编码 `SinaStockSource.kt`

```kotlin
// ⚠️ 关键修复：新浪返回 GBK/GB2312 编码，必须用 bytes() 读取后指定编码！
val bodyBytes = response.body?.bytes()
val body = String(bodyBytes, Charset.forName("GBK"))
```

如果 GBK 解码失败，自动回退到默认编码，确保至少能解析数字字段。

### 🔧 修复：指数退避重试（所有数据源）

| 数据源 | 最大重试 | 延迟策略 | 新增 Headers |
|--------|---------|----------|-------------|
| `SinaStockSource` | 2次 | 500ms → 1000ms | `Referer`, `User-Agent` |
| `TencentStockSource` | 2次 | 300ms → 600ms | `User-Agent` |
| `EastMoneyStockSource` | 2次 | 300ms → 600ms | `User-Agent`, `Referer` |

### 🔧 修复：单位修正 `EastMoneyStockSource.kt`

- **成交量**：东方财富返回单位是「手」，修正为「股」（×100）
- **成交额**：东方财富返回单位是「万元」，修正为「元」（×10000）

### 🧠 改进：智能缓存 TTL `StockCache.kt`

| 时间段 | TTL | 说明 |
|--------|-----|------|
| 交易中 9:30-11:30, 13:00-15:00 | **1秒** | 极短缓存，确保实时性 |
| 午休 11:30-13:00 | **5秒** | 中间状态 |
| 盘后 15:00-22:00 | **5分钟** | 可接受延迟 |
| 深夜-盘前 22:00-9:30 | **30分钟** | 长期缓存 |
| 周末/节假日 | **1小时** | 数据不会变动 |

缓存条目过期后**自动清除**，避免返回陈旧数据。

### 🔄 改进：降级模式 `RealtimeDataAccessor.kt`

```
连续失败 3 次 → 自动进入降级模式
    ├── 请求间隔延长 3 倍（防止被限流封 IP）
    ├── 成功恢复后逐级退出降级
    └── 全部源不可用时 → 健康检查 → 最高优先级源兜底
```

### 🤖 AI 意图解析 + 技术分析（P2 ✅ 已完成）

#### 新增文件

| 文件 | 说明 |
|------|------|
| `stock/analysis/AiStockAnalyzer.kt` | LLM 意图解析器 — 调用 AI 解析复杂意图（如"今天哪些股票涨停了？"→ `QUERY_HOT_STOCKS`），支持技术分析 Prompt 模板、意图执行器 |
| `stock/intent/handlers/AiIntentHandler.kt` | AI 兜底处理器 — 职责链最后一关，当前面 Handler 置信度 < 0.7 时自动调用 LLM 分析 |

#### 核心能力

```
用户输入 → StockCodeHandler / StockNameHandler / IndexHandler
    │
    ├─ 置信度 >= 0.7 → 直接返回
    └─ 置信度 < 0.7 → 交给 AiIntentHandler 兜底
                         │
                         ▼
                  AiStockAnalyzer.analyzeIntent()
                         │
                         ├─ 发送 JSON prompt 给当前 AI 提供商
                         ├─ AI 返回结构化 JSON
                         │   {"intent":"query_hot_stocks","stocks":[],...}
                         ├─ 解析为 IntentResult
                         └─ 注入 system prompt → 发给 AI 生成回答
```

#### 技术分析

```kotlin
// 在 StockService 或 ChatActivity 中使用
val analyzer = AiStockAnalyzer(apiProvider, repository)

// AI 解析复杂意图
val intent = analyzer.analyzeIntent("今天哪些股票涨停了？")
// → IntentResult(QUERY_HOT_STOCKS, confidence=0.85)

// AI 技术分析
val analysis = analyzer.technicalAnalysis(
    userQuery = "分析茅台的MACD",
    realtimeData = formattedStockData,
    analysisType = "macd"
)

// 执行意图
val promptText = analyzer.executeIntent(intent)
```

### 完整 Checklist 实现状态（P0-P2 ✅ 全部完成）

| 优先级 | 任务 | 文件 | 状态 |
|--------|------|------|------|
| P0-必做 | ✅ 确定主数据源（新浪财经） | SinaStockSource.kt | ✅ |
| P0-必做 | ✅ 处理字符编码（GBK→UTF-8） | SinaStockSource.kt | ✅ |
| P0-必做 | ✅ 建立统一数据模型 | StockRealtime.kt | ✅ |
| P0-必做 | ✅ 实现缓存层（智能TTL） | StockCache.kt | ✅ |
| P0-必做 | ✅ 实现数据仓储（Repository） | StockRepository.kt | ✅ |
| P1-重要 | ✅ 实现意图识别链 | IntentProcessorChain.kt + 3个Handler | ✅ |
| P1-重要 | ✅ 添加备用数据源（腾讯/东方财富） | TencentStockSource.kt + EastMoneyStockSource.kt | ✅ |
| P1.5-优化 | ✅ 共享HTTP连接池 | HttpClientProvider.kt **新增** | ✅ |
| P1.5-优化 | ✅ 指数退避重试 | 所有 Source | ✅ |
| P1.5-优化 | ✅ 智能缓存TTL | StockCache.kt | ✅ |
| P1.5-优化 | ✅ 降级模式 | RealtimeDataAccessor.kt | ✅ |
| P1.5-优化 | ✅ 反爬Headers | 所有 Source | ✅ |
| P1.5-优化 | ✅ 单位修正（东方财富） | EastMoneyStockSource.kt | ✅ |
| P2-AI集成 | ✅ AI意图解析 + 技术分析 | AiStockAnalyzer.kt **新增** | ✅ |
| P2-AI集成 | ✅ AI兜底处理器 | AiIntentHandler.kt **新增** | ✅ |
| P2-AI集成 | ✅ 注册到 IntentProcessorChain + processSuspend() | IntentProcessorChain.kt | ✅ |
| P3-后续 | ⏳ 历史K线 + MACD/KDJ计算 | 待实现 | ⏳ |
| P4-后续 | ⏳ 板块查询 + 热门股票 + 财务数据 | 待实现 | ⏳ |

### 包结构说明

```
stock/analysis/           # AI 分析层
    └── AiStockAnalyzer.kt # ✅ AI 意图解析 + 技术分析（新增）

stock/intent/handlers/    # 意图处理器
    ├── IntentHandler.kt       # 处理器接口
    ├── StockCodeHandler.kt    # 股票代码处理 ✅
    ├── StockNameHandler.kt    # 股票名称处理 ✅
    ├── IndexHandler.kt        # 指数处理 ✅
    └── AiIntentHandler.kt     # ✅ AI 兜底解析（新增）
```

---

## 核心架构关系（API 层）

```
ApiProvider.kt (接口 + 配置定义)
    │
    ├── data class ApiProviderConfig        ← 配置字段
    │     id, name, baseUrl, apiKey, model,
    │     supportedModels, fallbackModels
    │
    └── interface ApiProvider              ← 方法签名
          fun sendMessageStream()

OpenAiCompatibleProvider.kt (具体实现)
    : ApiProvider {                         ← 实现接口
        override val config: ApiProviderConfig
        sendMessageStream() {               ← 发起 HTTP 请求
            POST /chat/completions
            SSE 流式读取
            模型回退 (fallbackModels)
            HTTP 5xx 自动重试
            人性化错误提示
        }
    }
```

## API 提供商配置流程

```
用户操作 SettingsFragment
    ↓ 选择提供商 + 模型 + 填 API Key
ApiConfigManager.builtInProviders  ← 提供商列表定义在这里
    ↓ getProviderConfig(providerId)
ApiKeysLoader.get(KEY_XXX)        ← 从 api_keys_local.properties 读取 Key
    ↓ 回退链: 用户设置 > 本地配置文件 > 空字符串
OpenAiCompatibleProvider(config)   ← 直接用 config 发送 HTTP 请求
    ↓ Authorization: Bearer {apiKey}
https://xxx/chat/completions 或 /responses
```

## Python 测试脚本

位于 `app/src/test/java/com/chin/stockanalysis/test_api.py`，无需 Android 环境即可测试所有 API 连通性：

```bash
python app/src/test/java/com/chin/stockanalysis/test_api.py doubao      # 豆包
python app/src/test/java/com/chin/stockanalysis/test_api.py siliconflow # 硅基流动
python app/src/test/java/com/chin/stockanalysis/test_api.py deepseek    # DeepSeek 官方
python app/src/test/java/com/chin/stockanalysis/test_api.py aliyun      # 阿里云 MaaS
python app/src/test/java/com/chin/stockanalysis/test_api.py all         # 全部
```

## 如何新增提供商

**在 `ApiConfigManager.kt` 的 `builtInProviders` 列表中添加一项：**

```kotlin
ApiProviderConfig(
    id = "your-provider-id",           // 唯一 ID
    name = "Your Provider Name",       // 显示名称
    baseUrl = "https://your-endpoint/v1/",
    model = "your-default-model",
    apiKey = "",                        // 留空，ApiKeysLoader 自动从配置文件回退
    description = "描述",
    isFree = true/false,
    supportedModels = listOf("model1", "model2")  // 用户可选的模型列表
)
```

**然后更新 `ApiKeysLoader.kt` 中的 `getLocalKeyName()` 映射：**

```kotlin
"your-provider-id" -> ApiKeysLoader.KEY_YOUR_KEY
```

**在 `api_keys_local.properties` 中添加对应的 Key：**

```properties
YOUR_KEY=sk-xxxx
```

## 用户选择模型和 API Key 在哪里配置

- **模型选择**：在 `ApiConfigManager.builtInProviders` 中定义的 `supportedModels` 列表决定用户可选什么模型。
- **API Key 选择**：`getProviderConfig()` 按优先级返回：
  1. 用户在 Settings 页面手动填写的 Key（最高优先级，存在 SharedPreferences）
  2. `api_keys_local.properties` 中的 Key（本地开发配置，已 `.gitignore`）
  3. 空字符串（无 Key，需用户输入）
- **`TestProviders.kt` 仅用于测试**：测试类 `ModelDirectTest` 和 `ProviderFlowTest` 使用它来获取配置和 API Key。

## 如果用户选择新增的模型（不在这里提供的）

✅ **已实现！** SettingsFragment 中新增了「➕ 添加自定义模型」按钮：

1. 点击按钮弹出对话框
2. 输入任意模型名（如 `doubao-seed-2-0-mini-260428`）
3. 点击「添加」保存到 SharedPreferences（data 分区）
4. 模型出现在下拉列表中，可以正常选择使用
5. 每个自定义模型旁边有 ✕ 按钮可单独删除

**实现原理：**
- `ApiConfigManager.addUserCustomModel(providerId, modelName)` — 保存到 SharedPreferences
- `ApiConfigManager.removeUserCustomModel(providerId, modelName)` — 从 SharedPreferences 删除
- `ApiConfigManager.getUserCustomModels(providerId)` — 获取已保存的自定义模型列表
- `ApiConfigManager.getProviderModels(providerId)` — 合并「内置模型 + 自定义模型」
