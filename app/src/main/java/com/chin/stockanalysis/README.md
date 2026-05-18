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
| `stock/` | 股票行情模块 |
| `ui/` | UI Fragment（ChatTab, StockTab, Settings） |

## 核心架构关系

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
