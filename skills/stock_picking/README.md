# 选股技巧 (Stock Picking Skills)

## 架构说明

本目录包含 AI 选股技巧（Skill），通过产业研究、供应链分析、财务穿透等方式寻找优质标的。

### 两种选股来源

| pickSource | 说明 | 触发方式 |
|------------|------|----------|
| `strategy_pick` | 量化策略引擎多因子打分筛选 | 策略列表页手动执行 |
| `ai_skill_pick` | AI Skill 基于板块反向筛选 | AI 对话中输入个股时自动触发 |

### 数据流

```
用户输入「兆易創新」→ AI解析出所属板块（存储芯片、半导体...）
  ↓
SkillOrchestrator.runSkills(stockCode, sectors)
  ↓
执行所有 autoTrigger Skill，注入板块信息到 prompt
  ↓
AI 基于板块内股票池做反向筛选 → 返回符合 Skill 条件的股票
  ↓
结果注入 AI 对话回复 → SkillPick 存入 StockDataCenter 供模拟交易使用
```

---

## 技巧列表

### 技巧1：寻找细分领域隐形冠军 (stock_picking_doubao_1)

**核心逻辑**：从赛道的 BOM 清单里逆向拆解，寻找"卡脖子"垄断环节的隐形冠军。

**五个步骤**：

1. **逆向工程拆解 BOM**  
   拆解物料清单，找出底层技术门槛高、不可替代的物理机瓶颈环节

2. **筛选细分领域隐形冠军**  
   - 市值 5-30 亿美元（约 35-210 亿人民币）  
   - 细分领域隐形冠军，具有不可替代性  
   - 低成本占比、高失效风险特征

3. **穿透财报审查关键指标**  
   - 近两季度毛利率因供需失衡出现爆发性拐点  
   - CapEx（资本支出）秘密爬坡承接爆发需求

4. **空头分析师证伪报告**  
   - 技术路径替代风险  
   - 大客户自研风险  
   - 供应链断裂风险  
   列举所有可能的"死法"

5. **未来 6 个月关键可证伪里程碑**  
   拟定核心事件（订单、良率、专利），未按时发生则判定逻辑失效并清仓

---

### 技巧2：大A咽喉卡点拆解 (stock_picking_doubao_2)

**核心逻辑**：四阶段筛选——月度初筛 → 季报复筛 → 买前终审 → 持股监控。

**四个阶段**：

1. **月度初筛（大A咽喉卡点拆解）**  
   从赛道 BOM 逆向寻找"卡脖子"垄断环节  
   - 国产化率 < 20%  
   - 成本占比 < 5%  
   - 扩产周期 > 18 个月  
   - 企业属性：专精特新 / 单项冠军  
   - 市值区间：30 亿 - 150 亿中小盘标的

2. **季报复筛（财务数据硬核穿透）**  
   - 主营驱动的毛利率连续扩张  
   - CapEx / 在建工程数据持续增长  
   - 定增资金投向"卡脖子"环节  
   - 剔除：近 3 个月券商研报 > 15 篇（规避过度曝光）

3. **买前终审（AI 红队对抗证伪）**  
   站在空头视角反向证伪：  
   - 大客户是否有自研 / 引入二供计划  
   - 技术路线未来 18 个月内是否被取消  
   - 同行是否通过价格战冲击垄断地位  
   任一维度出现明确风险 → 直接回避

4. **持股监控（贝叶斯动态熔断）**  
   为标的列出未来两季度 3 个硬核里程碑（打样、量产、客户验证等）  
   设定最晚完成时限，监控互动易、公告等信息  
   以「事件 | 最晚时限 | 熔断阈值」结构建立监控表格，动态管理持仓

---

## 文件结构

```
skills/stock_picking/
├── README.md                    ← 本文档

app/src/main/assets/
└── skills_config.json            ← ★ 内建 Skill 定义（JSON，随 App 发布）

app/src/main/java/.../skill/stock_picking/
├── BaseStockPickingSkill.kt     ← 基础类（统一 createSkill / shouldTrigger）
├── StockPickingSkillDoubao1.kt  ← 技巧1（硬编码 fallback）
├── StockPickingSkillDoubao2.kt  ← 技巧2（硬编码 fallback）
├── SkillIntentDetector.kt       ← 动态创建 Skill 意图解析器
└── SkillConfigLoader.kt         ← ★ 设定档载入器（JSON → Skill）

内部储存 (filesDir)/
└── skills_dynamic.json           ← ★ 动态 Skill 定义（AI 对话建立）
```

## 新增 Skill 的三种方式

### ★ 方式 1: 修改 `assets/skills_config.json`（推荐，无需 Kotlin 代码）

编辑 `app/src/main/assets/skills_config.json`，在 `skills` 阵列中添加新项目：

```json
{
  "skills": [
    {
      "id": "my_stock_skill",
      "name": "我的选股技巧",
      "icon": "🎯",
      "description": "技巧描述",
      "keywords": ["关键词1", "关键词2"],
      "fullPrompt": "你是行业分析专家，请按照...\n\n{stockCode} 是你要分析的标的。",
      "quickPrompt": "快速提示...",
      "autoTrigger": true
    }
  ]
}
```

- `{stockCode}` 在执行时自动替换为实际股票代码
- 需重新编译 APK

### ★ 方式 2: AI 对话动态建立（无需编译，即时生效）

在 AI 对话中输入：

**自然语言：**
```
新增一个选股技能：名称为"均线金叉筛选"，触发关键词为"均线,金叉,MA"，提示词为：请根据5日均线上穿20日均线筛选股票
```

**结构化指令：**
```
/create-skill name=均线金叉筛选 keywords=均线,金叉,MA prompt=根据5日均线上穿20日均线筛选股票
```

Skill 自动写入 `skills_dynamic.json`，重启后保留。

### 方式 3: 硬编码 Kotlin（设定档损坏时的安全网）

```kotlin
object MyNewSkill : BaseStockPickingSkill() {
    override val tag = "MyNewSkill"
    override val skillId = "my_new_skill"
    override val skillName = "我的新技巧"
    override val icon = "✨"
    override val skillDescription = "这是新技巧的描述"
    override val keywords = listOf("关键词1", "关键词2")
    override fun getFullPrompt(stockCode: String?) = """..."""
    override fun getQuickPrompt() = """..."""
}