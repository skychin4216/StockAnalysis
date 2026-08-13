# LLM 架构（legacy）

> 最后更新：2026-08-13 | 代码位置：`app/src/main/java/com/chin/stockanalysis/ai/` 与根包 `ApiConfigManager / OpenAiCompatibleProvider`
> 说明：本文描述「Provider/模型接入层」，属于 legacy 但仍是当前 Agent 框架的底层依赖。

---

## 一、总体分层

```
┌─────────────────────────────────────────────────┐
│ 调用方：ChatTabFragment / AgentBase / Pipeline    │
├─────────────────────────────────────────────────┤
│ AiProviderPool（共享池，占用/释放/健康检测）        │
├─────────────────────────────────────────────────┤
│ AiProviderSelector（场景 → 模型映射）              │
├─────────────────────────────────────────────────┤
│ ApiConfigManager（Provider 配置/Key/模型列表管理）  │
├─────────────────────────────────────────────────┤
│ OpenAiCompatibleProvider（HTTP 流式请求）          │
└─────────────────────────────────────────────────┘
```

## 二、核心组件

### 2.1 OpenAiCompatibleProvider

- 兼容 OpenAI Chat Completions 协议（`/v1/chat/completions`），支持流式输出。
- 能力：`sendMessageStream`（普通）/ `sendMessageStreamWithRawMessages`（带 tool_calls，Agent 专用）。
- 由 `ApiProviderConfig`（baseUrl / apiKey / model / name / id）驱动。

### 2.2 ApiConfigManager

- 管理多 Provider 配置：doubao / dashscope-qwen3 / deepseek-official / siliconflow-v3-flash。
- `getSelectedProviderId()` 用户当前选择；`getProviderModels(id)` 支持模型列表。
- Provider 配置持久化于 SharedPreferences。

### 2.3 AiProviderSelector（场景选择器）

核心：**不换 Provider，只换模型**。同一 Provider 下按场景切换模型 ID：

| 场景 | 用途 | 豆包示例模型 |
|------|------|-------------|
| CHAT_LEGACY | Legacy 对话（自然语言） | doubao-seed-2-0-pro-260215 |
| CHAT_AGENT | Agent 结构化 JSON 输出 | doubao-seed-1-6-251015 |
| PIPELINE_EXPERT | Pipeline 专家多步稳定 | doubao-seed-1-6-251015 |
| STOCK_PICKING | 选股/扫描（快） | doubao-seed-2-0-lite-260428 |
| DEFAULT | 默认 | 同 CHAT_LEGACY |

模型不在支持列表时自动回退用户默认模型。`inferScenarioFromGlobalMode()` 依据 FeatureFlag 的 `chatRoute`（LEGACY / AGENT_FRAMEWORK）推断场景。

### 2.4 AiProviderPool（v3 防冻结共享池）

- **占用管理**：`acquire(context, callerTag, timeoutMs)` 获取 Slot；`releaseNonBlocking(slot)` 释放。
- **优先级**：doubao → dashscope-qwen3 → deepseek-official → siliconflow-v3-flash。
- **超时清扫**：占用 30s 未释放自动清理；前后台切换强制清扫；进程解冻检测重置。
- **健康检测**：`isHealthy()` + 5s 探针，缓存 60s。
- **降级**：全部占用时创建临时 Provider 共享。
- **调用栈追踪**：Slot.allocatedBy 记录占用者调用栈，便于排查死锁。

### 2.5 ChatTools（Agent Function Calling 工具定义）

- 标准工具：`stock_query` / `sector_query` / `market_brief`。
- `allTools` 供 OpenAiCompatibleProvider 的 tools 参数使用。
- `isSupportedByProvider(configId)` 判断某 Provider 是否支持 Function Calling。

---

## 三、其它 AI 辅助模块（ai/ 目录）

| 类 | 职责 |
|----|------|
| AiOrchestrator | 通用 AI 调用编排（older 对话入口） |
| AiProbe | Provider 连通性探测 |
| BackgroundPredictor | 后台意图预测（跳转预判） |
| IntentDispatcher / IntentPredictionEngine | 意图识别与分发 |
| SectorDetector | 板块识别 |
| SmartContextWindow | 智能上下文窗口管理 |
| StockAIPromptBuilder | 股票分析 Prompt 构建 |
| StockAnalyzerService | 股票 AI 分析服务 |
| StockEntityExtractor | 股票实体抽取（从用户输入识别股票） |
| StrategyConfigGenerator | 策略配置生成 |
| DataCompletenessChecker | 数据完整性检查 |
| ConnectionPreWarmPool | 连接预热池 |

---

## 四、Legacy 标记说明

- `AiProviderSelector` 注释中的 `CHAT_LEGACY` 指「Legacy 对话」（自然语言对话场景），与 `AgentRoute.LEGACY` 对应。
- 当 `FeatureFlagManager.chatRoute == LEGACY` 时，对话走旧链路（AiOrchestrator + 无 Agent 编排）；`AGENT_FRAMEWORK` 时走 Agent 编排。
- Agent 框架底层仍复用 `OpenAiCompatibleProvider` / `AiProviderPool`，故本文所述接入层并非废弃代码，而是「基础能力层」。

---

## 五、相关文档

- [项目 Agent 架构](agent-architecture.md)
- [AI 对话框架](ai-conversation-framework.md)
- [后台服务架构](../background/background-services.md)
