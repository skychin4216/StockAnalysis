# AI 对话框多 Agent 重构设计（v2）

> 目的：先把 CodeBuddy 的多 Agent 实现机制整理清楚，再对照本 App 现状，
> 设计一套可落地的「AI 对话框 → 多专家协作工作台」架构。
> 日期：2026-09-06

---

## 1. CodeBuddy 的多 Agent 是怎么实现的

CodeBuddy（AI IDE / CLI Agent）本质是一个 **Agent Loop（感知→规划→行动→观察）**
之上的多 Agent 协作系统，关键机制如下：

| 机制 | 实现要点 | 对我们的启示 |
|---|---|---|
| **Agent = 提示词 + 工具集 + 独立上下文** | 每个 Agent 是「专用 system prompt + 允许的工具 + 自己的对话/工具历史」的组合。用户可增删 Agent（目录里的 `.md` 定义，frontmatter 写 name/description/tools，正文即 instructions） | 我们把「专家」抽成纯声明式配置 + 执行器，UI 才能动态增删 |
| **主代理 + 子代理（Subagents）** | 主代理判断任务复杂/跨领域时，用 Task 委派给专用子代理（如 code-explorer：只读探索、结果精简回传），子代理有独立上下文窗口，不污染主代理 | 对话里"深度分析"不该全部塞进一条长 prompt，应按领域拆子任务、分窗口执行再合并 |
| **异步团队（Team Mode）** | 多个成员并行跑：消息总线（mailbox）、`send_message/broadcast`、成员可持久化复活、可跨文件协作 | 「多专家并行出观点→汇总」就是其对话版：板块/技术/新闻/风险 4 个专家可并行分析再聚合 |
| **技能（Skills）即复用单元** | 技能 = 文档 + 可选脚本，按需加载进上下文（如本仓库 `.codebuddy/skills/stock-daemon`），LLM 只在需要时读它 | 我们的 usecase XML / skill 配置可作为专家「知识挂件」，对话中按需注入，省 token |
| **权限与交互协议** | 工具执行有审批/只读开关（canUseTool），复杂任务先 plan 再执行；工具结果作为观察回注循环 | 对话框里「先给方案 → 确认 → 执行工具」比"一问就全自动干"更可信 |
| **记忆与状态** | 会话历史、自动化任务、团队历史都可持久化/恢复 | 专家应有独立会话记忆（prompt 增量、持仓状态、风格偏好），不随主会话清空 |

一句话总结 CodeBuddy 的多 Agent 范式：
**一个带工具的协调主代理 + 一组可声明、可委派、可并行的领域子代理 + 明确的协作协议（委派/消息/审批）+ 按需加载的知识。**

---

## 2. 本 App 现状盘点（三套"准多 Agent"并存）

### 2.1 老一代：Skill 迁移的角色 Agent（可用户增删）
- `agent/Agent.kt`（数据模型：id/name/icon/description/quickPrompt/systemPrompt/triggerKeywords/source/usageCount）
- `agent/AgentManager.kt`：SharedPreferences 持久化 CRUD；首次启动把 `skill/stock_picking/SkillConfigLoader` 的 skill 配置迁移成 Agent（v1）→ 后续 v2/v3 修正升级
- `agent/AgentPickParser.kt`：解析 Agent 对话输出 → 提取选股
- UI：`AgentChatFragment.kt`(26KB, 选角色对话框) + 聊天 `ChatAdapter`；另有智能体管理列表页
- 特点：**对话是"单角色 prompt 问答"，无工具循环、无多 Agent 协作**；角色即 prompt，一次一问。

### 2.2 新一代：工具化 ReAct Agent（框架 + 子 Agent + 编排）
- `agent/framework/AgentBase.kt` + `AgentTool.kt`：工具注册、ReAct 循环、AgentContext 会话上下文、AgentResult/steps
- `agent/chat/ChatAgent.kt`：对话入口。`registerTool(StockQuery/MarketBrief/IntentParse/LeaderPoolManage/AiSelection/SectorRotation/PortfolioHealth)`；`handleMessage()` 先 `detectIntent`，再按意图分支：
  - `INDEX_ANALYSIS` → `IndexAnalysisAgent`（指数技术面）
  - `STOCK_PICKING` → `StockPickingAgent.pickStocks()`（Plan-and-Execute）
  - `STOCK_ANALYSIS` → `AgentOrchestrator.analyzeStock(mode: QUICK/DEEP/EXPERT)`（**多 agent 流水线深度分析**，DEEP=多 agent 流水线）
  - `MARKET_BRIEF` → 本地生成
  - 其它 → `react(userMessage, maxSteps=4)` 工具 ReAct
