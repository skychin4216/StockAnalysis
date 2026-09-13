## AIPredictionEngine 新闻因子评分集成设计

> 2026-08-13

### 问题背景

当前 AIPredictionEngine 的评分流程：

```
各策略 strength 总分 → 取 Top20 候选 → 构建 Prompt（含新闻因子文本）→ AI 输出 composite_score → applySectorBoost 后处理
```

新闻因子仅作为文本注入 Prompt，AI 可能过度关注基本面（低 PE 银行得高分）而忽略行业动量（CPI 下降利好科技）。
需要在代码层面量化新闻评分，让 AI 有明确的数值参考，而非仅靠文本理解。

### 设计方案

#### 1. NewsScoreCalculator — 量化新闻评分

新增 `NewsScoreCalculator` 对象，为每只候选股票计算新闻得分。

**匹配规则（按优先级）**：
1. stock_code 精确匹配
2. company_name 与 stockName 互相包含
3. tags 中任一标签与 stockName 互相包含
4. sector 与 stockName 互相包含（板块级匹配）

**评分公式**：
```
newsScore = Σ (sentiment × impactStrength × timeDecay)

timeDecay:
  当日新闻 → 1.0
  1-3天前  → 0.8
  4-7天前  → 0.6
  8-14天前 → 0.4
  15天+    → 0.2
```

**归一化**：将 newsScore 映射到 0-100 区间，供 Prompt 使用。

#### 2. 混合评分（HybridScore）

在候选股票表中新增 `hybridScore` 列，作为 AI 的核心参考：

```
hybridScore = strategyWeight × normalizedStrategyScore
            + newsWeight × normalizedNewsScore

strategyWeight = 0.6（默认）
newsWeight = 0.4（默认）
```

大盘环境动态调整权重：
- BULLISH: strategyWeight=0.5, newsWeight=0.5（进攻行情，消息面催化更重要）
- OSCILLATION: strategyWeight=0.6, newsWeight=0.4（均衡）
- BEARISH: strategyWeight=0.7, newsWeight=0.3（防御行情，技术面优先）

#### 3. Prompt 增强

在 Prompt 的候选股票表中新增 `新闻分` 和 `混合分` 两列，让 AI 看到明确的数值：

```
| 代码 | 名称 | 策略分 | 新闻分 | 混合分 | 命中策略 | 涨跌 |
```

同时在分析框架中新增指令：
```
composite_score 必须以混合分为基础（±10分调整），不得忽略新闻分。
新闻分 > 70 的股票，reason 中必须提及消息面催化。
新闻分 < 30 且有利的股票，需谨慎考虑。
```

#### 4. applySectorBoost 增加新闻校验

后处理阶段增加逻辑：
- 如果 newsScore > 70 且 compositeScore 偏低，额外 +5 分（新闻催化未被 AI 充分反映）
- 如果 newsScore < 20 且 sentiment = -1（有利空新闻），-5 分惩罚

### 涉及文件

| 文件 | 改动 |
|------|------|
| `predict/NewsScoreCalculator.kt` | 新增：新闻评分计算器 |
| `predict/AIPredictionEngine.kt` | 修改：集成新闻评分 + 增强 Prompt + 后处理 |
| `strategy/README.md` | 更新：记录新闻因子评分机制 |

### 数据流

```
collectCandidateStocks() → Top20 候选
    ↓
buildMultiDayFeatures() → OHLCV 特征
    ↓
newsManager.getActiveFactors() → 活跃新闻因子
    ↓
NewsScoreCalculator.score(candidates, factors) → 每只股票的新闻得分
    ↓
计算 hybridScore = 0.6×策略分 + 0.4×新闻分
    ↓
buildPredictionPrompt() → 含新闻分+混合分列的增强 Prompt
    ↓
AI 输出 composite_score（以混合分为基础）
    ↓
applySectorBoost() → 板块加权 + 新闻校验
    ↓
最终 topPicks
```
