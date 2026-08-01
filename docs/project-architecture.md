<!-- 文件原名：项目架构说明.md -->

# 📊 StockAnalysis 项目架构说明（v2.0 重设计版）

## 概述
。用户可以像使用 DeepSeek/豆包一样与 AI 交互，AI 能够理解股票相关问题、自动获取最新数据并生成专业的投资分析报告。

## 核心特性
- 🧠 **AI 智能对话** - DeepSeek/豆包风格的聊天界面，支持多 API 提供商
- 📊 **实时行情** - 接入多源数据（新浪、腾讯、东方财富），支持自动降级
- 🎯 **意图识别** - 智能理解用户意图，自动获取相关数据并注入 AI 分析
- 📈 **K 线分析** - MPAndroidChart 展示日 K 线，支持技术指标计算
- 💾 **智能缓存** - 3秒缓存机制，加速查询响应

## 技术栈（v2.0）
| 组件         | 技术/库                           | 说明                    |
|--------------|-----------------------------------|------------------------|
| 语言         | Kotlin                            | 100% Kotlin编写        |
| 应用架构     | Multi-Fragment + MVVM             | 新增：支持多页面Tab   |
| UI 框架      | Android ViewBinding + Fragment    | 新增：BottomNavigationView |
| 聊天界面     | RecyclerView + Custom ViewType    | 新增：4种消息气泡样式  |
| K 线图表     | MPAndroidChart (CandleStickChart) | 保留：其他页面使用    |
| 网络请求     | OkHttp 4.12                       | 保留                   |
| JSON 解析    | org.json + Gson                   | 升级：新增Gson支持    |
| 构建工具     | Gradle KTS                        | 保留                   |

## 项目结构（v2.0）

### 整体架构
```
┌───────────────────────────────────────────────────────────────────┐
│                      MainActivity (TabActivity)                    │
│  ┌─────────────────────────────────────────────────────────────┐  │
│  │  Bottom Navigation View (3 Tabs)                           │  │
│  ├─────────────┬──────────────────┬──────────────┬──────────┤  │
│  │  📊 Stock   │  💬 AI Chat      │  ⚙️ Settings │          │  │
│  │  (Fragment) │  (Fragment)      │  (Fragment)  │          │  │
│  │             │                  │              │          │  │
│  │ (K线+策略)   │ (消息气泡+输入框) │ (API配置)    │          │  │
│  └─────────────┴──────────────────┴──────────────┴──────────┘  │
└───────────────────────────────────────────────────────────────────┘
             │
             ▼ (后端服务无关UI,可复用)
┌───────────────────────────────────────────────────────────────────┐
│              核心业务逻辑层（无 UI 依赖）                          │
├───────────────────────────────────────────────────────────────────┤
│  🧠 意图识别       📊 数据服务        🤖 AI分析                   │
│  ┌──────────┐    ┌────────────┐    ┌──────────────┐             │
│  │Intent    │    │Stock       │    │AI Stock      │             │
│  │Processor │───▶│Service     │───▶│Analyzer      │             │
│  └──────────┘    └────────────┘    └──────────────┘             │
│       │                 │                                        │
│       ▼                 ▼                                        │
│  ┌─────────────────────────────────────────────────────┐        │
│  │        StockRepository + Cache 层                    │        │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌────────┐ │        │
│  │  │ Sina     │ │ Tencent  │ │ EastMoney│ │ Cache  │ │        │
│  │  │ (Primary)│ │ (Backup1)│ │(Backup2) │ │(3sec)  │ │        │
│  │  └──────────┘ └──────────┘ └──────────┘ └────────┘ │        │
│  └─────────────────────────────────────────────────────┘        │
│                    ▲                                             │
│                    │                                             │
│        对外 HTTP API (新浪/腾讯/东方财富)                        │
└───────────────────────────────────────────────────────────────────┘
```