- `agent/stock/StockAnalysisAgent`、`StockPickingAgent`、`agent/chat/PlanAgent.kt`、`DeepPipelineTail`、`QuickBuildExpertRunner`（DAG 一键构建专家）
- UI：`ChatTabFragment.kt`(98KB！) 新一代快捷对话页（AnalysisMode 快速/深度/专家切换）

### 2.3 深度编排层
- `agent/core/AgentOrchestrator`：QUICK→DEEP(多 agent 流水线)→EXPERT 模式；`core/DeepAnalystEngine` 多子 agent（价值/趋势/风险…）
- `agent/router/ChatRouter`、`agent/news`、`agent/pipeline`、`agent/v2`、`agent/autoquant` 等按域分包

### 2.4 现状问题（重构理由）
1. **两代并存、职责重叠**：老 Agent（prompt 问答）与新 ChatAgent（工具 ReAct）双入口，AgentChatFragment 与 ChatTabFragment 两套聊天 UI。
2. **ChatAgent 的"多 Agent"是 if-else 硬编码意图路由**：加一个专家 = 改 handleMessage 加分支 + buildSystemPrompt 加一段 + UI 加按钮，全部硬耦合。
3. **无协作协议**：没有"并行投票/链式委派/确认后执行"的概念；专家之间无法互相引用结果。
4. **专家无状态**：quickPrompt 每次全量注入，无记忆/用量/个性化。
5. **ChatTabFragment 98KB 上帝类**：渲染+逻辑+状态全堆一个文件。

---

## 3. 目标架构：专家注册表 + 编排器 + 协作协议（映射 CodeBuddy 范式）

### 3.1 概念层（新增/重构 4 个概念）

```
┌─ ChatCoordinator（协调主代理 = 现在的 ChatAgent，重构为编排器）
│     用户消息 → 意图/专家识别 → 选择执行模式 → 汇总流式回复
│
├─ AgentHub（专家注册表，对应 CodeBuddy 的 agents 声明）
│     [ChatExpertSpec(id,name,icon,desc,trigger,invoke,stateful)]
│
├─ ChatExpert（专家 = 领域执行器，对应子 Agent）
│     e.g. 技术面专家 / 基本面 / 指数 / 板块轮动 / 选股 /
│           ETF·资金 / 新闻情报 / 风险合规 / DAG 自选
│
└─ Orchestrator（协作协议，对应 Team/Send-Message）
      直答 | 链式(前一专家输出喂给下一专家) | 并行投票(多专家同题→聚合)
```

### 3.2 专家注册表设计（核心新增，先行落地的 P0）

```kotlin
// agent/hub/ChatExpert.kt
data class ChatExpertSpec(
    val id: String, val name: String, val icon: String,
    val domain: ExpertDomain,          // enum: TECHNICAL/FUNDAMENTAL/INDEX/SECTOR/
                                       //      PICKING/ETF_FLOW/NEWS/RISK/MACRO/DAG
    val description: String,
    val triggers: List<String>,        // 触发关键词，供意图识别用
    val maxSteps: Int = 0,             // 0=单轮执行；>0=允许该专家用工具自循环
    val stateful: Boolean = false,     // 是否保留独立会话记忆
)
interface ChatExpert {
    val spec: ChatExpertSpec
    /** 专家执行入口：返回可流式的回复块 */
    suspend fun invoke(userMessage: String, ctx: ExpertContext): ExpertReply
}
data class ExpertContext(val shared: AgentContext, val memory: ExpertMemory? = null)
data class ExpertReply(val text: String, val data: Any? = null, val tookMs: Long = 0)
```

### 3.3 内置专家清单（首批 10 个，全部映射到"已存在的执行器"，零新 AI 成本）

