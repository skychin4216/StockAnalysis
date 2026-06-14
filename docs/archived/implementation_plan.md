# Implementation Plan — 智能对话引擎 v2.0

Date: 2026-06-07
Branch: dev

## 1) 方案评估与升级决策

### Copilot 原方案 (d665391) 评估
| 方面 | 结论 |
|------|------|
| KSP 问题根因 | ✅ 正确诊断：DTO 放主模块触发 Room KSP 扫描崩溃 |
| 模块化拆分 | ✅ RemoteAiClient/RetrofitFactory → 独立模块 |
| 对话速度优化 | ❌ 完全缺失 |
| 意图预判/后台预测 | ❌ 完全缺失 |
| 核心定位偏差 | 把 AI 当数据库查询工具，而非对话引擎 |

### 新方案：参考豆包AI 架构
豆包核心优势：首 Token < 500ms、打字时预判意图、对话后后台静默预测、分层缓存。

---

## 2) 实施步骤（5 步）

### Step 1: ConnectionPreWarmPool（连接预热池）
- **文件**: `app/src/main/java/com/chin/stockanalysis/ai/ConnectionPreWarmPool.kt`
- **改动**: `OpenAiCompatibleProvider.kt` 集成预热池
- **效果**: 首 Token 延迟 -200~300ms（消除 TCP/TLS 握手）

### Step 2: SmartContextWindow（智能上下文缓存）
- **文件**: `app/src/main/java/com/chin/stockanalysis/ai/SmartContextWindow.kt`
- **改动**: `ChatTabFragment.kt` 使用缓存代替每次重建 systemPrompt
- **效果**: 消息发送前延迟 -500ms~1s

### Step 3: 并行化 onMessageComplete
- **改动**: `ChatTabFragment.kt` — 记忆提取 + 追问生成在 AI 回复流式输出时并行执行
- **效果**: 追问建议提前 1-2s 出现

### Step 4: IntentPredictionEngine（意图预判引擎）
- **文件**: `app/src/main/java/com/chin/stockanalysis/ai/IntentPredictionEngine.kt`
- **改动**: `ChatTabFragment.kt` — 用户输入 ≥3 字符时后台并行识别意图
- **效果**: 感知智能度大幅提升，提前准备上下文

### Step 5: BackgroundPredictor（后台静默预测）
- **文件**: `app/src/main/java/com/chin/stockanalysis/ai/BackgroundPredictor.kt`
- **改动**: `ChatTabFragment.kt` — 对话完成后后台执行预测任务
- **效果**: 豆包级智能体验

---

## 3) 架构总览

```
ChatTabFragment
├── IntentPredictionEngine  ← 用户打字时并行运行
├── SmartContextWindow      ← 分层缓存(内存+DB)
├── BackgroundPredictor     ← 对话完成后后台运行
├── OpenAiCompatibleProvider (集成 ConnectionPreWarmPool)
└── KeyMemoryManager (现有，并行化)
```

---

## 4) 文件变更清单

### 新增文件
- `app/src/main/java/com/chin/stockanalysis/ai/ConnectionPreWarmPool.kt`
- `app/src/main/java/com/chin/stockanalysis/ai/SmartContextWindow.kt`
- `app/src/main/java/com/chin/stockanalysis/ai/IntentPredictionEngine.kt`
- `app/src/main/java/com/chin/stockanalysis/ai/BackgroundPredictor.kt`

### 修改文件
- `app/src/main/java/com/chin/stockanalysis/OpenAiCompatibleProvider.kt` — 集成预热池
- `app/src/main/java/com/chin/stockanalysis/ui/ChatTabFragment.kt` — 集成全部新组件
- `docs/implementation_plan.md` — 更新（本文件）
- `docs/README.md` — 更新架构说明

---

## 5) 中优先级后续任务
- 将 DTO + Retrofit 移入独立 `feature/ai-analyze` 模块
- 添加 AI 调用成本/延迟/失败率监控 dashboard
- WebSocket 支持（替代当前 HTTP 轮询式流式）