### 文件结构
```
stockanalysis/
├── build.gradle.kts              # 根级构建配置
├── settings.gradle.kts           # 项目设置
├── gradle.properties             # Gradle 属性
├── gradle/
│   └── libs.versions.toml        # 版本目录
│
└── app/
    ├── build.gradle.kts          # 模块构建配置
    ├── AndroidManifest.xml       # 应用清单
    ├── src/
    │   ├── main/
    │   │   ├── java/com/chin/stockanalysis/
    │   │   │   ├── ui/                        # UI 层（新）
    │   │   │   │   ├── MainActivity.kt         # 主 TabActivity
    │   │   │   │   ├── StockTabFragment.kt    # K线页面
    │   │   │   │   ├── ChatTabFragment.kt     # 聊天页面
    │   │   │   │   ├── SettingsFragment.kt    # 设置页面
    │   │   │   │   ├── ChatActivity.kt        # 聊天详情 Activity
    │   │   │   │   ├── ChatAdapter.kt         # 消息适配器
    │   │   │   │   └── Message.kt             # 消息数据模型
    │   │   │   │
    │   │   │   ├── stock/                     # 股票服务包（新）
    │   │   │   │   ├── StockService.kt        # 核心服务门面
    │   │   │   │   ├── StockContext.kt        # 处理结果数据类
    │   │   │   │   ├── StockRealtime.kt       # 统一数据模型
    │   │   │   │   │
    │   │   │   │   ├── intent/                # 意图识别层
    │   │   │   │   │   ├── StockIntent.kt
    │   │   │   │   │   ├── IntentResult.kt
    │   │   │   │   │   ├── IntentProcessorChain.kt
    │   │   │   │   │   └── handlers/
    │   │   │   │   │       ├── IntentHandler.kt
    │   │   │   │   │       ├── StockCodeHandler.kt
    │   │   │   │   │       ├── StockNameHandler.kt
    │   │   │   │   │       ├── HotStockHandler.kt
    │   │   │   │   │       └── AiIntentHandler.kt
    │   │   │   │   │
    │   │   │   │   ├── data/                  # 数据访问层
    │   │   │   │   │   ├── StockRepository.kt
    │   │   │   │   │   ├── StockDataSource.kt
    │   │   │   │   │   ├── StockCache.kt
    │   │   │   │   │   └── sources/
    │   │   │   │   │       ├── SinaStockSource.kt
    │   │   │   │   │       ├── TencentStockSource.kt
    │   │   │   │   │       └── EastMoneyStockSource.kt
    │   │   │   │   │
    │   │   │   │   ├── formatter/             # 格式化层
    │   │   │   │   │   └── StockDataFormatter.kt
    │   │   │   │   │
    │   │   │   │   └── analysis/              # AI分析层
    │   │   │   │       └── AiStockAnalyzer.kt
    │   │   │   │
    │   │   │   └── api/                       # API 调用层（改进）
    │   │   │       ├── ApiProvider.kt
    │   │   │       ├── OpenAiCompatibleProvider.kt
    │   │   │       └── ApiConfigManager.kt
    │   │   │
    │   │   ├── res/
    │   │   │   ├── layout/
    │   │   │   │   ├── activity_main.xml           # TabLayout主布局
    │   │   │   │   ├── fragment_stock.xml          # K线页面布局
    │   │   │   │   ├── fragment_chat.xml           # 聊天页面布局
    │   │   │   │   ├── fragment_settings.xml       # 设置页面布局
    │   │   │   │   ├── activity_chat.xml           # 聊天详情Activity布局
    │   │   │   │   └── item_message.xml            # 消息气泡布局
    │   │   │   ├── drawable/                       # UI 资源
    │   │   │   │   ├── bubble_user.xml             # 用户消息气泡
    │   │   │   │   ├── bubble_ai.xml               # AI消息气泡
    │   │   │   │   └── bubble_error.xml            # 错误提示气泡
    │   │   │   ├── mipmap-*/                       # 应用图标
    │   │   │   ├── values/
    │   │   │   │   ├── colors.xml
    │   │   │   │   ├── strings.xml
    │   │   │   │   ├── themes.xml
    │   │   │   │   └── dimens.xml                  # 新增：尺寸定义
    │   │   │   └── xml/
    │   │   │       ├── backup_rules.xml
    │   │   │       └── data_extraction_rules.xml
    │   │   ├── test/
    │   │   └── androidTest/
    │   └── proguard-rules.pro
    └── gradle/wrapper/
```

## 核心模块职责

### 1️⃣ MainActivity.kt
- **数据获取**：通过 OkHttp 异步请求新浪财经 A 股接口，解析 JSON 获取 OHLC 数据
- **K 线绘制**：使用 MPAndroidChart CandleStickChart 渲染日 K 线
- **量化策略**：内置均线策略（MA5/MA10 金叉/死叉），结果显示在 `tvStrategy` TextView

### 2️⃣ activity_main.xml
- `CandleStickChart` (`@+id/klineChart`) — 320dp 高度的 K 线图
- `TextView` (`@+id/tvStrategy`) — 策略结果显示区域