| id | 专家 | 复用现有实现 | 触发示例 |
|---|---|---|---|
| technical | 个股技术面 | `StockAnalysisAgent` + AgentOrchestrator(QUICK/DEEP) | "分析 600519 技术面" |
| sector | 板块轮动 | SectorRotationTool / 龙头图谱 / ETF 重仓 | "今天哪个板块强" |
| index | 指数与大盘 | `IndexAnalysisAgent` | "上证指数走势" |
| picking | 一键选股 | StockPickingAgent + AiSelectionTool | "帮我选股" |
| etf_flow | ETF·资金流 | SectorRotationTool + PcCandidates(etf_holdings) | "ETF 低吸" |
| news | 情报雷达 | agent/news（机构线索/消息驱动） | "有什么消息" |
| portfolio | 持仓健康 | PortfolioHealthTool | "我的持仓怎样" |
| macro | 宏观事件 | agent/macro（事件日历） | "下周宏观事件" |
| risk | 风险合规 | agent/risk | "帮我排雷" |
| dag | DAG 量化 | QuickBuildExpertRunner / usecase XML | "构建季度策略" |

### 3.4 编排模式（Orchestrator.execute）

```
execute(userMsg):
  1. 识别：AgentHub.match(userMsg) → 命中 n 个专家 + 置信度
      命中唯一 → 直答模式（single）
      命中多个/意图不明 → ask 澄清（让用户点选）或协调者 react 兜底
  2. 模式选择：
     SINGLE   → expert.invoke(userMsg)               # 一般问答
     CHAIN    → 按 pipeline 定义顺序调用多专家，前输出进后 ctx   # 如"宏观→板块→选股"
     COUNCIL  → 并行 launch{ expert.invoke() }，Join 后统一聚合   # 深度/多空分歧时
     CONFIRM  → 专家先给方案（含工具预算/影响），用户点"执行"才跑重工具
  3. 返回：统一 ExpertReply + trace: [agentId×N]（UI 渲染"调用链"）
```

### 3.5 会话与记忆（对齐 CodeBuddy memory）
- 每个专家可选 `ExpertMemory`（SharedPreferences/文件）：最近偏好、关注池、上次结论
- 主会话历史继续由 ChatAgent session 管理；`trace` 写入该轮消息 data 供 UI 展示徽标

### 3.6 UI 对齐（阶段化）
- 聊天输入框上方：横向专家 chip 选择（来自 AgentHub 动态渲染）——不再硬编码
- 每条回复头部：该轮参与专家徽标（icon + id），折叠 trace
- 老 AgentChatFragment：改造为直接驱动 AgentHub；ChatTabFragment 98KB 逐步把「逻辑」迁到 Hub/Expert，UI 只留渲染

---

## 4. 落地路线（P0/P1/P2）

### P0 —— 注册表 + 选择器打通（先做，改动最小、价值最高）
1. 新增 `agent/hub/`：`ChatExpert.kt`（spec+interface）、`ExpertDomain.kt`、`AgentHub.kt`
   （内置 10 专家的声明 + 到现有执行器的 invoke 适配器）
2. 把 `ChatAgent` 的意图硬编码分支改写成 `when(命中专家)`，未命中走 react 兜底
   —— 行为不变，但"加专家=往 AgentHub 加一行"
3. UI：ChatTabFragment 输入框上方加专家 chips（读 AgentHub），选则 `ctx.forceExpert=id`
4. `ExpertReply.trace` 渲染：消息文本前加 `[icon name]` 行

### P1 —— 协作协议
- `COUNCIL` 并行聚合（技术+板块+ETF 同问一题 → 汇总表）
- `CHAIN` pipeline 声明（macro→sector→picking）
- `CONFIRM` 预执行审批（调用重工具前先出方案）

### P2 —— 记忆与老架构收编
- ExpertMemory 持久化；老 AgentManager/Agent 数据并入 AgentHub（Agent = 用户自建专家）
- ChatTabFragment 拆分为组件化 UI；双聊天入口统一
- 新增"团队模式"（多专家同屏讨论），对齐 Team Mode 消息总线

### 验收指标
- 加一个专家 = 注册一行 + 写 invoke，无需改 ChatAgent/UI 逻辑
- 深度问题回复 = 多专家徽标可回溯调用链
- OOS 行为回归：QuickBuild 专家、机构情报、一键选股等现有能力不降级

---

## 5. 本仓库 CodeBuddy 侧的对应关系（顺便自查）
- `.codebuddy/skills/stock-daemon`、`adb-stock-data-commands`：即 CodeBuddy 技能（本仓库正在用）
- CodeBuddy Agent SDK Web 模板（`init-cbc-sdk-web` skill）：Express+WebSocket 的对话壳，若未来做 PC 端 Web 版可参考其 session 管理
