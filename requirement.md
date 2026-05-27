# StockAnalysis 需求与架构设计

> 创建日期: 2026-05-27  
> 项目: StockAnalysis (Android KMP 股票分析助手)

---

## 需求1: 修复多股票查询返回错误价格

### 问题描述
发送"生益科技和沪电股份，还能追涨么"时，返回的股票价格非常离谱。但发送"生益科技最新股价"时，返回正确。

### 根因分析
`FuzzyStockNameHandler.extractStockNameCandidate()` 对多股票名称（以"和"、"跟"、"与"连接）提取失败:

1. 输入: `"生益科技和沪电股份，还能追涨么"`
2. `STRIP_WORDS` 中未包含"追涨"、"还能"，未剥离这些无关词
3. 中文字符"和"(U+548C)不在分隔符正则中，导致 `"生益科技和沪电股份"` 被识别为一个 8 字整体
4. `maxByOrNull` 按长度评分偏爱 3-5 字词组，`"还能追涨么"`(5字, 得分15) > `"生益科技和沪电股份"`(8字, 得分8)
5. 最终搜索"还能追涨么"→无结果→返回 UNKNOWN→AI 凭训练数据瞎编价格

对比"生益科技最新股价": `STRIP_WORDS` 包含"最新"，剥离后留下 `"生益科技"`(4字) → 正确搜索并返回真实数据。

### 解决方案
1. **扩展 STRIP_WORDS**: 添加 `"还能追涨"`, `"追涨"`, `"可以追"`, `"能买"` 等常见口语
2. **添加连接词拆分**: 在 extractStockNameCandidate 中先用"和"、"跟"、"与"、"还有"、"以及"拆分多股票名称
3. **改为返回多候选词**: 改返回 `List<String>` 支持多股票名匹配
4. **优化评分策略**: 多股票连接词拆分后分别评分，支持同时查询 2+ 股票
5. **添加 STOCK_NAME_MAP 硬编码**: 在 StockNameHandler 中添加"生益科技"、"沪电股份"等热门股票

涉及文件:
- `app/src/main/java/com/chin/stockanalysis/stock/intent/handlers/FuzzyStockNameHandler.kt`
- `app/src/main/java/com/chin/stockanalysis/stock/intent/handlers/StockNameHandler.kt`

---

## 需求2: 单股票查询正常（确认项）

### 状态
✅ 工作正常。`"生益科技最新股价"` 走 `FuzzyStockNameHandler`，`STRIP_WORDS` 含"最新"，剥离后留下"生益科技"→ 东方财富 API → 返回正确价格。

无需修改。

---

## 需求3: 自动化测试（androidTest）

### 架构设计

```
app/src/androidTest/java/com/chin/stockanalysis/
├── StockAnalysisTest.kt           # 主测试类
├── testdata/
│   ├── TestPrompts.kt             # 测试 prompt 数据集
│   ├── PriceAssertions.kt         # 价格合理性断言工具
│   └── TestRunner.kt              # 测试运行器
└── rules/
    └── StockTestRule.kt           # 测试规则（网络条件跳过等）
```

### 测试用例设计

| 用例ID | Prompt | 验证项 | 预期 |
|--------|--------|--------|------|
| T1 | "贵州茅台最新股价" | stockCodes包含"sh600519" | 置信度>0.7 |
| T2 | "生益科技和沪电股份，还能追涨么" | stockCodes包含"sh600183"和"sz002463" | 置信度>0.7 |
| T3 | "600519价格" | stockCodes包含"sh600519" | 置信度>0.9 |
| T4 | "上证指数" | intent=QUERY_INDEX | stockCodes非空 |
| T5 | "宁德时代和比亚迪对比" | intent=COMPARE_STOCKS | stockCodes.size>=2 |
| T6 | "生益科技最新股价" | 价格在合理区间(1-500) | 价格>0 |
| T7 | "帮我分析茅台" | intent=TECHNICAL_ANALYSIS | stockCodes非空 |
| T8 | "今天天气怎么样" | intent=UNKNOWN | stockCodes空 |