## 实时数据框架（新增）

### 为什么豆包App能实时获取股票信息，但通过豆包的API接口获取不到？

**核心原因**：
- **豆包App**：客户端直接调用新浪/腾讯等开源财经API获取实时数据 → 注入AI prompt → 展示给用户
- **豆包API**：如果只调用AI模型，模型训练数据有截止日期，无法获取实时数据
- **结论**：实时数据必须从**开源财经网站**的HTTP接口获取，不能依赖AI API

### 实时数据组件

我们设计了两个专门类来处理实时数据：

```
stock/realtime/
├── RealtimeDataAccessor.kt  # ① 专门访问实时数据（并发请求、健康检查、频率控制）
├── RealtimeDataProcessor.kt # ② 专门处理实时数据（验证、清洗、市场状态、格式化）
└── RealtimeConfig.kt        # ③ 配置管理 + 工厂方法
```

#### ① RealtimeDataAccessor（实时数据访问器）

| 特性 | 说明 |
|------|------|
| **并发请求** | 同时从多个数据源请求，取最快返回的，而非顺序降级 |
| **智能选源** | 根据延迟自动排序，优先选最快的源 |
| **健康检查** | 后台每30秒自动ping各数据源，标记不可用的 |
| **频率控制** | 同一股票1秒内不可重复请求，防止被限流 |
| **自动恢复** | 所有源不可用时，强制重试并恢复 |

工作流程：
```
fetchRealtime(["sh600519"])
    │
    ├─ 1. 频率控制 → 检查该股票上次请求是否 > 1秒
    ├─ 2. 过滤健康源 → 排除已标记不可用的源
    ├─ 3. 按延迟/优先级排序
    ├─ 4. 并发请求所有活跃源（async/await）
    ├─ 5. 取第一个成功返回的结果
    └─ 6. 更新延迟统计 + 健康状态
```

#### ② RealtimeDataProcessor（实时数据处理器）

| 特性 | 说明 |
|------|------|
| **数据验证** | 检查价格合理性、涨跌幅是否超±20%限、最高<最低等异常 |
| **数据清洗** | 跳过名称为空、价格为0/负数的异常记录 |
| **交易时段感知** | 自动判断交易中/午休/盘前/收盘/周末休市 |
| **新鲜度判断** | 交易中要求数据 <5秒，收盘后放宽到5分钟 |
| **格式化** | `formatForAi()` 生成AI prompt文本，`formatForUi()` 生成结构化UI数据 |
| **Flow观察** | `observeRealtime()` 每N秒自动推送最新数据 |

#### ③ RealtimeConfig（配置管理）

三种预设配置：

| 模式 | 说明 | 适用场景 |
|------|------|----------|
| `DEFAULT` | 稳定模式，并发取最快 | 大多数场景 |
| `FASTEST` | 极速模式，缓存更短 | 高频交易时需要最新数据 |
| `LOW_TRAFFIC` | 低流量模式，降低频率 | 慢速网络或节省流量 |

### 使用示例

```kotlin
// 1. 一行代码获取完整配置的处理器
val processor = RealtimeConfig.createProcessor()

// 2. 获取实时数据（协程中调用）
lifecycleScope.launch {
    val result = processor.getProcessedRealtime(listOf("sh600519"))
    
    // 3. 格式化数据用于 AI prompt 注入
    val aiText = processor.formatForAi(result)
    
    // 4. 或格式化用于 UI 展示
    val uiData = processor.formatForUi(result)
}
```

### 与原有 StockService 的集成

```kotlin
class ChatActivity {
    // 原有 StockService
    private lateinit var stockService: StockService
    
    // 新增：实时数据处理器
    private lateinit var realtimeProcessor: RealtimeDataProcessor
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // ... 原有初始化
        
        // 创建实时数据处理器（一行代码）
        realtimeProcessor = RealtimeConfig.createProcessor()
        
        // 或自定义配置
        realtimeProcessor = RealtimeConfig.createProcessor(
            config = RealtimeConfig.FASTEST
        )
    }
    
    // 使用新的实时方法
    private suspend fun getRealtimeStockData(userMessage: String): StockContext {
        return stockService.processUserInputRealtime(userMessage, realtimeProcessor)
    }
}
```

### 数据流对比

**原有流程**：
```
用户输入 → IntentProcessor → StockRepository → SinaSource(顺序) → Formatter → AI Prompt
                                                                    ↑
                                                            StockDataFormatter
```

