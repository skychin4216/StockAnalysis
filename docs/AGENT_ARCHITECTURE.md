# 智能体 (AI Agent) 架构设计

## 概述

将每个选股技巧（Skill）升级为独立智能体（Agent），每个智能体拥有独立的对话上下文，
智能体选出的股票保存到 `skill_pick_stocks` 数组，供后续策略和模拟交易优先使用。

## 架构变化

### 1. 主页面 Tab 布局调整
```
原: 对话(0) | 股票(1) | 策略(2) | 我的(3)
新: 对话(0) | 智能体(1) | 股票(2) | 策略(3) | 我的(4)
```

### 2. 智能体模型 (Agent)
```
Agent {
  id: String          // 唯一标识
  name: String        // 名称
  icon: String        // 图标
  description: String // 设定描述
  systemPrompt: String // 输入指令（核心prompt）
  triggerKeywords: List<String> // 触发关键词
  enabled: Boolean
  createdAt: Long
}
```

### 3. 数据流
```
AgentTabFragment (用户选择智能体)
  → AgentChatSession (独立对话上下文)
  → AI 分析返回选股结果
  → 解析股票代码 → StockDataCenter.addSkillPicks()
  → skill_pick_stocks 数组

策略/模拟交易执行时:
  → SimulationTradeEngine.selectTop3()
  → 检查 StockDataCenter.getSkillPickStockCodes()
  → 加权优先 (skill_pick_stocks 股票获得额外加分)
```

### 4. ChatTabFragment 变化
- 移除 SkillOrchestrator 的调用
- 保持通用 AI 对话能力
- 不再在对话中自动触发 Skill 执行

### 5. 文件清单

#### 新建文件:
- `app/src/main/java/com/chin/stockanalysis/agent/Agent.kt` - 智能体数据模型
- `app/src/main/java/com/chin/stockanalysis/agent/AgentManager.kt` - 智能体管理器 (CRUD + 持久化)
- `app/src/main/java/com/chin/stockanalysis/agent/AgentPickParser.kt` - 解析AI回复中的选股结果
- `app/src/main/java/com/chin/stockanalysis/agent/AgentChatSession.kt` - 智能体对话会话
- `app/src/main/java/com/chin/stockanalysis/ui/AgentTabFragment.kt` - 智能体 Tab UI
- `app/src/main/java/com/chin/stockanalysis/ui/AgentChatFragment.kt` - 智能体对话界面
- `app/src/main/java/com/chin/stockanalysis/ui/AgentListAdapter.kt` - 智能体列表适配器
- `app/src/main/res/layout/fragment_agent_tab.xml` - 智能体 Tab 布局
- `app/src/main/res/layout/fragment_agent_chat.xml` - 智能体对话布局
- `app/src/main/res/layout/item_agent.xml` - 智能体列表项布局
- `app/src/main/res/drawable/ic_nav_agent.xml` - 智能体导航图标

#### 修改文件:
- `app/src/main/java/com/chin/stockanalysis/ui/MainActivity.kt` - 添加智能体Tab
- `app/src/main/res/menu/nav_menu.xml` - 添加智能体菜单项
- `app/src/main/java/com/chin/stockanalysis/ui/ChatTabFragment.kt` - 移除 Skill 调用
- `app/src/main/java/com/chin/stockanalysis/stock/database/StockDataCenter.kt` - 增强 skill_pick_stocks 集成
- `app/src/main/java/com/chin/stockanalysis/strategy/trade/SimulationTradeEngine.kt` - 优先使用 skill_pick_stocks