### 测试框架
- Android Instrumented Tests (需要设备/模拟器)
- 使用 JUnit 4 + `@RunWith(AndroidJUnit4::class)`
- `androidTestImplementation` 依赖已就绪
- 价格合理性断言: 0 < price < 10000, 涨跌幅在 -20% ~ +20% 之间

涉及文件:
- `app/src/androidTest/java/com/chin/stockanalysis/testdata/TestPrompts.kt`
- `app/src/androidTest/java/com/chin/stockanalysis/testdata/PriceAssertions.kt`
- `app/src/androidTest/java/com/chin/stockanalysis/StockAnalysisTest.kt`
- `app/build.gradle.kts` (可能需要添加 androidTest 依赖)

---

## 需求4: 股票页面自动刷新价格（TLL 架构）

### TLL 架构 (Time-Location-Limitation)

```
┌──────────────────────────────────────────────────────┐
│                  TLL 自动刷新策略                        │
├──────────┬───────────────────────────────────────────┤
│ Time     │ 交易时段: 每60秒刷新                        │
│          │ 非交易时段: 不刷新(若价格已是最近交易时段获取的) │
│          │ 午休: 不刷新                                │
├──────────┼───────────────────────────────────────────┤
│ Location │ 仅在 StockListFragment 可见时刷新            │
│          │ 用户离开页面时停止刷新                        │
├──────────┼───────────────────────────────────────────┤
│ Limitation│ 单次请求最多10只股票                        │
│           │ 网络失败时自动降级，不阻塞UI                 │
│           │ 每只股票最后一次更新时戳记录                 │
└──────────┴───────────────────────────────────────────┘
```

### 架构设计

```
app/src/main/java/com/chin/stockanalysis/stock/autorefresh/
├── StockAutoRefreshManager.kt       # 自动刷新管理器（核心）
├── RefreshPolicy.kt                 # TLL 刷新策略
└── PriceUpdateNotifier.kt           # 价格更新通知器
```

### 核心逻辑

```kotlin
class StockAutoRefreshManager(
    private val repository: MultiSourceStockRepository,
    private val tradingCalendar: TradingCalendar,
    private val scope: CoroutineScope
) {
    // 定时器，每60秒检查一次
    private var refreshJob: Job? = null
    
    // 最后更新的价格数据 (code → 价格+时间戳)
    private val lastPrices = ConcurrentHashMap<String, PriceSnapshot>()
    
    // 是否应该刷新
    private suspend fun shouldRefresh(): Boolean {
        val status = tradingCalendar.getMarketStatus()
        // 周末/节假日不刷新
        if (!status.isTradingDay) return false
        // 午休不刷新
        if (status.session == Session.LUNCH) return false
        // 盘前/盘后：检查上次更新时间是否已覆盖最新
        // 若lastUpdateTime是今天交易时段获取的，则无需刷新
        return status.session in listOf(Session.MORNING, Session.AFTERNOON)
    }
    
    fun startWatch(stockCodes: List<String>) { ... }
    fun stopWatch() { ... }
}
```

涉及文件:
- `app/src/main/java/com/chin/stockanalysis/stock/autorefresh/StockAutoRefreshManager.kt`
- `app/src/main/java/com/chin/stockanalysis/stock/autorefresh/RefreshPolicy.kt`
- `app/src/main/java/com/chin/stockanalysis/ui/StockListFragment.kt` (集成刷新)
- `app/src/main/java/com/chin/stockanalysis/ui/StockTabFragment.kt` (传递 scope)

---

## 需求5: 量化选股策略框架

### 架构设计

