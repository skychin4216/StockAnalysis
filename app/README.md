# 📱 app 模块说明

> StockAnalysis 应用模块 - 核心业务逻辑和UI实现

## 📑 快速导航

- [模块概览](#模块概览)
- [包结构详解](#包结构详解)
- [核心类说明](#核心类说明)
- [工作流程](#工作流程)
- [开发指南](#开发指南)

---

## 模块概览

### 四层架构

```
┌─────────────────────────────────┐
│  UI 层                          │
│  └─ Main / Fragment / Activity  │
├─────────────────────────────────┤
│  业务逻辑层                      │
│  └─ Service / Manager / Analyzer│
├─────────────────────────────────┤
│  数据访问层                      │
│  └─ Repository / DataSource     │
├─────────────────────────────────┤
│  外部API层                       │
│  └─ Sina / JoinQuants / etc     │
└─────────────────────────────────┘
```

### 模块大小统计

| 包 | 文件数 | 类型 | 功能 |
|----|--------|------|------|
| stock/data | 10+ | 核心 | 数据访问、并发、缓存 ⭐⭐⭐ |
| stock/intent | 8+ | 中等 | 意图识别和处理 ⭐⭐⭐ |
| stock/formatter | 1 | 小 | 数据格式化 ⭐ |
| stock/analysis | 1 | 小 | AI分析 ⭐ |
| stock/realtime | 5+ | 中等 | 实时数据框架 ⭐⭐ |
| ui | 10+ | 中等 | UI页面和适配器 ⭐⭐ |
| api | 3 | 小 | AI API支持 ⭐ |

---

## 包结构详解

### 1️⃣ stock/ 包（核心业务逻辑）

#### stock/data/ - 数据访问层 ⭐⭐⭐ 最重要

```
stock/data/
├── SmartStockCache.kt ★           # Feature A - 智能缓存
│   └─ 根据交易时段自动计算TTL (1s/5m/30m/1h)
│
├── MultiSourceStockRepository.kt ★ # Feature B - 并发多源
│   └─ 5源并发竞速，自动故障转移
│
├── StockDataSourceFactory.kt ★     # Feature C - 优先级工厂
│   └─ 统一源管理，Token配置
│
├── StockRepository.kt              # 顺序降级(备用)
│   └─ 传统的主→备1→备2降级方案
│
├── StockCache.kt                   # 旧版简易缓存
├── StockDataSource.kt              # 数据源接口
├── HttpClientProvider.kt           # 共享OkHttp连接池
│
└── sources/ - 5个数据源实现
    ├── SinaStockSource.kt          # 新浪 (P=2, 45ms)
    │   └─ GBK编码 + Referer头 ⚠️
    │
    ├── JoinQuantsSource.kt ★       # 聱宽 (P=1, 80ms) 新增
    │   └─ Token认证 + 格式转换
    │
    ├── TencentStockSource.kt       # 腾讯 (P=3, 120ms)
    ├── EastMoneyStockSource.kt     # 东方财富 (P=4, 180ms)
    │   └─ 单位修正 (手→股, 万元→元)
    │
    └── AKShareSource.kt ★          # AKShare (P=5, 150ms) 新增
        └─ 开源免费，全量数据
```

**最重要的3个文件**:
- `SmartStockCache.kt` - 根本性降低API调用
- `MultiSourceStockRepository.kt` - 根本性提升响应速度
- `StockDataSourceFactory.kt` - 灵活的源管理

#### stock/intent/ - 意图识别层 ⭐⭐⭐

```
stock/intent/
├── IntentProcessorChain.kt         # 职责链处理器
│   └─ 多个Handler顺序处理，兜底由AI处理
│
├── StockIntent.kt                  # 意图类型枚举
├── IntentResult.kt                 # 意图解析结果
│
└── handlers/ - 具体处理器
    ├── IntentHandler.kt            # 处理器接口
    ├── StockCodeHandler.kt         # 股票代码匹配 (正则)
    ├── StockNameHandler.kt         # 股票名称匹配 (28只常见股)
    ├── IndexHandler.kt             # 指数查询 (7个指数)
    └── AiIntentHandler.kt          # AI兜底处理
        └─ 置信度<0.7时调用LLM
```

**工作流程**:
```
用户输入
  ↓
StockCodeHandler (95%置信度) → 返回
  ↓ (不匹配或置信度低)
StockNameHandler (90%置信度) → 返回
  ↓ (不匹配或置信度低)
IndexHandler (95%置信度) → 返回
  ↓ (不匹配或置信度低)
AiIntentHandler (调用LLM分析) → 返回
```

#### stock/formatter/ - 格式化层 ⭐

```
stock/formatter/
└── StockDataFormatter.kt           # 数据格式化
    ├─ formatForAi() → 生成AI提示文本
    └─ formatForUI() → 生成结构化UI数据
```

#### stock/analysis/ - AI分析层 ⭐

```
stock/analysis/
└── AiStockAnalyzer.kt             # AI意图解析+技术分析
    ├─ analyzeIntent() → 调用LLM识别复杂意图
    ├─ technicalAnalysis() → 技术面分析
    └─ executeIntent() → 执行意图
```

#### stock/realtime/ - 实时数据框架 ⭐⭐

```
stock/realtime/
├── RealtimeDataAccessor.kt        # 访问实时数据
│   └─ 并发请求 + 按延迟智能选源 + 后台健康检查
│
├── RealtimeDataProcessor.kt       # 处理实时数据
│   └─ 数据验证 + 清洗 + 交易时段感知 + 格式化
│
└── RealtimeConfig.kt              # 配置管理
    └─ 3种预设 + 工厂方法
```

---

### 2️⃣ ui/ 包（UI层）⭐⭐

```
ui/
├── MainActivity.kt                 # 主界面(Tab Activity)
│   └─ 底部导航 (Stock / Chat / Settings)
│
├── ChatActivity.kt ★              # 聊天主界面 (核心UI)
│   └─ 集成: 多源数据 + 意图识别 + AI API
│
├── ChatAdapter.kt                 # 消息列表适配器
│   └─ 4种ViewType: 用户/AI/流式/错误
│
├── Message.kt                     # 消息数据模型
│   └─ 包含: 内容/类型/时间戳/流式状态等
│
├── StockTabFragment.kt            # K线页面
├── ChatTabFragment.kt             # 聊天入口频道
├── SettingsFragment.kt            # API配置页面
└── [其他Fragment]
```

**ChatActivity 核心职责**:
1. ✅ 初始化多源仓储和健康检查
2. ✅ 选择AI提供商（DeepSeek/硅基/豆包）
3. ✅ 进行意图识别和数据获取
4. ✅ 构建系统Prompt并调用AI API
5. ✅ 流式显示AI回复

---

### 3️⃣ api/ 包（AI API支持）⭐

```
api/
├── ApiProvider.kt                 # API提供商接口
│   └─ data class ApiProviderConfig + interface
│
├── OpenAiCompatibleProvider.kt    # OpenAI兼容实现
│   └─ SSE流式解析 + 自动重试 + 模型回退
│
└── ApiConfigManager.kt            # 多厂商配置管理
    └─ SharedPreferences持久化 + 多模型支持
```

**支持的AI厂商**:
- ✅ DeepSeek 官方 (付费)
- ✅ 硅基流动 V2.5 (免费)
- ✅ 硅基流动 V3 (免费) ⭐ 推荐
- ✅ 豆包 (需配置)
- ✅ 阿里云百炼 (需配置)

---

### 4️⃣ 云服务集成（新增）⭐⭐

```
stock/
├── RemoteDataService.kt ★         # 云API调用
│   ├─ healthCheck() → GET /health
│   ├─ getRealtime() → POST /api/stock/realtime
│   └─ analyzeIndustryChain() → POST /api/analysis/complex
│
├── IntentRecognizer.kt ★          # 意图识别引擎
│   ├─ 识别5类意图 (SIMPLE_QUERY / INDUSTRY_ANALYSIS / etc)
│   ├─ 提取股票代码和关键词
│   └─ 动态构建Prompt注入
│
└── ProcessResult.kt ★             # 处理结果包装
    └─ {intentType, promptInjection, data}
```

---

## 核心类说明

### SmartStockCache

```kotlin
class SmartStockCache(maxSize: Int = 200) {
    // 根据当前时间自动计算TTL
    fun calculateSmartTTL(): Long {
        // 交易中 → 1秒
        // 盘后 → 5分钟
        // 夜间 → 30分钟
        // 周末 → 1小时
    }
    
    fun get(codes: List<String>): Map<String, StockRealtime>
    fun put(data: Map<String, StockRealtime>)
    fun clear()
}
```

**使用示例**:
```kotlin
val cache = SmartStockCache()
cache.put(mapOf("sh600519" to StockRealtime(...)))
val cached = cache.get(listOf("sh600519"))
```

### MultiSourceStockRepository

```kotlin
class MultiSourceStockRepository(
    sources: List<StockDataSource>,
    cache: SmartStockCache
) {
    // 并发请求所有源，取最快返回
    fun getRealtime(codes: List<String>): Map<String, StockRealtime>
    
    // 后台健康检查每30秒运行一次
    fun healthCheck()
    
    // 诊断信息，显示每个源的状态
    fun getDiagnostics(): String
}
```

**使用示例**:
```kotlin
val repo = StockDataSourceFactory.createDefaultRepository(context)
val data = repo.getRealtime(listOf("sh600519", "sz000858"))
// 返回: {sh600519: StockRealtime, sz000858: StockRealtime}
// 耗时: ~50-80ms (vs 原来1000ms+)
```

### IntentRecognizer

```kotlin
class IntentRecognizer {
    fun recognizeAndProcess(userInput: String): ProcessResult {
        // 1. 正则匹配识别意图
        // 2. 提取股票代码和关键词
        // 3. 构建动态Prompt说明
        // 4. 返回ProcessResult
    }
}

data class ProcessResult(
    val intentType: IntentType,
    val promptInjection: String,
    val data: Map<String, Any>
)
```

**意图类型**:
```kotlin
enum class IntentType {
    SIMPLE_QUERY,        // "600519多少钱"
    INDUSTRY_ANALYSIS,   // "分析人形机器人前10股票"
    TECHNICAL_ANALYSIS,  // "分析000001K线"
    INVESTMENT_ADVICE,   // "000001适合买吗"
    GENERAL_CHAT         // "你好"
}
```

---

## 工作流程

### 用户发送消息 → AI回复

```
[ChatActivity]
1. 用户输入: "600519多少钱"
   ↓
2. IntentRecognizer.recognizeAndProcess()
   ├─ 正则提取 "sh600519"
   ├─ 识别意图 → SIMPLE_QUERY
   └─ 返回ProcessResult
   ↓
3. MultiSourceStockRepository.getRealtime(["sh600519"])
   ├─ 查缓存 → Miss
   ├─ 并发启动5个源
   ├─ T+45ms 新浪返回 ✓
   ├─ 写入缓存(TTL=1s)
   └─ 返回 {sh600519: StockRealtime{price: 1234.56}}
   ↓
4. buildSystemPromptWithStockData()
   ├─ 从ProcessResult提取实时数据
   ├─ 构建系统Prompt:
   │  "实时行情: 贵州茅台(600519) ¥1234.56 (+2.15%)"
   └─ 返回完整Prompt
   ↓
5. ApiProvider.sendMessageStream()
   ├─ POST DeepSeek/硅基流动/豆包 API
   ├─ SSE流式接收响应
   └─ 逐字回调给ChatAdapter
   ↓
6. ChatAdapter.onChunkReceived()
   ├─ 消息气泡逐字增长
   ├─ 打字动画闪烁
   └─ 完成后隐藏动画
   ↓
📱 用户看到完整AI回复
```

### 数据获取流程（并发vs顺序）

```
【顺序方案 (原)】
Sina(500ms超时) → Tencent(500ms) → EastMoney(200ms)
总耗时: 1200ms ❌

【并发方案 (新)】
async Sina(45ms) ← 选这个
async Tencent(120ms)
async EastMoney(180ms)
总耗时: 45ms ✅ (26.7倍提升)
```

---

## 开发指南

### 添加新数据源

1. **创建源类** (继承 `StockDataSource`)
   ```kotlin
   class MyNewSource : StockDataSource {
       override fun priority() = 6  // 优先级
       override fun fetchRealtime(codes: List<String>): Map<String, StockRealtime> {
           // 实现数据获取逻辑
       }
   }
   ```

2. **注册到工厂** (在 `StockDataSourceFactory.kt`)
   ```kotlin
   sources.add(MyNewSource())
   ```

3. **自动参与并发竞速** ✅

### 添加新的意图类型

1. **添加到枚举** (在 `StockIntent.kt`)
   ```kotlin
   enum class StockIntent {
       MY_NEW_INTENT
   }
   ```

2. **创建处理器** (继承 `IntentHandler`)
   ```kotlin
   class MyIntentHandler : IntentHandler {
       override fun process(message: String): IntentResult {
           // 识别逻辑
       }
   }
   ```

3. **注册到链** (在 `IntentProcessorChain.kt`)
   ```kotlin
   handlers.add(MyIntentHandler())
   ```

### 对接新的AI API

1. **在 `ApiConfigManager.kt` 添加配置**
   ```kotlin
   ApiProviderConfig(
       id = "my-api",
       name = "My API",
       baseUrl = "https://api.example.com/v1/",
       model = "my-model"
   )
   ```

2. **`OpenAiCompatibleProvider` 自动兼容** ✅
   (如果API遵循OpenAI格式)

3. **用户在Settings中选择使用** ✅

### 常见开发任务

| 任务 | 位置 | 难度 |
|------|------|------|
| 修改缓存不同的时段TTL | `SmartStockCache.kt` | 🟢 简单 |
| 添加新数据源 | `sources/` | 🟡 中等 |
| 修改意图识别规则 | `intent/handlers/` | 🟡 中等 |
| 改进AI Prompt | `ChatActivity.kt` | 🟢 简单 |
| 支持新的AI厂商 | `ApiConfigManager.kt` | 🟡 中等 |

---

## 测试

### 单元测试

```bash
# 运行所有测试
./gradlew test

# 运行特定测试类
./gradlew test --tests "com.chin.stockanalysis.stock.data.*"
```

### 集成测试

```bash
# 在设备或模拟器上运行
./gradlew connectedDebugAndroidTest
```

### 手动测试清单

- [ ] 在聊天页输入 "600519多少钱"
- [ ] 观察数据获取时间 (Logcat)
- [ ] 测试聱宽Token配置
- [ ] 测试不同AI厂商切换
- [ ] 测试网络异常情况的降级

---

## 诊断和调试

### 查看数据源诊断

```kotlin
// ChatActivity中
val diag = multiSourceRepository.getDiagnostics()
Log.d("TAG", diag)
```

输出示例:
```
╔════════════════════════════════╗
║ MultiSourceRepository 诊断     ║
╠════════════════════════════════╣
║ ✅ SinaSource: 45ms (99% ✓)    ║
║ ✅ JoinQuants: 80ms (95% ✓)    ║
║ ⏳ Tencent: 120ms (缓慢)        ║
║ ❌ EastMoney: 不可用 (失败3次)  ║
╚════════════════════════════════╝
```

### 运行时Logcat过滤

```bash
# 查看所有stock相关日志
adb logcat | grep -i "stock\|repository\|cache\|intent"

# 查看某个源的日志
adb logcat | grep "SinaStockSource"
```

---

## 性能优化建议

### 缓存优化
- SmartStockCache 已根据时段优化TTL
- 考虑添加二级持久化缓存（SQLite/Room）

### 网络优化
- HttpClientProvider 已使用共享连接池
- 考虑HTTP/2.0升级

### 并发优化
- MultiSourceStockRepository 已充分利用并发
- 考虑实现更智能的源选择算法

---

## 相关文档

- [项目根目录README](../README.md)
- [完整架构设计](../Github架构设计.md) ⭐ 重点
- [快速开始指南](../QUICKSTART.md)
- [API参考](../API_REFERENCE.md)

---

**最后更新**: 2026-05-20  
**维护者**: AI 架构设计团队  
**版本**: v3.0

