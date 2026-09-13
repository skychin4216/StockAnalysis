# Agent Framework Skill — Agent 智能体系统

## 模块职责
AI Agent 框架：意图路由、多 Agent 编排、深度分析引擎、AutoQuant 自动量化 Agent、
Agent 对话（聊天）等。对接大模型 API，为选股、研判、对话提供 AI 能力。

## 触发条件
用户提到以下关键词时，优先查阅本 Skill：
- Agent / 智能体 / AI 选股 / AI 分析
- AutoQuant / 自动量化 / 自动研判
- 对话 / Chat / 聊天没有回复
- 意图路由 / 编排 / 子 Agent

## 关键目录结构
```
app/src/main/java/com/chin/stockanalysis/agent/
├── Agent.kt / AgentManager.kt / AgentPickParser.kt
├── core/            # 核心编排
│   ├── AgentOrchestrator.kt   # Agent 编排器
│   ├── IntentRouter.kt        # 意图路由（用户意图 → Agent）
│   ├── DeepAnalystEngine.kt   # 深度分析引擎
│   ├── PlanningAgent.kt       # 规划 Agent
│   ├── SubAgentSpawner.kt     # 子 Agent 生成
│   ├── AgentContext.kt / AgentNode.kt / AgentRole.kt / StockAnalysisUseCase.kt
├── framework/       # 框架基类
│   ├── AgentBase.kt / AgentTool.kt
├── autoquant/       # AutoQuant 自动量化
│   └── AutoQuantAgentRunner.kt  # 自动量化 Agent 运行器
├── chat/            # 对话
│   ├── ChatOrchestrator.kt / QuickBuildExpertRunner.kt（一键建仓执行）
├── news/ / pipeline/ / risk/ / router/ / stock/ / v2/   # 各领域子模块
```

## 常见任务指引
### 1. AI 没回复 / 选股不执行
- 检查 `AgentManager` / `IntentRouter` 是否正确路由意图
- 检查 API Key 配置（`ApiConfigManager`）
- 检查 `AutoQuantAgentRunner` 的运行状态

### 2. 修改自动量化行为
- 改 `autoquant/AutoQuantAgentRunner.kt`

### 3. 新增 Agent 角色
- `core/AgentRole.kt` 注册新角色 + `IntentRouter` 添加意图映射

## 文件清单
- `skills/agent-framework/README.md` — 本文件（skill 定义）
