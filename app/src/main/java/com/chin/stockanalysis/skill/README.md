# 个人技能引擎模块 (skill/)

## 架构

```
skill/
├── README.md
├── Skill.kt                      # 技能数据模型
├── SkillEngine.kt                # 技能引擎（CRUD + 持久化）
└── prediction/
    ├── FollowUpGenerator.kt      # 智能追问生成器（带来源标注）
    └── FollowUpReason.kt         # 追问原因枚举
```

## 核心能力

### 1. 智能追问（FollowUpGenerator）

AI 回答结束后，自动生成追问建议，**每条追问标注来源原因**：

| 原因 | 图标 | 触发条件 |
|------|------|---------|
| 关联行业 | 📊 | 同板块/同行业股票 |
| 你的习惯 | 👤 | 历史查询模式 |
| 市场热点 | 🔥 | 当前市场热点 |
| 自然追问 | ➡️ | 从当前回答推导 |
| 时段相关 | 🕐 | 盘前/盘中/盘后场景 |
| 技能推荐 | 🏷️ | 来自个人 Skill |

### 2. 个人技能（Skill）

Skill = 命名 prompt 组合，一键执行：

```kotlin
val engine = SkillEngine(context)

// 内置默认 Skill
// "每日早盘关注" → 查看自选股价格 + ETF竞价 + 板块热度
// "尾盘异动监控" → 尾盘涨跌幅查3%的股票

// 用户自定义
engine.register(Skill(
    id = "my_strategy",
    name = "元件双雄看盘",
    icon = "🔍",
    prompts = listOf("生益科技最新股价", "沪电股份最新股价", "元件板块怎么样"),
    autoTrigger = true
))
```

### 3. 持久化

- SharedPreferences JSON 格式存储
- 支持注册/删除/启用/禁用
- 自动触发时间段配置