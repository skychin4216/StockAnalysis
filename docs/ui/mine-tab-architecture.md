# 我的 TAB 架构（设置）

> 最后更新：2026-08-13 | 代码位置：`ui/SettingsFragment.kt`

---

## 一、入口层级

```
MainActivity Tab[4] 我的
└── SettingsFragment
    ├── API Provider 配置（btnChangeApiKey → showApiConfigDialog）
    ├── 语言切换（btnLanguage → showLanguageDialog）
    ├── 清除缓存（btnClearCache）
    ├── 关于（tvAbout）
    ├── Agent 框架开关（setupAgentFramework）
    └── 通知与自动执行（setupWechatNotification）
        ├── 微信通知（ServerChan）开关 + Key
        ├── PushPlus 开关 + Token
        └── 做T 自动执行开关
```

## 二、配置项详解

### 2.1 API Provider

- `ApiConfigManager` 管理多 Provider（豆包/通义千问/DeepSeek/硅基流动）。
- 弹窗可选择 Provider、填写 API Key、切换模型。

### 2.2 Agent 框架（Feature Flag）

- `FeatureFlagManager.chatRoute`：`AGENT_FRAMEWORK`（默认）↔ `LEGACY`。
- `GlobalMode`：全局模式（专业/极简等）。

### 2.3 通知（TradeNotifier）

| 配置 | 存储键 | 说明 |
|------|--------|------|
| 微信通知 | wechat_enabled | ServerChan 推送开关 |
| ServerChan Key | server_chan_key | 微信推送密钥 |
| PushPlus | pushplus_enabled / pushplus_token | PushPlus 推送 |
| 自动执行 | auto_execute_enabled | 做T 信号达到阈值自动执行开关 |

### 2.4 语言 / 备份

- 语言：中文/英文，`LanguageManager.applyLanguage` 全局生效。
- 备份：`BackupManager`（首次启动引导选择备份目录，见 MainActivity）。

## 三、相关文档

- [LLM 架构（legacy）](../agent/llm-architecture.md)
- [后台服务架构](../background/background-services.md)
