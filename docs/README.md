# 📊 StockAnalysis - A股股票智能分析平台

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-100%25-blue.svg)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Android-API%2026+-green.svg)](https://www.android.com/)

A股股票智能分析平台 - 融合 AI 聊天、实时行情、量化策略和模拟交易的企业级 Android 应用。

## ✨ v8.0 新增 → 模拟交易系统

- 🤖 **模拟交易引擎** - 多策略 × 多周期 = 完整模拟买卖流程
- 📊 **策略周期执行** - 当日/近3/10/30/50/100日，每个策略独立打分
- 🔥 **新闻热点固化** - 每天收盘前3大热点自动存档，关盘后可对比验证
- 🔄 **板块轮动检测** - 自动识别板块连续上涨≥3天，给予风险惩罚
- 📤 **数据导入导出** - JSON/CSV 双格式，手机间互传，PC 可读
- 🎯 **AI 精选 Top3** - 跨策略综合评分，选出最有可能上涨的3只
- 📂 **热门板块子板块展开** - 前三名板块的所有子板块全部显示（如光通信→光模块/CPO/光材料/光纤）

### 基础特性
- 🧠 AI 智能对话 - 支持多 AI 厂商 (DeepSeek/硅基流动/豆包/阿里)
- 📊 5源并发获取 - 同时请求5个数据源，取最快返回
- 💾 智能时间感知缓存 - 根据A股交易时段动态调整TTL
- 📈 K线分析 - MPAndroidChart 展示日K线

## 🚀 快速开始

### 前置要求
- Android Studio + Android SDK 26+
- JDK 11+

### 本地开发

```bash
# 1. 克隆
git clone https://github.com/skychin4216/StockAnalysis.git

# 2. 配置 API Key - 编辑项目根目录 api_keys_local.properties
#    (文件已加入 .gitignore)

# 3. 清除旧数据库（v8.0 升级必须）
adb shell pm clear com.chin.stockanalysis

# 4. 构建 & 安装
gradlew assembleDebug && gradlew installDebug
```

## 🔑 API Key 自动填充架构（v8.0 增强版）

### 完整加载链路

```
┌──────────────────────────────────────────────────────────────────────┐
│  编译时 (Gradle)                                                     │
│  api_keys_local.properties ──copyApiKeys(tasks)──→ assets/           │
│  api_keys_local.properties ──buildConfigField──→ BuildConfig.java    │
│                              (debug buildType)                       │
└──────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌──────────────────────────────────────────────────────────────────────┐
│  运行时 (ApiKeysLoader & ApiConfigManager)                            │
│                                                                      │
│  优先级链 (高 → 低):                                                  │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │ 0. BuildConfig (编译时常量) ← debug 构建自动注入                │ │
│  │ 1. SharedPreferences ← 用户在 App 设置页手动输入                │ │
│  │ 2. assets/api_keys_local.properties  ← APK 内置                 │ │
│  │ 3. /data/data/{pkg}/files/api_keys_local.properties             │ │
│  │ 4. /sdcard/api_keys_local.properties ← 用户可自行放置           │ │
│  │ 5. 空字符串 (需用户输入)                                         │ │
│  └─────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────┘
```

### 关键文件

| 文件 | 作用 |
|------|------|
| `app/build.gradle.kts` | `buildFeatures.buildConfig=true` + debug 构建从 `.properties` 解析 Key 注入 `BuildConfig` |
| `ApiKeysLoader.kt` | `get()` 首行检查 `BuildConfig` 编译时常量 → Properties 文件 → SD 卡 → JVM 文件系统 |
| `ApiConfigManager.kt` | 综合优先级链: `getUserApiKey()` → `ApiKeysLoader.get()` → 空字符串 |
| `ApiProvider.kt` | 封装 `baseUrl + apiKey + model` 的 OpenAI 兼容 HTTP SSE 客户端 |
| `api_keys_local.properties` | 开发者本地 Key（已 .gitignore） |

### ⚠️ 注意: `pm clear` 会清除 SharedPreferences

执行 `adb shell pm clear` 不仅清除数据库，还清除用户在设置页手动输入的 Key。重新安装后需：
- **方法一**: 打开 App → 设置 → 重新输入 API Key
- **方法二**: 重编 debug APK (BuildConfig 自动注入 `api_keys_local.properties` 中的 Key)
- **方法三**: 使用 `adb shell settings put global` 不推荐

## 📚 文档

| 文档 | 说明 |
|------|------|
| **[strategy/trade/README.md](app/src/main/java/com/chin/stockanalysis/strategy/trade/README.md)** | ⭐ 模拟交易系统完整架构 |
| **[Strategy_Architecture_Analysis.md](Strategy_Architecture_Analysis.md)** | 🔍 策略架构分析 — 调用链、重复执行诊断、AI选股对比 |
| **[Github架构设计.md](Github_architecture.md)** | 总体架构设计 |
| **[QUICKSTART.md](QUICKSTART.md)** | 5分钟上手 |
| **[API_REFERENCE.md](API_REFERENCE.md)** | 数据源和API说明 |

## 🎯 v8.0 架构图

```
┌──────────────────────────────────────────────────────────────┐
│                 StrategyFragment (3-Tab)                     │
│  ┌───────────────┬──────────────────┬────────────────────┐   │
│  │ 量化选股       │ 热点新闻          │ 模拟交易            │   │
│  │ StrategyList   │ HotNewsFragment  │ SimulationTrade    │   │
│  └───────────────┴──────────────────┴────────────────────┘   │
└───────────────────────┬──────────────────────────────────────┘
                        │
                        ▼
┌──────────────────────────────────────────────────────────────┐
│              SimulationTradeEngine                           │
│  对每个策略 × 每个周期(1/3/10/30/50/100日):                    │
│    ├─ 执行策略 → 原始信号(5~10只)                            │
│    ├─ 新闻力度评分 + 板块轮动惩罚                            │
│    ├─ 主板过滤(记录原因) → Top3                              │
│    ├─ AI精选3只 → 生成买入建议                               │
│    └─ 1000轮调优拟合 → 保存最佳参数                          │
└───────────────────────┬──────────────────────────────────────┘
                        │
                        ▼
┌──────────────────────────────────────────────────────────────┐
│                   Room Database v8                            │
│  daily_period_result      ← 策略周期结果(含过滤原因+Top3)     │
│  strategy_trade_fitting_params ← 1000轮调优(准确率+收益率)    │
│  daily_news_hot_picks     ← 每日新闻热点固化Top3              │
│  strategy_trade_orders    ← 模拟交易订单(买入/卖出/收益)      │
│                                                              │
│  DataExportImport ← JSON/CSV导出, 手机互传, PC可读           │
└──────────────────────────────────────────────────────────────┘
```

### 热门板块子板块展开
取概念板块**前三名**，**列出所有子板块**（不限制数量）：
- 光通信 → 光芯片、光模块、CPO、光材料、光纤、光器件...
- 半导体 → 设备、材料、封测、设计、制造、存储...
- 新能源 → 电池、锂矿、逆变器、组件、硅料...

## 📁 v8.0 修改/新增文件清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `strategy/trade/SimulationTradeEngine.kt` | 新增 | 交易引擎(983行) + 4个Entity + 4个DAO |
| `strategy/trade/SimulationTradeFragment.kt` | 新增 | 模拟交易UI面板(6个操作按钮) |
| `stock/database/DataExportImport.kt` | 新增 | JSON/CSV导入导出工具 |
| `stock/database/StockDatabase.kt` | 修改 | v8: 4张新表 + fallbackToDestructiveMigration |
| `ui/StrategyFragment.kt` | 修改 | 3个Tab: 量化选股/热点新闻/模拟交易 |
| `ui/StrategyListFragment.kt` | 修改 | 热门板块子板块全部展开(不限制数量) |
| `app/build.gradle.kts` | 修改 | buildFeatures.buildConfig + copyApiKeys + debug BuildConfig 注入 |
| `ApiKeysLoader.kt` | 修改 | BuildConfig 最高优先级 (level 0) |
| `strategy/trade/README.md` | 新增 | 模拟交易系统完整架构文档 |
| `.gitignore` | 已有 | 排除 api_keys_local.properties |

## 🐛 故障排查

### StockService vs AI API 日志区分

| 组件 | TAG | 职责 | 常见问题 |
|------|-----|------|---------|
| StockService | `StockService` | 股票数据获取 (新浪/东方财富等) | 数据源不可用 |
| AI 对话 | `ChatTabFragment.sendWithRetry` | AI API SSE 流式回复 | 403/超时/重试 |
| AI Provider | `OpenAiProvider` | HTTP 请求层 | HTTP 403 = Key 无效 |
| 热门新闻 | `HotSectorNewsUpdater` | 新闻搜索 | HTTP 403 = Key 无效 |

### 常见问题

| 问题 | 原因 | 解决 |
|------|------|------|
| `OpenAiProvider: ❌ HTTP 403` | API Key 无效或过期 | 更新 `api_keys_local.properties` 后重编 debug APK |
| `sendWithRetry: ⏳ 重新获取中` | AI SSE 连接临时超时 | 自动重试，1.5 秒后恢复 |
| `pm clear` 后 AI 不工作 | SharedPreferences 被清除 | 重编 debug APK (BuildConfig 注入) 或手动在设置页输入 Key |
| StockService 正常但 AI 无回复 | AI Key 问题 | 检查 `findstr "OpenAiProvider" test.log` |

### 日志分析命令

```bash
# 查看 StockService 日志
findstr "StockService" test.log

# 查看 AI Provider 错误
findstr "OpenAiProvider.*Error\|403" test.log

# 查看重试
findstr "sendWithRetry" test.log

# 查看 App 完整日志
findstr "com.chin.stockanalysis" test.log | findstr /v "SurfaceFlinger\|WindowManager\|SurfaceComposer\|Insets\|Vibrator"
```

## 🔧 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| 语言 | Kotlin | 1.9+ |
| 框架 | Android | API 26+ |
| 架构 | MVVM + Fragment | - |
| 异步 | Coroutine | 1.7+ |
| 网络 | OkHttp | 4.12 |
| 数据库 | Room | 2.7.0 |
| 图表 | MPAndroidChart | 3.1.0 |

## 📝 许可证

MIT 许可证。

---

**最后更新**: 2026-06-07  
**版本**: v9.0 AI量化选股+多AI编排版