```
app/src/main/java/com/chin/stockanalysis/strategy/
├── StrategyEngine.kt                # 策略引擎（管理策略生命周期）
├── Strategy.kt                      # 策略接口
├── StrategyConfig.kt                # 策略配置数据类
├── strategies/
│   ├── MovingAverageStrategy.kt     # 均线金叉策略
│   ├── VolumeBreakStrategy.kt       # 放量突破策略
│   ├── LowValuationStrategy.kt      # 低估值策略
│   └── MomentumStrategy.kt          # 动量策略
├── filters/
│   ├── StockFilter.kt               # 股票筛选器接口
│   ├── MarketCapFilter.kt           # 市值筛选
│   ├── IndustryFilter.kt            # 行业筛选
│   └── PEratioFilter.kt             # 市盈率筛选
├── data/
│   ├── StockScreener.kt             # 选股数据仓库（调用东方财富排行榜API）
│   └── StrategyCache.kt             # 策略结果缓存
├── models/
│   ├── ScreeningResult.kt           # 选股结果模型
│   └── StrategySignal.kt            # 策略信号（买/卖/持有）
└── ui/
    ├── StrategyAdapter.kt           # 策略列表适配器
    ├── StrategyDetailFragment.kt    # 策略详情页
    └── StrategyResultAdapter.kt     # 选股结果适配器
```

### 策略接口

```kotlin
interface Strategy {
    val id: String
    val name: String
    val description: String
    val category: StrategyCategory
    
    /** 执行选股扫描 */
    suspend fun screen(): Result<ScreeningResult>
    
    /** 策略是否可用（是否满足前置条件） */
    suspend fun isAvailable(): Boolean
    
    /** 策略参数配置 */
    val config: StrategyConfig
}

enum class StrategyCategory {
    TREND,        // 趋势类
    MOMENTUM,     // 动量类
    VALUE,        // 价值类
    VOLUME,       // 量价类
    CUSTOM        // 自定义
}
```

### 首批实现策略
1. **均线金叉策略** - MA5上传MA20，配合放量确认
2. **放量突破策略** - 成交量放大2倍以上且突破前高
3. **低估值策略** - 市盈率<行业均值70%且ROE>15%

### 增删策略
- `StrategyEngine.registerStrategy(strategy)`: 注册新策略
- `StrategyEngine.removeStrategy(id)`: 移除策略
- `StrategyEngine.getStrategies()`: 获取所有策略
- 策略持久化到 SharedPreferences (记录启用的策略ID列表)

涉及文件:
- `app/src/main/java/com/chin/stockanalysis/strategy/StrategyEngine.kt`
- `app/src/main/java/com/chin/stockanalysis/strategy/Strategy.kt`
- `app/src/main/java/com/chin/stockanalysis/strategy/StrategyConfig.kt`
- `app/src/main/java/com/chin/stockanalysis/strategy/strategies/MovingAverageStrategy.kt`
- `app/src/main/java/com/chin/stockanalysis/strategy/strategies/VolumeBreakStrategy.kt`
- `app/src/main/java/com/chin/stockanalysis/strategy/strategies/LowValuationStrategy.kt`
- `app/src/main/java/com/chin/stockanalysis/strategy/models/ScreeningResult.kt`
- `app/src/main/java/com/chin/stockanalysis/strategy/models/StrategySignal.kt`
- `app/src/main/java/com/chin/stockanalysis/strategy/data/StockScreener.kt`
- `app/src/main/java/com/chin/stockanalysis/strategy/ui/StrategyAdapter.kt`
- `app/src/main/java/com/chin/stockanalysis/ui/StrategyFragment.kt` (重构)

---

## 实施顺序

| 优先级 | 需求 | 依赖 | 预计工作量 |
|--------|------|------|-----------|
| P0 | 需求1: 修复多股票价格错误 | 无 | 中 |
| P1 | 需求4: 股票页面自动刷新 | 需求1 | 中 |
| P2 | 需求3: 自动化测试 | 需求1 | 中 |
| P3 | 需求5: 量化选股策略框架 | 无 | 大 |
| P4 | 需求6: 智能追问 + 个人Skill引擎 | 无 | 中 |

