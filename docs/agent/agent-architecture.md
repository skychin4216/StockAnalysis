# 项目 Agent 架构

> 最后更新：2026-08-13 | 代码位置：`app/src/main/java/com/chin/stockanalysis/agent/`

---

## 一、定位

Agent 层是「智能体 Tab」与「AI 对话」的大脑：将用户一句话路由为不同深度的分析任务，派生多个子 Agent 并行分析，最终汇总为可执行报告（含选股/买卖/风控建议）。

```
用户输入
  │
  ▼
IntentRouter（确定性路由，0 token）
  ├── QUICK_SCAN      → 仅 Scout（纯量化）
  ├── DEEP_ANALYSIS   → Scout + Analyst 集群 + Guardian
  ├── RISK_CHECK      → 仅 Guardian
  ├── FOLLOW_UP       → 仅 Analyst
  └── GENERAL_CHAT    → 单次 LLM 问答
  │
  ▼
AgentOrchestrator（场景编排）
  ├── AgentSessionManager（会话/取消管理）
  ├── SubAgentSpawner（并行派生子 Agent）
  ├── PlanningAgent（深度分析规划）
  └── V2 决策矩阵（仓位水阀/利润质量/最终决策）
  │
  ▼
OrchestratorResult（汇总报告）
```

---

## 二、模块清单

```
agent/
├── Agent.kt                 — Agent 数据类 + AgentPick 选股结果
├── AgentManager.kt          — Agent CRUD + SharedPreferences 持久化
├── AgentPickParser.kt       — 从 LLM 回复中解析选股 JSON
├── core/                    — 编排核心
│   ├── AgentOrchestrator.kt — 场景编排者（入口）
│   ├── IntentRouter.kt      — 意图路由 + AgentClusterConfig
│   ├── AgentRole.kt         — 角色枚举（Scout/Analyst/Guardian/Executor）
│   ├── SubAgentSpawner.kt   — 并行派生子 Agent
│   ├── AgentSessionManager.kt — 会话生命周期
│   ├── PlanningAgent.kt     — 深度分析规划（DeepAnalystEngine）
│   └── ...                  — AgentContext / Announce 等
├── framework/               — 框架层
│   ├── AgentBase.kt         — Agent 基类（ReAct + Plan-and-Execute）
│   ├── AgentTool.kt         — 工具接口
│   └── ...                  — AgentResult / AgentMemory 等
├── chat/                    — 对话 Agent（ChatAgent / ChatManager）
├── news/                    — 新闻分析 Agent
├── pipeline/                — Pipeline 专家 Agent
├── risk/                    — 风控 Agent
├── router/                  — 交易路由
├── stock/                   — 选股/交易执行 Agent
└── v2/                      — V2 决策矩阵
    ├── V2DecisionMatrix.kt  — 最终决策矩阵
    ├── PositionWaterValve.kt— 仓位水阀（根据环境限制仓位）
    ├── MarketEnvironment.kt — 市场环境分类
    ├── ProfitQualityAnalyzer.kt — 利润质量分析
    └── FinalDecision.kt     — 最终决策数据类
```

---

## 三、核心类详解

### 3.1 Agent（数据模型）

```kotlin
data class Agent(
    val id: String,
    val name: String,
    val icon: String = "🤖",
    val description: String,       // 角色说明
    val quickPrompt: String,       // 触发指令（用户可编辑）
    val systemPrompt: String,      // 全自动执行规则（AI 生成）
    val triggerKeywords: List<String>,  // 触发关键词
    val enabled: Boolean,
    val usageCount: Int,
    val source: AgentSource        // USER_CREATED / FROM_SKILL / AUTO_GENERATED
)
```

- 智能体是「选股 Skill 的升级版」：Skill 只是 prompt 注入，Agent 拥有独立对话上下文。
- 首次启动从 `skill_config.json` 自动迁移默认 Agent；v2/v3 升级会修正脏数据并补齐缺失的 pipeline agents。
- 选股结果保存为 `AgentPick(rank, stockCode, stockName, reason, confidence, sourceAgentId)`，供策略与模拟交易优先使用。

### 3.2 AgentManager

- 存储：SharedPreferences `agent_prefs` → `agents_json`。
- 能力：register / remove / setEnabled / get / getAll / getEnabled / create / update / recordUsage。
- ID 生成：`agent_<拼音化名称>_<时间戳%100000>`。

### 3.3 AgentOrchestrator（编排者）

入口方法：

```kotlin
suspend fun execute(
    intent: UserIntent,        // 已路由的意图
    stockCode: String? = null, // 目标股票（null=全市场）
    stockName: String? = null,
    onProgress: ((String, String) -> Unit)? = null
): OrchestratorResult
```