**新实时流程**：
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

### 文件组织

```
stock/realtime/
├── RealtimeDataAccessor.kt   # 并发请求、选源、健康检查、频率控制
├── RealtimeDataProcessor.kt  # 验证、清洗、市场状态、格式化、Flow观察
│                            （包含：ProcessedResult, MarketStatus, UiStockData）
└── RealtimeConfig.kt         # 三种预设模式、Builder、工厂方法
```

---

## 实现阶段与优先级

### 🟢 Phase 1: UI 重构（3-4 小时）
目标：完成 Tab 架构 + 聊天界面基础框架

- [x] 创建 `ui/` 包结构
- [x] 实现 `MainActivity.kt` (TabActivity)
- [x] 实现 `StockTabFragment.kt` (K线迁移)
- [x] 实现 `ChatTabFragment.kt` (快捷入口)
- [x] 实现 `SettingsFragment.kt` (API配置)
- [x] 实现 `ChatActivity.kt` (聊天主体)
- [x] 实现 `ChatAdapter.kt` (消息列表)
- [x] 实现 `Message.kt` (数据模型)
- [x] 新建各布局文件
- [x] 新建气泡 Drawable 资源
- [x] 注册 AndroidManifest

**验收标准**：App 启动能看到 3 个 Tab，能进入聊天页，能输入发送消息（暂无后端处理）

---

### 🟡 Phase 2: 股票数据服务（4-5 小时）
目标：完整的股票数据获取 + 意图识别

- [x] 创建 `stock/` 包结构
- [x] 实现 `StockRealtime.kt` (数据模型)
- [x] 实现 `StockIntent.kt` + `IntentResult.kt`
- [x] 实现 `StockDataSource.kt` (接口)
- [x] 实现 `SinaStockSource.kt` (新浪财经)
- [x] 实现 `StockCache.kt` (缓存机制)
- [x] 实现 `StockRepository.kt` (数据仓储)
- [x] 实现各 `Handler` (意图处理)
- [x] 实现 `IntentProcessorChain.kt` (职责链)
- [x] 实现 `StockDataFormatter.kt` (格式化)
- [x] 实现 `StockService.kt` (服务门面)

**验收标准**：能够解析"茅台"获取实时行情，格式化为文本

---

### 🔴 Phase 3: AI 集成 (3-4 小时)
目标：完整的聊天 + 股票数据融合

- [ ] 优化 `ChatActivity.kt` (集成 StockService)
- [ ] 实现消息流式输出(打字效果)
- [ ] 实现 `AiStockAnalyzer.kt` (复杂意图)
- [x] 实现备用数据源 (TencentSource, EastMoneySource)
- [x] 完善错误处理 + 降级逻辑
- [x] **新增实时数据框架** (RealtimeDataAccessor + RealtimeDataProcessor)
- [ ] 测试完整数据流

**验收标准**：用户输入"茅台"，AI 能回复实时价格；输入复杂问题，AI 能进行分析

---

## 依赖关系

```
Phase 1 (UI)
    ↑
    │(需要)
Phase 2 (数据服务)
    ↑
    │(需要)
Phase 3 (AI集成)
    ↑
    │(需要)
Phase 2.5 (实时数据框架) ← 新增：RealtimeDataAccessor + RealtimeDataProcessor
```

每个 Phase 相对独立，可并行开发某些模块。

## 实时数据框架文件间依赖关系

```
RealtimeConfig.kt
    │
    ├──→ 依赖 RealtimeDataProcessor.kt
    │         │
    │         ├──→ 依赖 StockCache.kt（缓存）
    │         └──→ 依赖 RealtimeDataAccessor.kt
    │                  │
    │                  ├──→ 依赖 StockDataSource.kt（接口）
    │                  └──→ 依赖 sina/tencent/eastmoney 实现
    │
    └──→ 依赖 StockService.kt（通过 processUserInputRealtime 集成）
```

**新文件无循环依赖**，所有新增的 `realtime/` 包只依赖已有 `data/` 包的基础设施。

---

## 依赖库版本

