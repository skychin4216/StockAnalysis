# 🚀 快速开始指南

> 5分钟快速搭建开发环境，10分钟运行第一个demo

## ⏱️ 预计耗时

- **环境检查**: 2分钟
- **项目克隆和构建**: 5分钟 (或更快，取决于网络)
- **配置API**: 2分钟
- **首次运行**: 2分钟
- **总计**: ~10分钟

## 📋 前置条件

### 1. 系统要求

```bash
# 检查 JDK 版本 (需要11+)
java -version
# Expected: openjdk version "11.0.x" or higher

# 如果没有JDK11，请下载
# https://www.oracle.com/java/technologies/javase-downloads.html
```

### 2. Android Studio 安装

- 下载: [Android Studio Giraffe+](https://developer.android.com/studio)
- 安装 Android SDK 24+ (最好是34)
- 安装 Android Emulator 或连接真机

### 3. 验证环境

```bash
# 检查gradle
./gradlew -v

# 检查kotlin
kotlin -version
```

## 📥 第一步：克隆项目

```bash
# 使用HTTPS克隆（推荐）
git clone https://github.com/your-repo/StockAnalysis.git
cd StockAnalysis

# 或使用SSH
git clone git@github.com:your-repo/StockAnalysis.git
cd StockAnalysis
```

## 🔨 第二步：在Android Studio中打开

### 方案 A: 从Android Studio打开（推荐）

1. 打开 Android Studio
2. 选择 **File** → **Open**
3. 选择 `StockAnalysis` 文件夹
4. Android Studio 会自动识别 gradle 项目
5. 等待 Gradle 同步完成

### 方案 B: 命令行打开

```bash
# macOS/Linux
open -a "Android Studio" /path/to/StockAnalysis

# Windows (在项目目录运行)
start studio.cmd
```

## ⚙️ 第三步：Gradle同步和構建

### 自动同步

Android Studio 会自动提示 **Sync Now**，点击即可。

### 手动同步

```bash
# 同步Gradle
./gradlew clean

# 构建项目
./gradlew build
```

预期输出:
```
...
BUILD SUCCESSFUL in 45s
```

## 🔑 第四步：配置API密钥

### 选项1: 本地配置文件（推荐开发环境）

创建 `local.properties` (如果不存在):

```properties
# StockAnalysis/local.properties

# Android SDK 路径
sdk.dir=/Users/username/Library/Android/sdk

# 或 Windows:
# sdk.dir=C:\\Users\\username\\AppData\\Local\\Android\\Sdk
```

创建 `api_keys_local.properties` (已在 .gitignore 中):

```properties
# StockAnalysis/api_keys_local.properties

# DeepSeek 官方 (付费，需要token)
DEEPSEEK_KEY=sk-xxxxxxxxxxxxxxxx

# 硅基流动 (推荐！免费)
SILICONFLOW_KEY=sk-xxxxxxxxxxxxxxxx

# 豆包 (需要申请)
DOUBAO_KEY=sk-xxxxxxxxxxxxxxxx

# 阿里云 MaaS (需要配置)
ALIYUN_MAAS_KEY=sk-xxxxxxxxxxxxxxxx

# 聱宽（可选，用于更高优先的数据源）
JOINQUANTS_TOKEN=xxxxxxxxxxxxxxxx
```

### 选项2: 在App运行时配置

1. 启动App后进入 **Settings** 标签
2. 点击 **API配置** 按钮
3. 选择 AI 提供商（推荐硅基流动V3）
4. 输入 API Key
5. 点击 **保存**

### 获取API密钥

#### 🆓 免费方案（推荐）

**硅基流动** (完全免费，每月数百万tokens)
1. 访问: https://cloud.siliconflow.cn/
2. 注册账号 (手机号)
3. 进入 **API Keys** 页面
4. 点击 **创建密钥**
5. 复制 Key 到配置文件

```properties
SILICONFLOW_KEY=sk-xxxx
```

**AKShare** (完全免费，无需配置)
- 已集成，无需额外配置
- 自动用于数据获取

#### 💳 付费方案

**DeepSeek 官方**
1. 访问: https://platform.deepseek.com/api_keys
2. 注册账号
3. 创建 API Key
4. 新用户赠送 500万 tokens (~$3)

**聱宽** (可选，用于更好的数据源优先级)
1. 访问: https://www.joinquants.com/
2. 注册账号
3. 在平台获取 API Token

## 🏃 第五步：首次运行

### 使用 Android Studio 运行

1. 连接真机 或 启动模拟器
2. 在 Android Studio 顶部工具栏，选择目标设备
3. 点击 **▶️ Run** 按钮 (绿色三角)
4. 等待编译和安装...

### 或使用命令行运行

```bash
# 安装到连接的设备/模拟器
./gradlew installDebug

# 启动应用
adb shell am start -n com.chin.stockanalysis/.MainActivity
```

## 🧪 第六步：测试功能

### A. 测试数据获取

1. 点击 **💬 Chat** 标签
2. 输入: `600519多少钱`
3. 观察 Logcat 输出 (预期延迟: 50-80ms)
4. 等待AI回复

**Logcat 预期输出** (查看: View → Tool Windows → Logcat):

```
D/ChatActivity: ✅ 多源仓储初始化完成
D/MultiSourceRepository: Concurrent request from 5 sources
D/SinaStockSource: fetchRealtime: requesting 1 codes
D/SinaStockSource: ✓ SinaStockSource: 1/1 (45ms)
D/MultiSourceRepository: All from cache
D/ChatActivity: 📥 StockService.processUserInput 开始
D/ChatActivity: ➡ IntentProcessor 结果: SIMPLE_QUERY
```

### B. 测试AI回复

1. 继续在聊天中输入 `茅台最近的表现怎么样？`
2. obs観看 AI 逐字输出效果（打字效果）
3. 不应该有明显延迟

### C. 测试意图识别

尝试以下输入：

| 输入 | 预期意图 | 预期行为 |
|------|-------|--------|
| `600519` | SIMPLE_QUERY | 获取茅台实时行情 |
| `000001` | SIMPLE_QUERY | 获取平安银行行情 |
| `分析人形机器人前10` | INDUSTRY_ANALYSIS | 调用云服务分析 |
| `茅台K线怎样` | TECHNICAL_ANALYSIS | AI分析技术面 |
| `000001适合买吗` | INVESTMENT_ADVICE | 给出投资建议 |
| `你好` | GENERAL_CHAT | 普通问候 |

## 🐛 常见问题排查

### Q1: Gradle 同步失败

**错误信息**:
```
Could not resolve: com.github.PhilJay:MPAndroidChart:v3.1.0
```

**解决**:
1. 检查网络连接
2. 清空 Gradle 缓存:
   ```bash
   ./gradlew clean
   rm -rf ~/.gradle/caches
   ```
3. 重新同步

### Q2: 编译错误 "Unresolved reference"

**错误信息**:
```
Unresolved reference: ChatAdapter / Message
```

**解决**:
- 确认导入路径是 `com.chin.stockanalysis.ui.*`
- 点击 Android Studio 的 **Sync Now**

### Q3: 运行时崩溃 "ClassNotFoundException"

**错误信息**:
```
ClassNotFoundException: com.chin.stockanalysis.ui.ChatAdapter
```

**解决**:
1. 卸载旧App: `./gradlew uninstallDebug`
2. 重新安装: `./gradlew installDebug`

### Q4: 新浪数据为空

**表现**: 输入股票代码后无法获取数据

**原因**: 新浪 API 需要 Referer 头

**检查**:
```bash
# 手动测试
curl "https://hq.sinajs.cn/list=sh600519" \
  -H "Referer: https://finance.sina.com.cn/"

# 应该返回 GBK 编码的数据
```

### Q5: RemoteDataService 连接失败

**表现**: 产业链分析等功能返回404

**原因**: Python后端还未部署

**临时解决**: 暂时注释掉 RemoteDataService 的调用，只使用本地数据

## 📱 首次运行后...

### 确认功能正常

- [ ] 聊天界面可以发送消息
- [ ] AI 能实时回复
- [ ] 可以查询股票价格（快速<100ms）
- [ ] 在 Logcat 中看到数据源并发日志
- [ ] 缓存工作正常（第2次查询<10ms）

### 下一步

1. 👉 阅读完整架构: [Github架构设计.md](Github_architecture.md)
2. 👉 理解三大功能的原理
3. 👉 部署Python后端 (如果需要产业链分析)
4. 👉 根据需要定制化开发

## 🛠️ 开发模式快速命令

```bash
# 清理构建
./gradlew clean

# 仅编译Kotlin代码（快速检查语法）
./gradlew build --no-tests

# 运行单元测试
./gradlew test

# 运行集成测试（需要连接设备）
./gradlew connectedAndroidTest

# 查看项目依赖
./gradlew dependencies

# 生成项目报告
./gradlew check
```

## 🌐 部署Python后端（可选）

如果需要产业链分析等功能，需要部署Python后端：

```bash
# 1. 进入后端目录
cd python_backend

# 2. 安装依赖
pip install -r requirements.txt

# 3. 本地测试
python app.py

# 4. 部署到 Aliyun Function Compute
serverless deploy

# 5. 获取 Function URL，更新 ChatActivity.kt
```

详见: [完整架构设计 - 部署指南](Github_architecture.md#部署指南)

## 📚 学习路径

### 初级 (了解基础)
1. 运行本指南的前5步
2. 测试基本功能
3. 查看 Logcat 理解数据流

### 中级 (理解架构)
1. 完整阅读 [app/README.md](./app/README.md)
2. 深入 [Github架构设计.md](Github_architecture.md)
3. 研究 SmartStockCache 和 MultiSourceStockRepository

### 高级 (贡献代码)
1. 理解意图识别和AI集成
2. 尝试添加新数据源
3. 部署和优化Python后端

## 🎓 代码示例

### 获取实时股票数据

```kotlin
// 在 ChatActivity 或任何地方使用
val repo = StockDataSourceFactory.createDefaultRepository(this)
val data = repo.getRealtime(listOf("sh600519"))
// 返回: {sh600519: StockRealtime(price=1234.56, ...)}
// 耗时: 50-80ms (并发，取最快的源)

// 后续查询（命中缓存）
val cached = repo.getRealtime(listOf("sh600519"))
// 耗时: <1ms
```

### 识别用户意图

```kotlin
val recognizer = IntentRecognizer()
val result = recognizer.recognizeAndProcess("分析人形机器人前10股票")
// result.intentType = IntentType.INDUSTRY_ANALYSIS
// result.promptInjection = "分析人形机器人概念前10只相关股票..."
// result.data = {keywords: ["人形机器人"], ...}
```

### 调用AI API

```kotlin
val provider = ApiConfigManager.getInstance().createCurrentProvider()
provider.sendMessageStream(
    messages = listOf(Message(...)),
    systemPrompt = "你是股票分析师...",
    onSuccess = { text -> println(text) },  // 逐字回调
    onComplete = { fullText -> println("完成: $fullText") },
    onError = { error -> println("错误: $error") }
)
```

## 💡 开发技巧

### 快速调试

```bash
# 实时查看Logcat
adb logcat | grep "stock\|cache\|repository"

# 清空应用数据并重新运行
adb shell pm clear com.chin.stockanalysis && ./gradlew installDebug
```

### 性能测试

```kotlin
// 在ChatActivity中添加
val startTime = System.currentTimeMillis()
val data = repo.getRealtime(listOf("sh600519"))
val elapsed = System.currentTimeMillis() - startTime
Log.d("PERF", "数据获取耗时: ${elapsed}ms")

// 预期: 首次50-80ms, 缓存命中<1ms
```

### 断点调试

1. 在代码行号左侧点击设置断点
2. 运行 `./gradlew installDebug` 或点击 Run
3. 应用暂停在断点处
4. 使用 Debugger 窗口检查变量

## 🆘 获取帮助

- 📖 完整文档: [Github架构设计.md](Github_architecture.md)
- 💬 应用文档: [app/README.md](./app/README.md)
- 🐛 故障排查: [Github架构设计.md - 故障排查](Github_architecture.md#故障排查)
- 📞 联系: support@stockanalysis.dev

---

**恭喜！你已经完成了快速开始。** 🎉

接下来，建议：
1. 深入阅读 [Github架构设计.md](Github_architecture.md) ⭐⭐⭐
2. 理解三大核心功能的原理
3. 尝试修改代码和测试

祝开发愉快！🚀

