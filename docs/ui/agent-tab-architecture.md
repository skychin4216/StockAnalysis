# 智能体 TAB 架构

> 最后更新：2026-08-13 | 代码位置：`ui/AgentTabFragment.kt`、`ui/AgentChatFragment.kt`、`ui/AgentListAdapter.kt`、`agent/`

---

## 一、入口层级

```
MainActivity Tab[1] 智能体
└── AgentTabFragment
    ├── 搜索框（按名称/描述/触发关键词过滤）
    ├── Agent 列表（按 usageCount 降序）
    ├── ➕ 创建智能体（btnAddAgent）
    └── Agent 对话页（AgentChatFragment，replace 到 layoutAgentChat）
        ├── 聊天
        ├── 设置（编辑 Agent）→ showEditDialog
        └── 返回（closeAgentChat）
```

## 二、页面结构

### 2.1 AgentTabFragment

| 元素 | 说明 |
|------|------|
| `rvAgentList` | RecyclerView + `AgentListAdapter`，点击项打开对话，长按/更多弹出 `AgentPopupMenu` |
| `etSearchAgent` | 搜索过滤（名称/描述/触发关键词） |
| `btnAddAgent` | 创建智能体，弹 `showCreateDialog` |
| 更多菜单 | 编辑 / 启用-禁用 / 删除 / 统计 |

- 空列表提示：`暂无智能体，点击创建`。
- 排序规则：`getAll().sortedByDescending { it.usageCount }`。

### 2.2 AgentChatFragment

- `AgentChatFragment.newInstance(agent)` 携带 Agent 打开对话。
- `onBackClick` → `closeAgentChat()`（MainActivity 返回键同样会优先关闭智能体对话页而非退出 App）。
- `onSettingsClick` → 编辑 Agent。

## 三、与 Agent 框架的联动

1. 用户点击 Agent → `agentManager.recordUsage(id)` 记录使用次数。
2. 对话消息经 `ChatRouter`（AgentRoute=AGENT_FRAMEWORK）→ `AgentChatService` → `ChatAgent.handleMessage()`。
3. `ChatAgent` 将用户的 `quickPrompt` 作为触发指令，按意图路由到 Orchestrator 执行（选股/风控/深度分析）。
4. AI 选股结果经 `AgentPickParser` 解析 → `AgentPick` → 策略/模拟交易优先使用。

## 四、相关文档

- [项目 Agent 架构](../agent/agent-architecture.md)
- [AI 对话框架](../agent/ai-conversation-framework.md)