```gradle
// 核心
implementation 'org.jetbrains.kotlin:kotlin-stdlib'
implementation 'androidx.appcompat:appcompat:1.6.1'
implementation 'androidx.core:core-ktx:1.12.0'

// UI
implementation 'com.google.android.material:material:1.11.0'
implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
implementation 'androidx.fragment:fragment-ktx:1.6.1'
implementation 'androidx.recyclerview:recyclerview:1.3.1'

// 图表
implementation 'com.github.PhilJay:MPAndroidChart:v3.1.0'

// 网络
implementation 'com.squareup.okhttp3:okhttp:4.12.0'

// JSON
implementation 'com.google.code.gson:gson:2.10.1'

// 异步(可选)
implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1'
```

---

## A 股数据适配

- **行情数据来源**：新浪财经免费 JSON API（无需 token）
- **备用源**：腾讯财经、东方财富（自动降级）
- **交易规则**：T+1 交易制，±10% 涨跌停（策略需补充约束）
- **节假日过滤**：当前未实现，建议后续补充
- **复权处理**：接口返回原始价格，未做前/后复权

---

## v4.0 架构更新（2026-05-21）

### 新增：主题/板块查询模块

在 v3.0 的「具体股票实时行情查询」基础上，v4.0 新增了「主题/板块查询」能力，
支持"化工前20"、"有色金属"、"商业航天产业链"等行业级别的批量分析。

#### 核心变化：引入 StockQueryEngine

```kotlin
// Fragment/Activity 统一使用
private val queryEngine: StockQueryEngine by lazy {
    StockQueryEngine.create(requireContext())
}

// 发送消息时
val systemPrompt = withContext(Dispatchers.IO) {
    queryEngine.buildSystemPrompt(
        userText = userText,
        baseSystemPrompt = BASE_SYSTEM_PROMPT,
        onPreferenceLeaned = { /* 通知 UI 弹 Toast */ }
    )
}
```

#### 新增 `stock/theme/` 子模块

| 类 | 职责 |
|----|------|
| `ThemeStockLibrary` | 内置10大主题股票库（商业航天/有色金属/AI算力/半导体/军工/化工/医药/新能源/消费/金融），每股预置业务描述和产业链依据 |
| `ThemeStockService` | 整合层，方案A（内置库）+ 方案B（东方财富板块 API），自动选择更全面的数据路径 |
| `UserPreferenceManager` | 单例，持久化用户的过滤偏好（剔除科创板/创业板/市值区间/价格区间），自动从对话中学习 |

#### 新增 `stock/data/sources/` 数据源

| 类 | API | 功能 |
|----|-----|------|
| `EastMoneySectorSource` | 东方财富 push2 行情 API | 按板块名称拉取成分股（40+行业/概念板块），支持市值/交易所过滤 |
| `EastMoneyBidAskSource` | 东方财富五档行情 API | 获取股票的五档买卖挂单，计算买卖比，输出 🟢🟡⚪🔴 低吸评级 |

#### UI 层变化

- `ChatTabFragment`：删除 `initStockService` + `buildSystemPromptWithStockData`，改用 `queryEngine.buildSystemPrompt()`
- `ChatActivity`：同上，去掉所有重复字段
- 菜单新增：「已记忆的偏好」「清除偏好记忆」「数据源诊断」

#### 数据流（v4.0）

```
用户输入
    │
    ▼
StockQueryEngine.buildSystemPrompt()
    │
    ├─ 检测到"化工/有色金属/商业航天"
    │   └─ ThemeStockService → 东方财富板块API → getRealtime()
    │       → 构建含10~20只股票行情+产业链依据的 prompt
    │
    ├─ 检测到股票代码/名称（600519/贵州茅台）
    │   └─ StockService → IntentProcessorChain → getRealtime()
    │       → 构建单股/多股实时行情的 prompt
    │
    └─ 普通问题
        → 直接返回 baseSystemPrompt（AI 基于训练知识回答）
    │
    ▼
AI Provider.sendMessageStream()
    │
    ▼
流式输出到聊天界面（Markdown 表格渲染）
```

## v4.2 数据层更新（2026-07-30）：基本面持久化

### 背景问题

`daily_snapshot` 表（K 线 API 来源）只有 OHLCV + 换手率，没有估值/财务字段。
`StrategyDataFeed.snapshotToStock()` 转出的 `StockRealtime` 其 PE/PB/市值/ROE 全部为 0，
导致所有基本面策略（机构增持、护城河龙头、低估值、周期低位）在第一步硬性过滤即全军覆没
（如 `mcap>=200亿 && PE>0 && PB>0` → 199→0），DAG 长线 pipeline 因 `n_orders` 输出 0
触发 fail-fast 报"失败"。

### 方案（DB 持久化为主 + 运行时兜底）

