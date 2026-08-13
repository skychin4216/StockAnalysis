# AI 对话框架

> 最后更新：2026-08-13 | 代码位置：`ui/ChatTabFragment.kt`、`conversation/`、`agent/chat/`、`agent/router/`

---

## 一、总体架构

```
ChatTabFragment（对话 UI，豆包风格 v9.0）
   │  ChatRouter.getService()
   ▼
┌─────────────── 路由决策（FeatureFlag）───────────────┐
│ AgentRoute.AGENT_FRAMEWORK → AgentChatService        │
│                        ↓                            │
│                    ChatAgent                        │
│                        ↓                            │
│              IntentRouter / AgentOrchestrator       │
│  AgentRoute.LEGACY → LegacyChatService（抛异常）      │
│  → 调用方 fallback 到原有 ChatTabFragment 流程        │
└──────────────────────────────────────────────────────┘
   │
   ▼
AiProviderPool（共享池）→ OpenAiCompatibleProvider（流式）
   │
   ▼
ConversationRepository（Room：conversations 表）持久化
```

## 二、数据模型

### 2.1 Message（UI 消息，`ui/Message.kt`）

对话列表项，含 `role`（user/assistant）、`text`、`timestamp`、`loading`、`isStreaming` 等状态，支持流式增量渲染。

### 2.2 ConversationEntity（Room）

```kotlin
@Entity(tableName = "conversations")
data class ConversationEntity(
    val id: String,          // System.currentTimeMillis() 生成的会话 ID
    val title: String,
    val subtitle: String,
    val timestamp: Long,
    val messagesJson: String // 整个会话消息的 JSON
)
```

- 单表存所有历史会话，消息以 JSON 整体序列化（简单但非增量）。
- `ConversationRepository` 封装增删改查 + Flow 观察。

## 三、对话流程

### 3.1 发送消息

1. 用户输入 → 加入 `messages` → 渲染气泡
2. `AiProviderPool.acquire()` 获取 Provider Slot
3. 上下文组装：BASE_SYSTEM_PROMPT（StockAIPromptBuilder）+ 历史（SmartContextWindow 智能裁剪）+ 关键记忆（KeyMemoryManager）+ 新闻因子（NewsFactorManager）
4. **意图预判**（IntentPredictionEngine / BackgroundPredictor）决定是否后台预热
5. 经 `ChatRouter` 分发：
   - Agent 模式：`ChatAgent.handleMessage()` → 意图路由 → Orchestrator
   - Legacy 模式：fallback 原 ChatTabFragment 专家流程（AiOrchestrator 多 AI 并行）
6. 流式输出（80ms 节流），结束后生成追问建议

### 3.2 会话管理

- `currentConvId` 每次进入新会话重新生成；长按会话可改名。
- 自动标题：首轮回复后 `AutoTitleGenerator` 生成标题。
- 历史会话：ListView 展示，点击加载 `messagesJson`。

### 3.3 关键记忆（KeyMemoryManager）

用户可通过指令「记住 xxx」保存 `KeyMemoryEntity`，后续对话自动注入上下文（记忆 → 对话），形成长期个人画像。

## 四、多 AI 并行（AiOrchestrator）

- 同一问题可同时分发给多个 Provider/模型，取其优者或聚合。
- 与 Agent 框架关系：`AiOrchestrator` 是 legacy 并行编排；Agent 框架是结构化多角色编排。

## 五、辅助能力

| 组件 | 职责 |
|------|------|
| StockEntityExtractor | 从自然语言提取股票代码/名称 |
| SmartContextWindow | 历史裁剪，控制 token |
| SkillEngine / SkillOrchestrator | 关键词触发选股/分析技巧 |
| StockQueryEngine | 对话内股票查询 |
| CrossTabBus | 与股票/策略 Tab 通信（如选中股票） |
| TextToSpeech | 语音朗读回复 |
| 分享 | 系统分享文本；AI 分析后询问「保存到机构推荐」 |

## 六、相关文档

- [项目 Agent 架构](agent-architecture.md)
- [LLM 架构（legacy）](llm-architecture.md)
- [智能体 TAB 架构](../ui/agent-tab-architecture.md)
