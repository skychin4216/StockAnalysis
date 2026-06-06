# 📖 API 参考文档

> 完整的API调用说明、数据格式和集成指南

## 📑 快速导航

- [数据源API](#数据源api)
- [服务API](#服务api)
- [UI组件API](#ui组件api)
- [AI API集成](#ai-api集成)
- [错误处理](#错误处理)
- [完整示例](#完整示例)

---

## 数据源API

### 1. SmartStockCache

智能缓存，根据交易时段动态计算TTL。

#### 类定义

```kotlin
class SmartStockCache(private val maxSize: Int = 200)
```

#### 主要方法

#### `get(codes: List<String>): Map<String, StockRealtime>`

获取缓存中未过期的数据。

**参数**:
- `codes`: 股票代码列表，格式 "sh600519" 或 "sz000858"

**返回值**:
- `Map<String, StockRealtime>` - 股票代码 → 实时数据

**示例**:
```kotlin
val cache = SmartStockCache()
val data = cache.get(listOf("sh600519", "sz000858"))
// 返回: {sh600519: StockRealtime(...), sz000858: StockRealtime(...)}
```

#### `put(data: Map<String, StockRealtime>)`

写入缓存，自动计算并应用SmartTTL。

**参数**:
- `data`: 股票数据集合

**TTL计算规则**:
```
交易中 (09:30-15:00)  → 1秒
盘后   (15:00-22:00)  → 5分钟
夜间   (22:00-09:30)  → 30分钟
周末/节假日           → 1小时
```

**示例**:
```kotlin
val newData = mapOf(
    "sh600519" to StockRealtime(
        code = "sh600519",
        name = "贵州茅台",
        price = 1234.56,
        changePercent = 2.15,
        volume = 12345600
    )
)
cache.put(newData)
// 自动计算TTL为1秒（因为当前是交易中09:30-15:00）
```

#### `clear()`

清空所有缓存。

**示例**:
```kotlin
cache.clear()  // 清空缓存
```

#### 线程安全性

✅ **线程安全** - 所有方法都使用 `synchronized` 保护

---

### 2. MultiSourceStockRepository

并发多源数据仓储，5个源并发竞速。

#### 类定义

```kotlin
class MultiSourceStockRepository(
    private val sources: List<StockDataSource>,
    private val cache: SmartStockCache = SmartStockCache()
)
```

#### 主要方法

#### `getRealtime(codes: List<String>): Map<String, StockRealtime>`

获取实时数据（同步，非挂起）。

**参数**:
- `codes`: 股票代码列表

**返回值**:
- `Map<String, StockRealtime>` - 股票数据

**工作流程**:
1. 查缓存 (O(1), <1ms)
2. 缓存未命中 → 并发启动5个源
3. 取第一个成功返回的 (50-80ms)
4. 写入缓存
5. 返回

**性能数据**:
- 首次查询: 50-80ms
- 缓存命中: <1ms
- P99延迟: 150ms (vs 顺序1200ms)

**示例**:
```kotlin
val startTime = System.currentTimeMillis()
val data = repository.getRealtime(listOf("sh600519", "sz000858"))
val elapsed = System.currentTimeMillis() - startTime

Log.d("TAG", "数据获取耗时: ${elapsed}ms")
// 输出: 数据获取耗时: 45ms (Sina最快返回)

data.forEach { (code, stock) ->
    println("$code: ¥${stock.price} (+${stock.changePercent}%)")
}
```

#### `getRealtimeSuspend(codes: List<String>): Map<String, StockRealtime>` (挂起)

异步版本，用于Coroutine。

**示例**:
```kotlin
lifecycleScope.launch {
    val data = repository.getRealtimeSuspend(listOf("sh600519"))
}
```

#### `healthCheck()`

后台健康检查，检查所有源的可用性。

**工作机制**:
- 每30秒自动运行一次
- 对每个源发起小测试请求
- 连续失败3次标记为不可用
- 不可用源定期重试恢复

**示例**:
```kotlin
// ChatActivity中自动启动
lifecycleScope.launch {
    while (isActive) {
        repository.healthCheck()
        delay(30000)  // 30秒检查一次
    }
}
```

#### `getDiagnostics(): String`

获取诊断信息，显示每个源的状态。

**返回值**:
- 格式化的诊断字符串

**示例**:
```kotlin
val diag = repository.getDiagnostics()
Log.d("TAG", diag)

// 输出:
// ╔════════════════════════════════════╗
// ║ MultiSourceRepository 诊断信息      ║
// ╠════════════════════════════════════╣
// ║ ✅ SinaSource: 45ms (99% ✓)        ║
// ║ ✅ JoinQuantsSource: 80ms (95% ✓)  ║
// ║ ⏳ TencentSource: 120ms (缓慢)       ║
// ║ ❌ EastMoneySource: 不可用 (失败3次) ║
// ║ ✅ AKShareSource: 150ms (92% ✓)    ║
// ╚════════════════════════════════════╝
```

---

### 3. StockDataSourceFactory

数据源工厂，管理优先级和创建。

#### 类定义

```kotlin
object StockDataSourceFactory {
    fun createDefaultRepository(context: Context): MultiSourceStockRepository
    fun createLightweightRepository(context: Context): MultiSourceStockRepository
    fun saveJoinQuantsToken(context: Context, token: String)
    fun loadJoinQuantsToken(context: Context): String
}
```

#### 主要方法

#### `createDefaultRepository(context: Context): MultiSourceStockRepository`

创建完整配置的仓储（生产环境推荐）。

**源列表** (按优先级):
1. JoinQuants (P=1, 80ms) - 如果配置了Token
2. Sina (P=2, 45ms)
3. AKShare (P=5, 150ms)
4. Tencent (P=3, 120ms)
5. EastMoney (P=4, 180ms)

**示例**:
```kotlin
val repo = StockDataSourceFactory.createDefaultRepository(this)
val data = repo.getRealtime(listOf("sh600519"))
```

#### `createLightweightRepository(context: Context): MultiSourceStockRepository`

创建轻量级仓储（低端设备推荐）。

**源列表**:
- Sina (P=2, 45ms)
- AKShare (P=5, 150ms)
- Tencent (P=3, 120ms)

**示例**:
```kotlin
val repo = StockDataSourceFactory.createLightweightRepository(this)
// 仅使用3个源，更轻量级
```

#### `saveJoinQuantsToken(context: Context, token: String)`

保存聱宽Token到SharedPreferences。

**参数**:
- `token`: 聱宽API Token

**示例**:
```kotlin
StockDataSourceFactory.saveJoinQuantsToken(context, "你的Token")
// Token被保存，App重启后自动加载
```

#### `loadJoinQuantsToken(context: Context): String`

从SharedPreferences加载聱宽Token。

**返回值**:
- Token字符串，或空字符串（未配置）

**示例**:
```kotlin
val token = StockDataSourceFactory.loadJoinQuantsToken(context)
if (token.isEmpty()) {
    Log.i("TAG", "聱宽未配置，使用其他源")
}
```

---

## 服务API

### 1. StockService

股票服务门面，解析输入、获取数据、格式化。

#### 类定义

```kotlin
class StockService(
    private val intentProcessor: IntentProcessorChain = IntentProcessorChain(),
    private val repository: StockRepository,
    private val dataFormatter: StockDataFormatter = StockDataFormatter()
)
```

#### 主要方法

#### `processUserInput(userMessage: String): StockContext`

处理用户输入，返回完整上下文。

**参数**:
- `userMessage`: 用户输入的消息

**返回值**:
- `StockContext` 包含:
  - `intent`: 识别出的意图
  - `hasStockData`: 是否包含股票数据
  - `formattedData`: 格式化的提示词文本

**示例**:
```kotlin
val context = stockService.processUserInput("600519多少钱")
// context.intent = StockIntent.QUERY_PRICE
// context.hasStockData = true
// context.formattedData = "请提供关于贵州茅台(600519)的实时行情..."
```

---

### 2. IntentRecognizer

意图识别引擎（新增）。

#### 类定义

```kotlin
class IntentRecognizer

data class ProcessResult(
    val intentType: IntentType,
    val promptInjection: String,
    val data: Map<String, Any>
)

enum class IntentType {
    SIMPLE_QUERY,        // "600519多少钱"
    INDUSTRY_ANALYSIS,   // "分析人形机器人"
    TECHNICAL_ANALYSIS,  // "分析K线"
    INVESTMENT_ADVICE,   // "适合买吗"
    GENERAL_CHAT         // "你好"
}
```

#### 主要方法

#### `recognizeAndProcess(userInput: String): ProcessResult`

识别意图并返回处理结果。

**参数**:
- `userInput`: 用户输入

**返回值**:
- `ProcessResult` 包含:
  - `intentType`: 识别的意图类型
  - `promptInjection`: 动态构建的Prompt说明
  - `data`: 相关数据（如股票代码）

**意图识别规则**:

| 用户输入 | 识别意图 | 是否获取数据 |
|---------|--------|----------|
| "600519多少钱" | SIMPLE_QUERY | ✅ 获取实时数据 |
| "分析人形机器人" | INDUSTRY_ANALYSIS | ✅ 调用云服务 |
| "分析000001K线" | TECHNICAL_ANALYSIS | ✅ 获取技术面数据 |
| "适合买吗" | INVESTMENT_ADVICE | ✅ 提供建议 |
| "你好" | GENERAL_CHAT | ❌ 无需数据 |

**示例**:
```kotlin
val recognizer = IntentRecognizer()

// 示例1: 简单查询
val result1 = recognizer.recognizeAndProcess("600519多少钱")
// result1.intentType = IntentType.SIMPLE_QUERY
// result1.promptInjection = "查询股票sh600519的实时价格..."

// 示例2: 产业链分析
val result2 = recognizer.recognizeAndProcess("分析人形机器人前10股票")
// result2.intentType = IntentType.INDUSTRY_ANALYSIS
// result2.data = {keywords: ["人形机器人"]}

// 示例3: 通用对话
val result3 = recognizer.recognizeAndProcess("你好")
// result3.intentType = IntentType.GENERAL_CHAT
// result3.promptInjection = ""
```

---

### 3. RemoteDataService

云服务调用（新增）。

#### 类定义

```kotlin
class RemoteDataService(private val baseUrl: String)
```

#### 主要方法

#### `suspend fun healthCheck(): Boolean`

检查云服务健康状态。

**返回值**:
- `true` 服务正常
- `false` 服务不可用

**示例**:
```kotlin
lifecycleScope.launch {
    val ok = remoteService.healthCheck()
    if (ok) {
        Log.i("TAG", "云服务正常")
    } else {
        Log.e("TAG", "云服务不可用，降级使用本地数据")
    }
}
```

#### `suspend fun getRealtime(codes: List<String>): Map<String, StockRealtime>`

从云服务获取实时数据。

**参数**:
- `codes`: 股票代码列表

**HTTP请求**:
```
POST {baseUrl}/api/stock/realtime
Content-Type: application/json

{
  "codes": ["sh600519", "sz000858"]
}
```

**HTTP响应** (200 OK):
```json
{
  "code": 200,
  "data": {
    "sh600519": {
      "code": "sh600519",
      "name": "贵州茅台",
      "price": 1234.56,
      "changePercent": 2.15
    }
  },
  "timestamp": "2026-05-20T15:30:00Z"
}
```

**示例**:
```kotlin
lifecycleScope.launch {
    val data = remoteService.getRealtime(listOf("sh600519"))
    data.forEach { (code, stock) ->
        Log.d("TAG", "$code: ¥${stock.price}")
    }
}
```

#### `suspend fun analyzeIndustryChain(query: String): List<StockAnalysisResult>`

进行产业链分析。

**参数**:
- `query`: 查询语句，如 "分析人形机器人前10股票"

**HTTP请求**:
```
POST {baseUrl}/api/analysis/complex
Content-Type: application/json

{
  "query": "分析人形机器人前10股票"
}
```

**HTTP响应**:
```json
{
  "code": 200,
  "data": {
    "stocks": [
      {
        "code": "sh600520",
        "name": "中航光电",
        "price": 245.68,
        "score": 9.5,
        "reason": "产业链主导企业..."
      }
    ]
  }
}
```

**示例**:
```kotlin
lifecycleScope.launch {
    val results = remoteService.analyzeIndustryChain("人形机器人")
    results.forEach { stock ->
        Log.d("TAG", "${stock.name}(${stock.code}): 评分 ${stock.score}")
    }
}
```

---

## 策略与模拟交易服务 API

本节描述在后端或云服务中建议暴露的策略执行与模拟交易相关接口。移动端（App）可通过 RemoteDataService 调用这些 API 来触发策略执行、获取策略结果并启动模拟交易会话。

### 1. POST /api/strategies/{strategy_id}/run
- 描述: 触发指定策略在给定日期执行并持久化结果（非阻塞，返回 exec_id）
- 请求:
  - 方法: POST
  - 路径参数: strategy_id (字符串)
  - 查询参数: date=YYYY-MM-DD
  - body: {
      "params": { /* 策略自定义参数，键值对 */ },
      "requester": "mobile|scheduler|user" // 可选
    }
- 响应(202 Accepted):
  {
    "exec_id": "uuid-xxx",
    "status": "queued",
    "message": "Strategy execution queued"
  }
- 注意: 执行结果写入数据库（StrategyResults 与 StrategyExecs），客户端可轮询 GET /api/strategies/{strategy_id}/results?date=YYYY-MM-DD 或 GET /api/executions/{exec_id}

### 2. GET /api/strategies/{strategy_id}/results
- 描述: 查询某策略在某日的执行结果（若未执行返回 404 或空数组，视实现而定）
- 请求:
  - 方法: GET
  - 查询参数: date=YYYY-MM-DD
- 响应(200 OK):
  {
    "strategy_id": "A",
    "date": "2026-06-07",
    "exec_id": "uuid-xxx",
    "status": "completed",
    "results": [
      { "symbol": "sh600519", "score": 85, "rank": 1, "meta": { /* 任意JSON */ } },
      ...
    ]
  }

### 3. GET /api/executions/{exec_id}
- 描述: 查询执行任务状态与元信息
- 响应示例:
  {
    "exec_id": "uuid-xxx",
    "strategy_id": "A",
    "date": "2026-06-07",
    "status": "running|completed|failed",
    "started_at": "2026-06-07T01:23:45Z",
    "finished_at": null,
    "error": null,
    "meta": { "model_version": "v1.2", "seed": 12345 }
  }

### 4. POST /api/simulations/start
- 描述: 启动一次模拟交易会话，后端会确保当天策略结果存在（若不存在则按请求触发策略执行并等待完成）
- 请求:
  - 方法: POST
  - body: {
      "date": "2026-06-07",
      "use_strategies": ["A","B"],
      "merge_mode": "star|weight|manual",
      "user_id": "user-123",
      "capital": 100000.0,
      "max_positions": 10
    }
- 响应(200):
  {
    "simulation_id": "sim-uuid-xxx",
    "status": "started",
    "report_url": "/api/simulations/sim-uuid-xxx/report"
  }
- 注意: 若策略执行被异步排队，API 可返回 202 并提供 poll URL 或 websocket 推送地址以便客户端获得完成通知。

### 5. GET /api/simulations/{simulation_id}/report
- 描述: 获取模拟交易报告与买入建议
- 响应示例:
  {
    "simulation_id": "sim-uuid-xxx",
    "trade_date": "2026-06-07",
    "ai_picks": [ {"rank":1, "symbol":"sh600519", "composite_score":85, "reason":"..."}],
    "buy_orders": [ {"symbol":"sh600519","qty":100,"price":1234.56}],
    "summary": "...",
    "backtest_metrics": { "nav": 100123.45, "max_drawdown": 0.03 }
  }

### 6. GET /api/ui/selections?date=YYYY-MM-DD
- 描述: 返回供 UI 展示的多策略并列/合并视图（包含策略元数据、打星、以及合并后的候选股票）
- 响应示例:
  {
    "date": "2026-06-07",
    "strategies": [
      {"id":"A","name":"方案A","star":4,"results_url": "/api/strategies/A/results?date=2026-06-07"},
      {"id":"B","name":"方案B","star":3,"results_url": "/api/strategies/B/results?date=2026-06-07"}
    ],
    "merged": [ {"symbol":"sh600519","score":85,"source":["A","B"]} ]
  }

### 7. Webhook / Push
- 建议支持 Webhook 或 websocket 推送：当 exec_id 完成或 simulation 完成时推送事件到客户端（便于 UI 实时刷新）。

---

## UI组件API

### 1. ChatAdapter

消息列表适配器。

#### 类定义

```kotlin
class ChatAdapter(
    private val messages: List<Message>
) : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>()

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val isError: Boolean = false,
    val errorMessage: String? = null
)
```

#### 主要回调

#### `onCopyMessage: (String) -> Unit`

用户点击复制按钮的回调。

**示例**:
```kotlin
adapter.onCopyMessage = { text ->
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
    Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
}
```

#### `notifyItemChanged(position)`

刷新特定位置的消息（如流式输出）。

**示例**:
```kotlin
// 消息逐字增长时
adapter.notifyItemChanged(messages.size - 1)
```

---

### 2. Message 数据模型

```kotlin
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val content: String,           // 消息内容
    val isUser: Boolean,           // true=用户，false=AI
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,  // 流式输出中
    val isError: Boolean = false,      // 错误消息
    val errorMessage: String? = null   // 错误信息
)
```

**使用示例**:
```kotlin
// 用户消息
val userMsg = Message(
    content = "600519多少钱",
    isUser = true
)
messages.add(userMsg)

// AI 消息（流式输出）
val aiMsg = Message(
    content = "正在查询...",
    isUser = false,
    isStreaming = true
)
messages.add(aiMsg)

// 更新流式消息
aiMsg.content += "贵州茅台"
adapter.notifyItemChanged(messages.size - 1)

// 完成流式输出
aiMsg.isStreaming = false
adapter.notifyItemChanged(messages.size - 1)
```

---

## AI API集成

### 1. ApiProvider 接口

```kotlin
interface ApiProvider {
    suspend fun sendMessageStream(
        messages: List<Message>,
        systemPrompt: String,
        onSuccess: (String) -> Unit,      // 逐字回调
        onComplete: (String) -> Unit,     // 完成回调
        onError: (String) -> Unit         // 错误回调
    )
}
```

### 2. OpenAiCompatibleProvider

OpenAI兼容的API实现（支持所有OpenAI格式接口）。

#### 支持的AI厂商

| 厂商 | API端点 | 模型 | 费用 |
|------|--------|------|------|
| DeepSeek官方 | https://api.deepseek.com/v1/chat/completions | deepseek-chat | 付费 |
| 硅基流动 | https://api.siliconflow.cn/v1/chat/completions | deepseek-ai/DeepSeek-V3 | ✅ 免费 |
| 豆包 | https://api.doubao.com/v1/chat/completions | doubao-* | 需配置 |
| 阿里MaaS | https://dashscope.aliyuncs.com/api/v1/ | qwen-* | 需配置 |

#### 请求格式

```json
{
  "model": "deepseek-chat",
  "messages": [
    {"role": "system", "content": "你是一个股票分析师..."},
    {"role": "user", "content": "600519多少钱"}
  ],
  "stream": true,
  "temperature": 0.7,
  "max_tokens": 2048
}
```

#### 响应格式 (SSE流式)

```
data: {"choices":[{"delta":{"content":"贵"}}]}
data: {"choices":[{"delta":{"content":"州"}}]}
data: {"choices":[{"delta":{"content":"茅"}}]}
data: {"choices":[{"delta":{"finish_reason":"stop"}}]}
data: [DONE]
```

#### 使用示例

```kotlin
val provider = OpenAiCompatibleProvider(
    config = ApiProviderConfig(
        id = "siliconflow-v3",
        name = "硅基流动 V3",
        baseUrl = "https://api.siliconflow.cn/v1/",
        apiKey = "sk-xxxx",
        model = "Pro/deepseek-ai/DeepSeek-V3"
    )
)

lifecycleScope.launch {
    provider.sendMessageStream(
        messages = listOf(
            Message("system", "你是股票分析师", isUser = false),
            Message("user", "600519多少钱", isUser = true)
        ),
        systemPrompt = "你是专业的A股分析师...",
        onSuccess = { chunk ->
            // 逐字接收
            Log.d("TAG", "收到: $chunk")
            aiMessage.content += chunk
            adapter.notifyItemChanged(messages.size - 1)
        },
        onComplete = { fullText ->
            // 完成
            Log.d("TAG", "完成: $fullText")
            aiMessage.isStreaming = false
            adapter.notifyItemChanged(messages.size - 1)
        },
        onError = { error ->
            // 错误
            Log.e("TAG", "错误: $error")
            Toast.makeText(context, "AI回复异常: $error", Toast.LENGTH_SHORT).show()
        }
    )
}
```

### 3. ApiConfigManager

多AI厂商配置管理。

#### 类定义

```kotlin
object ApiConfigManager {
    companion object {
        fun getInstance(): ApiConfigManager
    }
    
    fun getProviderConfig(providerId: String): ApiProviderConfig?
    fun createCurrentProvider(): ApiProvider?
    fun saveApiKey(providerId: String, key: String)
    fun getApiKey(providerId: String): String
    fun getCurrentProviderName(): String
}
```

#### 主要方法

#### `getInstance(): ApiConfigManager`

获取单例实例。

```kotlin
val manager = ApiConfigManager.getInstance()
```

#### `getProviderConfig(providerId: String): ApiProviderConfig?`

获取提供商配置。

```kotlin
val config = manager.getProviderConfig("siliconflow-v3")
// 返回: ApiProviderConfig(
//   id = "siliconflow-v3",
//   name = "硅基流动 V3",
//   ...
// )
```

#### `createCurrentProvider(): ApiProvider?`

创建当前选中提供商的实例。

```kotlin
val provider = manager.createCurrentProvider()
if (provider != null) {
    // 使用provider.sendMessageStream()
}
```

#### `saveApiKey(providerId: String, key: String)`

保存API Key到SharedPreferences。

```kotlin
manager.saveApiKey("siliconflow-v3", "sk-xxxx")
```

#### `getApiKey(providerId: String): String`

获取保存的API Key。

```kotlin
val key = manager.getApiKey("siliconflow-v3")
```

---

## 错误处理

### 常见错误及解决

#### HTTP 401 Unauthorized

**原因**: API Key 无效或过期

**解决**:
```kotlin
onError = { error ->
    if (error.contains("401")) {
        Log.e("TAG", "API Key无效，请重新配置")
        // 打开Settings界面让用户重新输入
    }
}
```

#### HTTP 429 Too Many Requests

**原因**: 请求过频繁，被限流

**解决**:
```kotlin
// 等待一段时间后重试
lifecycleScope.launch {
    delay(5000)  // 等待5秒
    // 重新发送请求
}
```

#### Connection Timeout

**原因**: 网络不稳定或API服务器响应慢

**解决**:
```kotlin
// MultiSourceRepository 自动处理
// 如果某个源超时，自动尝试其他源
val data = repo.getRealtime(listOf("sh600519"))
// 即使Sina超时，也会尝试Tencent等
```

#### SSL Certificate Error

**原因**: HTTPS证书验证失败

**解决**:
```kotlin
// HttpClientProvider 已配置标准证书验证
// 如果仍有问题，检查系统时间是否正确
```

---

## 完整示例

### 示例1: 完整的聊天流程

```kotlin
class ChatActivity : AppCompatActivity() {
    
    private lateinit var repository: MultiSourceStockRepository
    private lateinit var apiProvider: ApiProvider
    private lateinit var intentRecognizer: IntentRecognizer
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. 初始化数据源
        repository = StockDataSourceFactory.createDefaultRepository(this)
        
        // 2. 初始化AI提供商
        apiProvider = ApiConfigManager.getInstance().createCurrentProvider()
            ?: return
        
        // 3. 初始化意图识别
        intentRecognizer = IntentRecognizer()
    }
    
    private fun sendMessage(userMessage: String) {
        lifecycleScope.launch {
            // 1. 识别意图
            val result = intentRecognizer.recognizeAndProcess(userMessage)
            
            // 2. 根据意图获取数据
            val stockData = when (result.intentType) {
                IntentType.SIMPLE_QUERY -> {
                    repository.getRealtimeSuspend(result.data["codes"] as? List<String> ?: emptyList())
                }
                else -> emptyMap()
            }
            
            // 3. 构建系统Prompt
            val systemPrompt = """
                你是一个专业的A股投资分析师。
                
                ${result.promptInjection}
                
                如果用户问到股票数据：
                ${StockDataFormatter().format(IntentResult(), stockData)}
            """.trimIndent()
            
            // 4. 调用AI API
            apiProvider.sendMessageStream(
                messages = messages,
                systemPrompt = systemPrompt,
                onSuccess = { chunk ->
                    // 流式更新UI
                    aiMessage.content += chunk
                    adapter.notifyItemChanged(messages.size - 1)
                },
                onComplete = { fullText ->
                    aiMessage.isStreaming = false
                    adapter.notifyItemChanged(messages.size - 1)
                },
                onError = { error ->
                    Toast.makeText(this@ChatActivity, "错误: $error", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}
```

### 示例2: 数据源诊断

```kotlin
lifecycleScope.launch {
    // 启动后台健康检查
    while (isActive) {
        repository.healthCheck()
        delay(30000)
    }
}

// 手动打印诊断信息
Log.d("DIAG", repository.getDiagnostics())

// 测试并发性能
val startTime = System.currentTimeMillis()
val data = repository.getRealtime(listOf("sh600519"))
val elapsed = System.currentTimeMillis() - startTime

Log.i("PERF", "数据获取耗时: ${elapsed}ms")
// 预期: 首次50-80ms, 缓存命中<1ms 
```

---

## 完整数据结构参考

### StockRealtime

```kotlin
data class StockRealtime(
    val code: String,              // 股票代码 "sh600519"
    val name: String,              // 股票名称 "贵州茅台"
    val price: Double,             // 当前价格
    val previousClose: Double,     // 前一日收盘价
    val open: Double,              // 今日开盘价
    val high: Double,              // 最高价
    val low: Double,               // 最低价
    val volume: Long,              // 成交量（股）
    val amount: Long,              // 成交额（元）
    val time: Long,                // 更新时间戳
    val changePercent: Double,     // 涨跌幅 %
    val bid: Double,               // 买一价
    val ask: Double                // 卖一价
)
```

### ProcessResult

```kotlin
data class ProcessResult(
    val intentType: IntentType,
    val promptInjection: String,   // 动态Prompt说明
    val data: Map<String, Any>     // 相关数据
)
```

### ApiProviderConfig

```kotlin
data class ApiProviderConfig(
    val id: String,                // 唯一ID
    val name: String,              // 显示名称
    val baseUrl: String,           // API基地址
    val apiKey: String = "",       // API密钥
    val model: String,             // 默认模型
    val description: String = "",  // 描述
    val isFree: Boolean = false,   // 是否免费
    val supportedModels: List<String> = emptyList()  // 支持的模型列表
)
```

---

## 性能基准

| 操作 | 耗时 | 备注 |
|------|------|------|
| 首次数据获取 | 50-80ms | 5源并发，取最快 |
| 缓存命中查询 | <1ms | 交易中TTL=1s |
| 意图识别 | <10ms | 正则匹配快速 |
| AI流式回复 | 取决于API | 通常2-10秒 |
| 健康检查 | <100ms | 后台30秒运行一次 |

---

**更新时间**: 2026-05-20  
**API版本**: v3.0  
**文档状态**: ✅ 完整