**1. Schema（DB version 11 → 12，破坏性迁移自动重建）**

`DailySnapshotEntity` 新增 7 列（均默认 0 = 无数据）：
`pe`（动态市盈率，负=亏损）、`pb`、`market_cap`（总市值/元）、
`roe_ttm`（ROE加权/最新报告期）、`gross_margin_ttm`（毛利率）、
`debt_to_asset`（资产负债率）、`operating_cash_flow`（经营现金流/元）。

**2. 同步写入（HistoricalDataFetcher）**

- Step 1（clist 实时榜）：fields 增加 `f9`(PE)/`f20`(市值)/`f23`(PB)，当日行直接带估值。
- Step 2（K 线）：`REPLACE` 写入会覆盖当日行、清零估值 —— 因此新增 **Step 2.5 基本面充实**：
  - push2 行情批量（复用 `EastMoneyStockSource.fetchRealtime`，50/批）→ PE/PB/市值/换手
  - datacenter 财务批量（`FundamentalsProvider.fetchBulkFinance`，
    `RPT_F10_FINANCE_MAINFINADATA` 按报告期倒序分页、每股取最新一期、命中即停）
    → ROEJQ/XSMLL/ZCFZL/NETCASH_OPERATE_PK
  - 经 `DailySnapshotDao.updateFundamentals()` 回写当日行（turnover_rate 用 CASE 保护不被 0 覆盖）。

**3. 运行时兜底（StrategyDataFeed，B 层）**

`prepareFromDb()` 出口检查 `marketCap<=0` 的缺失行，用日级增量缓存
（companion 锁 + 按日 key）批量补 PE/PB/市值。同步已跑时零请求；
仅在升级后未同步 / 同步未覆盖的股票时触发。`DataFeedConfig.enrichFundamentals=false` 可关闭。

**4. 其余写入点接线**

`StockQueryEngine.saveRealtimeToSnapshot`、`SimulationTradeEngine` 当日实时写入
均携带 `StockRealtime` 的基本面字段。

**5. 顺带修复（FactorDataProvider）**

datacenter 报表真实列名与旧代码不符（旧值恒为 0）：
`ROE_WEIGHT→ROEJQ`、`NETPROFIT_YOY→PARENTNETPROFITTZ`、
`TOTALOPERATEREVE_YOY→TOTALOPERATEREVETZ`；并补 `sortColumns=REPORT_DATE` 降序
保证 pageSize=1 取到最新报告期。该报表不含 PE/PB（调用方另有行情来源）。

### 数据流

```
同步: clist(f9/f20/f23) ─┐
      K线(OHLCV, 覆盖当日行) → daily_snapshot(v12)
      Step2.5: push2批量 + F10批量 ─→ updateFundamentals ┘
                                        │
查询: prepareFromDb → snapshotToStock(携带7字段) → StockRealtime
        └─ marketCap<=0 的行 → B层日级缓存补拉(仅缺失码)
                                        │
      策略硬性过滤(PE/PB/mcap/ROE/毛利率/负债率/现金流) 正常生效
```

### 已知边界

- 历史日期行的基本面为 0（K 线 API 无历史估值，回测场景按需 `enrichFundamentals=false`）。
- ROE 取最新报告期（季报值），非年化 —— 策略阈值（如 roe_min=15）按此语义理解。
- B 层只补估值三件套（PE/PB/市值），ROE 等财务字段依赖同步 Step 2.5。

---

## v5.0 Agent 框架与 DAG Pipeline（2026-07-30 ~ 08-01）

### 架构概览

v5.0 引入了两大核心系统：**Agent 框架**（AI 驱动的多角色分析）和 **DAG Pipeline**（声明式量化选股流水线），统一通过 `AgentOrchestrator` 调度。

```
┌─────────────────────────────────────────────────────────────┐
│                    AgentOrchestrator                         │
│  ┌──────────┐  ┌──────────────┐  ┌──────────────────────┐  │
│  │ Scout    │  │ DeepAnalyst  │  │ Guardian             │  │
│  │ (市場環境)│  │ (7子Agent)   │  │ (風控評估)           │  │
│  └──────────┘  └──────────────┘  └──────────────────────┘  │
│        │              │                    │                │
│        └──────────────┼────────────────────┘                │
│                       ▼                                     │
│              V2DecisionMatrix                               │
│         (環境×利潤質量×PE/PB)                               │
└─────────────────────────────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│                    DAG Pipeline                              │
│  XML 聲明式定義 → Kahn 拓撲排序 → 分層並行執行              │
│                                                              │
│  4 個 UseCase：screening / ultra_short / short_term /        │
│                 mid_term / long_term                         │
│                                                              │
│  節點類型：DATA_SOURCE → DATA_TRANSFORM → FACTOR_COMPUTE →  │
│           STRATEGY → ENRICHMENT → FILTER → AI_PREDICTION →  │
│           AGGREGATION → TRADE_ACTION                        │
└─────────────────────────────────────────────────────────────┘
```

