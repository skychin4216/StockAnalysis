# 个人技能引擎模块 (skill/)

## 架构

```
skill/
├── README.md
├── Skill.kt                      # 技能数据模型
├── SkillEngine.kt                # 技能引擎（CRUD + 持久化 + 动态创建）
├── prediction/
│    ├── FollowUpGenerator.kt      # 智能追问生成器（带来源标注）
│    └── FollowUpReason.kt         # 追问原因枚举
└── stock_picking/                 # ★ 选股技巧库
    ├── README.md
    ├── BaseStockPickingSkill.kt          # 选股技巧基础类（统一 createSkill / shouldTrigger）
    ├── StockPickingSkillDoubao1.kt       # 豆包选股技巧1（硬编码 fallback）
    ├── StockPickingSkillDoubao2.kt       # 豆包选股技巧2（硬编码 fallback）
    ├── SkillIntentDetector.kt            # 动态创建 Skill 的意图解析器
    └── SkillConfigLoader.kt              # ★ 设定档载入器（JSON → Skill）
```

```
assets/
└── skills_config.json             # ★ 内建 Skill 定义（随 App 发布，唯读）
```

```
内部储存 (filesDir)/
└── skills_dynamic.json            # ★ 动态 Skill 定义（AI 对话建立，重启保留）
```

## 数据流（v10.1 资料驱动架构）

```
App 启动
  └─ SkillEngine.registerDefaults()
       ├─ 1. 硬编码基础 Skill（早盘关注、尾盘异动）
       ├─ 2. SkillConfigLoader.loadAllSkills()
       │    ├─ assets/skills_config.json  → 内建选股 Skill
       │    └─ files/skills_dynamic.json  → 动态建立的 Skill
       │       └─ 合并去重（动态优先覆盖内建同 ID）
       └─ 3. Fallback: 硬编码 Skill（设定档损坏时）

AI 对话中动态建立
  └─ SkillEngine.createDynamicSkill()
       ├─ 写入内存 (skills map)
       ├─ 写入 SharedPreferences (runtime state)
       └─ 写入 files/skills_dynamic.json (定义持久化) ← ★ 新增
```

## 新增 Skill 的三种方式

### 方式 1: 修改 `assets/skills_config.json`（需重新编译）

编辑 `app/src/main/assets/skills_config.json`，在 `skills` 阵列中添加新项目：

```json
{
  "skills": [
    {
      "id": "stock_picking_doubao_1",
      "name": "豆包选股技巧1 - 寻找细分领域隐形冠军",
      "icon": "🎯",
      "description": "通过逆向工程拆解BOM...",
      "keywords": ["选股", "BOM", "隐形冠军"],
      "fullPrompt": "你是产业研究专家...",
      "quickPrompt": "请使用豆包选股技巧1...",
      "autoTrigger": true
    }
  ]
}
```

**特点：** 无代码，纯 JSON，prompt 中使用 `{stockCode}` 占位符

### 方式 2: AI 对话动态建立（无需编译，即时生效）

在 AI 对话中输入：

```
新增一个选股技能：名称为"均线金叉筛选"，触发关键词为"均线,金叉,MA"，提示词为：请根据5日均线上穿20日均线筛选股票
```

或结构化指令：

```
/create-skill name=均线金叉筛选 keywords=均线,金叉,MA prompt=根据5日均线上穿20日均线筛选股票
```

**特点：** 无需任何代码，自动写入 `skills_dynamic.json`，重启后保留

### 方式 3: 编写硬编码 Kotlin 文件（仅作为 fallback）

```kotlin
object MyNewSkill : BaseStockPickingSkill() {
    override val skillId = "my_new_skill"
    override val skillName = "我的新技巧"
    // ...
}
```

**特点：** 设定档损坏时的安全网；推荐优先使用方式 1

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

### 3. 选股技巧库 (stock_picking/)

#### 3a. BaseStockPickingSkill（基础类）

所有内建选股 Skill 的基类，统一提供 `createSkill()` / `shouldTrigger()` 样板方法。新增内置 Skill 只需：

1. 创建 `object Xxx : BaseStockPickingSkill()`，覆写 metadata + prompt
2. 在 `SkillEngine.registerDefaults()` 的 `builtinSkills` 清单中加入一行

代码量从 ~100 行缩减到 ~20 行。

#### 3b. 动态创建 Skill（SkillIntentDetector）

用户可在 AI 对话框中动态创建新 Skill，两种方式：

**方式 1：自然语言**
```
新增一个选股技能：名称为"均线金叉筛选"，触发关键词为"均线,金叉,MA"，提示词为：请根据5日均线上穿20日均线筛选股票
```

**方式 2：结构化指令**
```
/create-skill name=均线金叉筛选 keywords=均线,金叉,MA prompt=根据5日均线上穿20日均线筛选股票 desc=使用双均线策略筛选
```

Skill 会自动持久化到 SharedPreferences，重启后依然可用。

| 技巧 | 说明 |
|------|------|
| 豆包选股技巧1 | 通过 BOM 拆解寻找细分领域隐形冠军 |
| 豆包选股技巧2 | 大A咽喉卡点拆解，四阶段筛选 |

详见：[stock_picking/README.md](./stock_picking/README.md)

### 4. 持久化

- SharedPreferences JSON 格式存储
- 支持注册/删除/启用/禁用
- 自动触发时间段配置
- 动态创建的 Skill 自动持久化