---

## 需求6: 智能追问 + 个人Skill引擎

### 问题背景
1. AI 回答后推荐追问，但不知道推荐依据
2. 用户需要将常用查询组合成可一键执行的 Skill
3. 需要从用户习惯自动生成 Skill 建议

### 架构设计

```
app/src/main/java/com/chin/stockanalysis/skill/
├── Skill.kt                       # 技能数据模型
├── SkillEngine.kt                 # 技能引擎（CRUD + SharedPreferences 持久化）
├── README.md
└── prediction/
    ├── FollowUpGenerator.kt       # 智能追问生成器
    │   ├─ 同板块推荐 → [📊关联行业]
    │   ├─ 自然追问   → [➡️自然追问]
    │   ├─ 时段相关   → [🕐时段相关]
    │   └─ Skill推荐  → [🏷️技能推荐]
    └── FollowUpReason.kt          # 追问原因枚举（7种标注来源）

### 核心数据模型

```kotlin
data class Skill(
    val id: String,
    val name: String,           // "每日早盘关注"
    val icon: String,           // "🌅"
    val prompts: List<String>,  // 多个 prompt 组合
    val autoTrigger: Boolean,   // 是否自动推荐
    val triggerTime: String?,   // 触发时段 "09:00-09:30"
    val triggerPrompt: String?, // 推荐追问文本
    val source: SkillSource     // AUTO_GENERATED / USER_CREATED
)

data class FollowUp(
    val question: String,
    val reasons: List<FollowUpReason>,  // 追问来源标注
    val confidence: Float,
    val skillId: String?               // 关联的技能ID
)
```

### 内置默认 Skill
1. **每日早盘关注** → 查看自选股价格 + ETF竞价 + 板块热度
2. **尾盘异动监控** → 尾盘涨跌幅超3%的股票

---

## 项目架构全景图

```
app/src/main/java/com/chin/stockanalysis/
├── ApiProvider.kt / ApiConfigManager.kt     # AI API 配置
├── OpenAiCompatibleProvider.kt              # OpenAI 兼容接口
├── conversation/                            # 对话管理 (Room)
├── memory/                                  # 键值记忆 (Room)
├── stock/                                   # 股票核心引擎
│   ├── StockQueryEngine.kt                  # 查询引擎（入口）
│   ├── StockService.kt                      # 服务门面
│   ├── StockRealtime.kt                     # 实时行情数据模型
│   ├── StockContext.kt                      # 上下文结果
│   ├── RemoteDataService.kt                 # 远程数据服务
│   ├── analytics/                           # 技术分析
│   ├── data/                                # 数据层
│   │   ├── MultiSourceStockRepository.kt    # 多源并发仓储
│   │   ├── SmartStockCache.kt              # 智能缓存 (TTL感知)
│   │   ├── TradingCalendar.kt             # 交易日历
│   │   └── sources/                        # 数据源 (东方财富, Sina, Tencent)
│   ├── database/                            # 本地股票数据库 (Room)
│   ├── formatter/                           # 数据格式化 (AI prompt注入)
│   ├── intent/                              # 意图识别链
│   │   ├── IntentProcessorChain.kt          # 处理器链
│   │   └── handlers/                        # 具体处理器
│   ├── prefetch/                            # 后台预取 (预测性缓存)
│   ├── realtime/                            # 实时数据处理
│   ├── theme/                               # 主题/板块处理
│   └── autorefresh/                         # [NEW] 自动刷新
├── strategy/                                # [NEW] 量化选股策略
└── ui/                                      # UI层
    ├── MainActivity.kt
    ├── ChatTabFragment.kt / ChatAdapter.kt
    ├── StockTabFragment.kt / StockBrowserFragment.kt
    ├── StockListFragment.kt / StockListAdapter.kt
    ├── StockPagerAdapter.kt
    ├── StrategyFragment.kt
    ├── SettingsFragment.kt
    └── ConversationListFragment.kt