### Agent 框架核心文件

| 文件 | 職責 |
|------|------|
| `agent/core/AgentOrchestrator.kt` | 統一入口，管理 Scout/Analyst/Guardian 三角色並行 |
| `agent/core/DeepAnalystEngine.kt` | 7 子 Agent 3 階段深度分析（V1 流水線等價） |
| `agent/core/AgentRole.kt` | 角色定義（Scout/Analyst/Guardian/Analyst）+ 權限矩陣 |
| `agent/core/AgentContext.kt` | Agent 上下文（會話、權限、記憶） |
| `agent/core/SubAgentSpawner.kt` | 子 Agent 派生與超時管理 |
| `agent/core/IntentRouter.kt` | 意圖路由：4 意圖 × 4 週期 → Agent 集群組合 |
| `agent/core/StockAnalysisUseCase.kt` | 統一分析入口 `analyzeStock()` 擴展函數 |
| `agent/v2/V2DecisionMatrix.kt` | 決策矩陣：環境 × 利潤質量 × 估值 → 操作建議 |
| `agent/v2/PositionWaterValve.kt` | 倉位水閥：根據市場環境計算倉位上限 |
| `agent/v2/ProfitQualityAnalyzer.kt` | 利潤質量分析（主營業務/一次性/投資收益） |

### DAG Pipeline 核心文件

| 文件 | 職責 |
|------|------|
| `strategy/topology/core/DagPipeline.kt` | DAG 執行引擎（Kahn 拓撲排序 + 分層並行） |
| `strategy/topology/core/PipelineContext.kt` | Pipeline 上下文（stockFlow、stageOutputs、errors） |
| `strategy/topology/xml/PipelineXmlParser.kt` | XML 解析器（節點 + 邊 + 自閉合標籤處理） |
| `strategy/topology/xml/NodeRegistry.kt` | 節點註冊表（module name → factory） |
| `strategy/topology/xml/UseCaseLoader.kt` | UseCase 加載 + 策略動態注入 |
| `strategy/topology/nodes/PipelineNodes.kt` | 基礎節點實現 |
| `strategy/topology/nodes/HardcodeCompatNodes.kt` | Hardcode 兼容層節點（5 個） |
| `strategy/topology/nodes/MidTermPipelineNodes.kt` | 中長線專用節點（HoldingGuard 等） |
| `strategy/topology/xml/DagTradeExecutor.kt` | DAG 交易執行器 |

### XML Pipeline 節點統計

| Pipeline | 節點數 | 關鍵節點 |
|----------|--------|---------|
| ultra_short | 11 | n_cand（候選池）, n_t1sell（T+1 賣出） |
| short_term | 17 | +n_zipline（因子預計算）, +n_crosstab（跨 Tab 發布） |
| mid_term | 20 | +n_sector_pool（板塊精選）, n_guard（持倉風控） |
| long_term | 12 | +n_cand（候選池過濾） |

### 四週期策略體系

| 週期 | HoldingPeriod | 默認持倉天數 | 策略數量 | 代表策略 |
|------|--------------|-------------|---------|---------|
| 超短線 | ULTRA_SHORT | 1 天 | 3+ | MarketSentimentStrategy（情緒週期） |
| 短線 | SHORT | 3-5 天 | 4+ | SectorRotationStrategy（板塊輪動） |
| 中線 | MID | 10-30 天 | 4+ | TrendFollowingStrategy（均線趨勢） |
| 長線 | LONG | 30-180 天 | 3+ | CyclicalLowPositionStrategy（週期低位） |

策略通過 `holdingPeriods` 屬性聲明適用的週期，`UseCaseLoader.injectStrategiesToDag()` 在 Pipeline 啟動時按週期動態注入。

---

## v5.1 做T系統（T+0 日內交易）

### 概述

做T系統實現 A 股 T+0 日內交易（高拋低吸），在持有底倉的前提下，利用日內波動賺取差價。支持兩種模式：

