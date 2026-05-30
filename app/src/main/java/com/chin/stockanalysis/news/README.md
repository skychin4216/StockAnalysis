# 新闻利好利空因子库 (news/)

> 2026-05-31 — 新增模块

## 功能概述

存储从 AI 提取的公司/股票/行业相关利好利空新闻信息，用于策略分析因子。

## 文件

| 文件 | 功能 |
|------|------|
| `NewsFactorEntity.kt` | Room Entity + DAO |
| `NewsFactorManager.kt` | AI 提取、每日搜索、维护清理、因子 Prompt 构建 |

## 数据模型

```
NewsFactorEntity:
  stock_code      → 股票代码（如 sh600519）
  company_name    → 公司名称
  title           → 新闻标题
  content         → 新闻摘要
  news_date       → 新闻日期（非录入日期）
  sentiment       → 1=利好 / -1=利空 / 0=中性
  impact_strength → 影响强度 0-100
  source          → ai_extract / ai_search / user_input
  tags            → 关联标签（逗号分隔）
  sector          → 行业/板块
  is_active       → 活跃状态（>3月自动false）
```

## 生命周期管理

| 时间 | 状态 |
|------|------|
| 0-3个月 | `is_active=true` → 参与策略分析 |
| 3-12个月 | `is_active=false` → 不参与分析，保留存档 |
| >12个月 | 物理删除 |

## 集成方式

### 1. AI 对话自动检测（ChatTabFragment）

每次 AI 回复完成后，自动检测用户消息是否包含新闻信息（关键词匹配）：
- 匹配到 → 调用 AI 提取结构化因子 → 弹窗确认 → 保存

### 2. 策略分析因子（AIPredictionEngine 方案B）

```kotlin
val newsManager = NewsFactorManager(context)
val factors = newsManager.getActiveFactors(50)
// 利好因子 + 利空因子 → 注入 AI Prompt
```

### 3. 每日行业巨头搜索

```kotlin
newsManager.dailySearchIndustryGiants()
// 搜索: 英伟达/特斯拉/苹果/华为/台积电/微软/谷歌/比亚迪/宁德时代
```

## 数据库迁移

v5 → v6 迁移：`StockDatabase.MIGRATION_5_6` 新增 `news_factors` 表。