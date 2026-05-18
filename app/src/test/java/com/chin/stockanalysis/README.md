# 单元测试目录

## 文件说明

| 文件 | 类型 | 职责 |
|------|------|------|
| `ModelDirectTest.kt` | JUnit4 | 直连 API 测试（绕过 App 代码，分离免费/付费） |
| `ProviderFlowTest.kt` | JUnit4 | App 全流程测试（OpenAiCompatibleProvider 流式） |
| `TestProviders.kt` | 配置 | **测试专用**的提供商配置中心（免费/付费/按ID查找） |
| `test_api.py` | Python | 测试 Responses API (/api/v3/responses) |
| `test_chat_models.py` | Python | 测试 Chat Completions API (/chat/completions) |
| `ApiKeysLoader.kt` | 工具 | 从项目根目录 `api_keys_local.properties` 读取 Key |
| `ExampleUnitTest.kt` | 示例 | JUnit4 示例 |

## 测试分类

### `ModelDirectTest.kt` — 直连测试

直接发 HTTP 请求到各 API 提供商，验证 Key 和模型是否可用。

**测试方法命名规则：**
- `testFree_Xxx` → 免费模型（无需充值）
- `testPaid_Xxx` → 付费模型（需账号有余额）

**错误处理：**
- HTTP 4xx/5xx → `Assert.fail()` 报测试失败
- 超时 → `Assert.fail()`
- 网络异常 → `Assert.fail()`

### `ProviderFlowTest.kt` — 全流程测试

使用 `OpenAiCompatibleProvider` 发送流式请求，验证从配置→发送→接收的完整链路。

### `test_api.py` — Python 脚本测试

测试 **Responses API (/api/v3/responses)**，这是火山引擎 Ark 的新格式，支持豆包 Seed + DeepSeek + GLM 等模型。**所有 API Key 从 `api_keys_local.properties` 读取。**

```bash
python app/src/test/java/com/chin/stockanalysis/test_api.py doubao      # 只测豆包
python app/src/test/java/com/chin/stockanalysis/test_api.py all         # 测全部
```

### `test_chat_models.py` — Chat Completions API 验证

App 通过 `OpenAiCompatibleProvider` 使用的是 **Chat Completions API (/chat/completions)**，与 `test_api.py` 测试的 `/responses` 是不同端点。

**关键发现：**
- `/responses` 支持 `deepseek-ai/DeepSeek-V3`、`doubao-seed-*` 等全部模型
- `/chat/completions` **仅支持豆包原生模型名**（如 `doubao-seed-2-0-lite-260428`）
- `deepseek-ai/DeepSeek-V3`、`Pro/deepseek-ai/DeepSeek-V3` 等在 `/chat/completions` 返回 404
- 因此 `ApiConfigManager` 中豆包提供商只应列出在 `/chat/completions` 上验证通过的模型

```bash
python app/src/test/java/com/chin/stockanalysis/test_chat_models.py
```

输出示例（已验证）：
```
✅ doubao-seed-2-0-lite-260428
✅ doubao-seed-2-0-mini-260428
✅ doubao-seed-2-0-mini-260215
✅ doubao-seed-2-0-code-preview-260215
✅ doubao-seed-1-6-251015
✅ doubao-seed-1-8-251228
✅ doubao-seed-1-6-flash-250828
✅ doubao-seed-code-preview-251028
❌ deepseek-ai/DeepSeek-V3 → 404（需用 /responses）
❌ Pro/deepseek-ai/DeepSeek-V3 → 404（需用 /responses）
```

## TestProviders.kt 的作用

`TestProviders.kt` 是**测试专用的配置中心**，与主代码的 `ApiConfigManager.builtInProviders` 独立：

```kotlin
// 免费测试
TestProviders.FREE_PROVIDERS      // List<TestProviderConfig>
// 付费测试  
TestProviders.PAID_PROVIDERS      // List<TestProviderConfig>
// 按 ID 查找
TestProviders.findById("doubao")  // TestProviderConfig?
```

**如果需要测试新增模型，在 `TestProviders.kt` 中添加即可。**

## 如何运行测试

### Android Studio (JUnit4)
右键测试方法名 → Run，或右键类名 → Run 'ModelDirectTest' 运行全部。

也可以单独运行：
- 免费测试：右键 `testFree_SiliconflowV3Flash()` → Run
- 付费测试（豆包）：右键 `testPaid_Doubao()` → Run
- 阿里云 MaaS：右键 `testPaid_AliyunMaas()` → Run

### 命令行 (Python)
```bash
python app/src/test/java/com/chin/stockanalysis/test_api.py doubao
```

## 测试与主代码的关系

```
┌─────────────────────────────────────────────────────────────┐
│                     api_keys_local.properties               │
│                     (API Key 唯一源，已 .gitignore)          │
└──────────────┬──────────────────────────┬───────────────────┘
               │                          │
     ┌─────────▼──────────┐    ┌──────────▼──────────┐
     │  TestProviders.kt   │    │  ApiConfigManager.kt │
     │  (测试专用配置)      │    │  (主代码配置管理器)   │
     │                    │    │                      │
     │  FREE_PROVIDERS    │    │  builtInProviders     │
     │  PAID_PROVIDERS    │    │                      │
     │                    │    │  getProviderConfig()  │
     │  .apiKey ←─────┐   │    │  .apiKey ←─────┐     │
     │  .model        │   │    │  .model        │     │
     │  .baseUrl      │   │    │  .baseUrl      │     │
     └────────┬───────┼───┘    └────────┬───────┼─────┘
              │       │               │       │
     ┌────────▼───┐   │      ┌────────▼───┐   │
     │ModelDirect │   │      │Settings    │   │
     │Test.kt     │   │      │Fragment    │   │
     │(直连测试)   │   │      │(用户设置UI) │   │
     └────────────┘   │      └────────────┘   │
     ┌────────────────▼───┐  ┌────────────────▼───┐
     │  ProviderFlowTest.kt│  │  OpenAiCompatible  │
     │  (全流程测试)        │  │  Provider.kt       │
     │                     │  │  (HTTP 请求发送)    │
     └─────────────────────┘  └────────────────────┘
              │                        │
              └────────────────────────┘
                        │
              ┌─────────▼──────────┐
              │   第三方 API 服务器  │
              │   /chat/completions │
              │   /responses        │
              └────────────────────┘
```

**关键原则：**
- `TestProviders.kt` 仅用于测试，不影响 App 运行时的提供商列表
- `ApiConfigManager.builtInProviders` 是 App 运行时提供商列表，在 `SettingsFragment` 中展示给用户
- 所有 API Key 都从 `api_keys_local.properties` 读取，代码中 **0 个硬编码 Key**
- API Key 优先级：用户设置(SharedPreferences) > api_keys_local.properties > 空字符串