按 `intent.type` 分流：
- `QUICK_SCAN` → 仅 Scout，纯量化，无 LLM
- `RISK_CHECK` → 仅 Guardian 风控扫描
- `FOLLOW_UP` → 仅 Analyst 追问
- `DEEP_ANALYSIS` → 全链路：Scout 侦察 → Analyst 集群（技术/基本面/舆情/赛道/产业链）→ Guardian 复核 → V2 决策矩阵
- `GENERAL_CHAT` → 单次 LLM 通用问答

### 3.4 IntentRouter（意图路由器）

确定性路由（无需 LLM），优先级：
1. `RISK_CHECK`：含「风险/止损/止盈/风控/持仓安全/仓位」
2. `QUICK_SCAN`：含「快速/概览/扫描/市场怎么样/大盘/盘面」
3. `FOLLOW_UP`：含「为什么/追问/详细/解释」且已有当前股票
4. `GENERAL_CHAT`：闲聊/知识问答/无股票上下文
5. 默认 `DEEP_ANALYSIS`：从文本推断 HoldingPeriod（超短/短/中/长）

### 3.5 AgentClusterConfig（按周期配置集群）

| 周期 | 编排超时 | Analyst 步数 | Analyst 超时 | Guardian 超时 | 缓存TTL | 最大并发 |
|------|---------|-------------|-------------|--------------|--------|---------|
| ULTRA_SHORT | 120s | 3 | 90s | 5s | 5min | 6 |
| SHORT | 180s | 5 | 150s | 15s | 15min | 10 |
| MID | 240s | 6 | 210s | 30s | 60min | 12 |
| LONG | 240s | 6 | 210s | 30s | 120min | 12 |

### 3.6 AgentBase（Agent 基类）

两种执行模式：
- **ReAct（推理-行动循环）**：适合探索/选股。支持原生 Function Calling（tool_calls）与 JSON Prompt 约定两种模式，最多 8 步。
- **Plan-and-Execute（规划-执行）**：适合目标明确的交易执行。LLM 先产出计划（TOOL/LLM/SUB_AGENT 步骤），再逐步执行，失败可动态调整。

LLM 调用统一走 `AiProviderPool.acquire()`（25s 超时），结束 `releaseNonBlocking()`。
记忆：短期记忆保留最近 20 条；`AgentMemory(timestamp, type, input, output, steps)`。

### 3.7 AgentTool（工具接口）

```kotlin
interface AgentTool {
    val name: String
    val description: String      // 给 LLM 看
    val parameters: List<String>
    suspend fun execute(params: Map<String, String>, ctx: AgentContext): String
}
```

标准工具（ChatTools）：`stock_query` / `sector_query` / `market_brief`。

### 3.8 V2 决策矩阵

- **MarketEnvironment**：市场环境分类（影响仓位上限）
- **PositionWaterValve**：仓位水阀——根据环境与风险将建议仓位限制在安全区间
- **ProfitQualityAnalyzer**：利润质量分析（区分可持续利润与一次性收益）
- **FinalDecision / V2DecisionMatrix**：融合多 Agent 结果给出最终决策（买/卖/持有 + 仓位 + 理由）

---

## 四、执行时序（DEEP_ANALYSIS 示例）

```
User
 │ 输入："分析一下 600519，中线"
 ▼
IntentRouter ──→ DEEP_ANALYSIS + period=MID + target=600519
 ▼
AgentOrchestrator.execute()
 ├─ AgentSessionManager.createSession()
 ├─ AgentClusterConfig.forPeriod(MID)   // 6 步分析师，240s 超时
 ├─ Scout 侦察：拉取行情/K线/新闻/资金流（并行）
 ├─ PlanningAgent 规划分析步骤
 ├─ 并行派生 Analyst 集群：
 │    ├─ 技术面分析师（MA/RSI/BOLL/形态）
 │    ├─ 基本面分析师（PE/ROE/营收）
 │    ├─ 舆情分析师（新闻/公告）
 │    ├─ 赛道分析师（板块/龙头）
 │    └─ 产业链分析师（上下游）
 ├─ 收集所有 Announce，汇总
 ├─ Guardian 风控复核（止盈止损/仓位约束）
 ├─ V2DecisionMatrix 融合 → FinalDecision
 └─ AgentSessionManager.cancelSession()
 ▼
OrchestratorResult（报告 → UI 展示）
```

---

## 五、与 Skill / 选股的关系

- `skill/stock_picking/SkillConfigLoader` 加载选股 Skill 配置 → 首次启动迁移为 Agent。
- Agent 对话中 AI 选出的股票 → `AgentPickParser` 解析 → `AgentPick` → `StockDataCenter.SkillPickEntry` → 策略/模拟交易优先。
- 智能体 Tab 支持用户创建自定义 Agent，`quickPrompt` 即用户自编写的触发指令。

---

## 六、相关文档

- [LLM 架构（legacy）](llm-architecture.md)
- [AI 对话框架](ai-conversation-framework.md)
- [智能体 TAB 架构](../ui/agent-tab-architecture.md)
- [后台服务架构](../background/background-services.md)