- **做T（正T）**：低買 → 高賣（T_BUY → T_SELL）
- **反T（倒T）**：高賣 → 低買回（RT_SELL → RT_BUY）

### 核心文件

| 文件 | 職責 |
|------|------|
| `strategy/trade/TTradeModels.kt` | 數據模型：TTradeRecordEntity（交易記錄）、TTradeRecommendationEntity（推薦記錄）、TTradeSignal（信號）、TTradeStats（統計） |
| `strategy/trade/TTradeEngine.kt` | 核心引擎：信號生成、交易執行、推薦管理、結果跟蹤、收盤統計 |
| `strategy/trade/QuantFragmentBase.kt` | UI 基類：做T按鈕、對話框、推薦卡片、統計展示 |
| `stock/database/AppBackgroundRunner.kt` | 後台監控：每 5 分鐘掃描所有週期持倉 + 真實持倉 |

### 信號生成邏輯

```
generateSignals(stockCode, basePositionQty, periodType)
    │
    ├─ 讀取 daily_snapshot 近 30 天數據
    ├─ 計算 MA5, MA10, 20 日高低點, 平均振幅
    ├─ 支撐位 = max(recentLow, MA5×0.98, MA10×0.97)
    ├─ 阻力位 = min(recentHigh, MA5×1.02, MA10×1.03)
    ├─ 做T數量 = 底倉 × 40%（取整到 100 股）
    │
    ├─ 當前價接近支撐位（<2%）→ T_BUY 信號
    ├─ 當前價接近阻力位（<2%）→ RT_SELL 信號
    └─ 檢查未配對交易 → T_SELL / RT_BUY 配對信號
```

### 後台監控流程

```
AppBackgroundRunner.monitorTTradeOpportunities() [每 5 分鐘]
    │
    ├─ 1. expireOldRecommendations() — 過期昨日 PENDING 推薦
    ├─ 2. 收盤結算（15:00-15:05）— markDayEnd()
    ├─ 3. 掃描 4 個週期模擬持倉 + 真實持倉
    │      └─ 為每隻持倉 generateSignals() → saveRecommendations()
    ├─ 4. trackOutcomeForRecommendations() — 更新價格軌跡
    │      └─ 檢查目標價是否觸及 → markTargetHit()
    └─ 5. 記錄日誌
```

### 推薦生命周期

```
PENDING ──→ EXECUTED     （用戶執行了該推薦）
PENDING ──→ TARGET_HIT   （目標價觸及，虛擬成功）
PENDING ──→ TARGET_MISSED（收盤未觸及，虛擬失敗）
PENDING ──→ EXPIRED     （次日自動過期）
```

### 做T統計指標

| 指標 | 說明 |
|------|------|
| 虛擬成功率 | 目標價觸及數 / 總推薦數 × 100% |
| 實際成功率 | 已執行中盈利數 / 已執行數 × 100% |
| 平均虛擬盈虧 | 所有推薦的虛擬盈虧均值 |
| 總盈虧 | 已平倉交易的實際盈虧總和 |

### DB Schema（v17 → v18）

`t_trade_recommendations` 表新增字段：

| 字段 | 類型 | 說明 |
|------|------|------|
| `period_type` | TEXT | 所屬週期（UltraShortQuant/ShortTermQuant/MidTermQuant/LongTermQuant/RealPosition） |
| `peak_price_after` | REAL | 推薦後最高價 |
| `trough_price_after` | REAL | 推薦後最低價 |
| `target_hit` | INTEGER | 目標價是否觸及（0/1） |
| `virtual_profit_pct` | REAL | 虛擬盈虧百分比 |

---

## 數據庫版本歷史

| 版本 | 變更 |
|------|------|
| v11 → v12 | `daily_snapshot` 新增 7 列基本面字段（PE/PB/市值/ROE/毛利率/負債率/現金流） |
| v12 → v13 | 新增 `institutional_tips` 表（機構線索） |
| v13 → v14 | 新增 `period_holding_profit` 表（週期持倉盈虧） |
| v14 → v15 | 新增 `t_trade_records` 表（做T交易記錄） |
| v15 → v16 | 新增 `real_positions` 表（真實持倉） |
| v16 → v17 | 新增 `t_trade_recommendations` 表（做T推薦記錄） |
| v17 → v18 | `t_trade_recommendations` 新增 5 列跟蹤字段（period_type, peak/trough, target_hit, virtual_profit） |

