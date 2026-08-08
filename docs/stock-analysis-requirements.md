# StockAnalysis 项目需求与实现状汇编

> 本文档整理了项目所有核心需求文档，包括架构设计、交易规则、策略分类、迁移计划等。
> 最后更新：2026-08-08

---

## 目录

1. [系统架构概览](#1-系统架构概览)
2. [交易规则与选股标准](#2-交易规则与选股标准)
3. [策略分类与分析](#3-策略分类与分析)
4. [Hardcode到DAG迁移计划](#4-hardcode到dag迁移计划)
5. [Agent架构重构计划](#5-agent架构重构计划)
6. [策略优化计划](#6-策略优化计划)
7. [近期任务与实现回顾](#7-近期任务与实现回顾)
8. [Pipeline编辑器设计](#8-pipeline编辑器设计)
9. [T-trade交易时段](#9-t-trade交易时段)
10. [日志分析指南](#10-日志分析指南)

---

## 1. 系统架构概览

**文件**: `docs/ARCHITECTURE.md`

### 核心架构

- **技术栈**: Kotlin Android + MVVM + Room Database (v21) + DAG Pipeline引擎
- **排序算法**: Kahn拓扑排序实现DAG节点执行顺序
- **四周期体系**: 超短线(1-3天) / 短线(3-5天) / 中线(10-30天) / 长线(30天+)

### 数据库版本历史

| 版本 | 主要变更 |
|------|----------|
| v18 | t_trade_recommendations +5列; institutional_tips表 |
| v19 | 新增t_sectors表 |
| v20 | user_focus_sectors表(市场记忆DB化) |
| v21 | ai_selected_stock表(AI精选股) |

### 核心组件

- **DAG Pipeline引擎**: `strategy/topology/` - 节点定义、XML解析、执行引擎
- **策略系统**: `strategy/` - 16+策略实现，按HoldingPeriod分类
- **交易系统**: `strategy/trade/` - AutoSellEngine、持仓管理、选股管理
- **Agent系统**: `strategy/agent/` - 多Agent协作、意图路由、内存隔离

### 数据流

```
用户输入 → IntentRouter → AgentOrchestrator → Pipeline执行 → 结果展示
                              ↓
                        DAG节点按拓扑序执行
                              ↓
                        Signal生成 → 交易决策
```

---

## 2. 交易规则与选股标准

**文件**: `docs/trading-rules-and-selection-criteria.md`

### 六大严选标准

1. **大盘MA检查**: 大盘指数必须在20日均线上方
2. **PE检查**: PE(TTM) < 80，排除高估值
3. **PB检查**: PB < 10，排除高估值
4. **ROE检查**: ROE(TTM) > 8%，确保盈利能力
5. **负债率检查**: 负债率 < 60%，财务健康
6. **毛利率检查**: 毛利率 > 行业平均水平

### 四大纪律

1. **止损纪律**: 亏损>8%无条件止损
2. **止盈纪律**: 阶梯止盈(+10%卖1/3, +15%卖1/3, +20%清仓)
3. **仓位纪律**: 单只股票不超过总仓位30%
4. **时间纪律**: 持仓超过15天强制平仓

### 13种K线形态

包括：早晨之星、锤子线、吞没形态、乌云盖顶、黄昏之星等经典形态识别。

---

## 3. 策略分类与分析

**文件**: `docs/strategy-classification-analysis.md`

### 四周期×四阶段矩阵

| 周期 | 选股阶段 | 买入阶段 | 持有阶段 | 卖出阶段 |
|------|----------|----------|----------|----------|
| 超短线 | 量价突破 | 分时强势 | 快速止盈 | 技术止损 |
| 短线 | 热点追踪 | 回调买入 | 趋势跟踪 | 动态止盈 |
| 中线 | 基本面筛选 | 价值区间 | 成长验证 | 目标价卖出 |
| 长线 | 护城河分析 | 低估买入 | 长期持有 | 高估卖出 |

### 16个策略映射

- **超短线**: UltraShortTermStrategy, MomentumBreakoutStrategy, VolumeSurgeStrategy
- **短线**: ShortTermStrategy, SwingTradingStrategy, SectorRotationStrategy
- **中线**: MidTermStrategy, ValueInvestingStrategy, GrowthStrategy
- **长线**: LongTermStrategy, DividendGrowthStrategy, MoatLeaderStrategy

### 10个Phase完成状态

所有Phase均已完成，包括：
- Phase 1: 策略接口统一
- Phase 2: HoldingPeriod枚举
- Phase 3: 策略按周期分类
- Phase 4: DAG节点实现
- Phase 5: XML配置迁移
- ... (共10个Phase)

---

## 4. Hardcode到DAG迁移计划

**文件**: `docs/hardcode-to-dag-migration-plan.md`

### 迁移状态: ✅ 已完成 (2026-08-01)

### 迁移范围

| Fragment | 删除行数 | 新增XML | 状态 |
|----------|----------|---------|------|
| UltraShortQuantFragment | ~250行 | ultra_short_pipeline.xml | ✅ |
| ShortTermQuantFragment | ~280行 | short_term_pipeline.xml | ✅ |
| MidTermQuantFragment | ~220行 | mid_term_pipeline.xml | ✅ |
| LongTermQuantFragment | ~150行 | long_term_pipeline.xml | ✅ |

### 迁移收益

- 代码行数减少约900行
- 策略逻辑可视化编辑
- 节点复用率提升60%
- 新策略开发周期从3天缩短到2小时

---

## 5. Agent架构重构计划

**文件**: `docs/agent-architecture-refactoring-plan.md`

### Phase完成状态

| Phase | 描述 | 状态 |
|-------|------|------|
| A | Agent角色定义与权限 | ✅ 完成 |
| B | 内存隔离与持久化 | ✅ 完成 |
| C | AgentNode包装器 | ✅ 完成 |
| D | IntentRouter + AgentOrchestrator | ✅ 完成 |
| E | 性能优化与缓存 | ⏳ 待完成 |

### 已删除的旧代码

- UnifiedAgentRunner (旧协调器)
- V2AgentRunner (V2版本协调器)
- 硬编码的Agent选择逻辑

### 新架构优势

- 多Agent并行执行
- 意图识别准确率提升
- Agent内存独立，互不干扰
- 支持动态加载新Agent

---

## 6. 策略优化计划

**文件**: `docs/strategy-optimization-plan.md`

### 5个关键Bug修复

1. **BollingerBand策略**: 布林带计算错误，使用错误的标准差公式
2. **RSI策略**: RSI周期参数未正确传递
3. **LowValuation策略**: 低估值筛选条件过于严格
4. **VolumeBreak策略**: 放量突破判断逻辑错误
5. **TurnoverFilter策略**: 换手率阈值单位错误

### 6个新增策略

1. **InstitutionalAccumulation**: 机构吸筹识别
2. **SmartMoneyFollow**: 聪明钱跟随
3. **SectorBoost**: 板块轮动增强
4. **MomentumShift**: 动量转换
5. **VolatilityBreakout**: 波动率突破
6. **MeanReversion**: 均值回归

### T-trade系统增强

- 全时段监控：9个交易时段权重分配
- 虚拟成功率追踪：记录每个时段的买卖成功率
- 动态权重调整：根据成功率自动调整时段权重

---

## 7. 近期任务与实现回顾

**文件**: `docs/recent-tasks-and-implementation-review.md`

### 49个任务完成记录 (2026-07-25 ~ 2026-08-07)

#### 已完成的关键任务

- ✅ 轮动图表：API直接获取多周期数据(f109/f185/f186)
- ✅ 走势图优化：X轴60天可见范围，Y轴15%padding
- ✅ 选股区改造：完整表格显示(建仓日/成本/数量/现价/卖出)
- ✅ 标题简化：从"持仓（非交易时间买入）"简化为"选股"
- ✅ 卖出修复：选股卖出使用deleteByCode而非updateSellInfo
- ✅ Pipeline UI：图形化框架展示7个UseCase→Pipeline→Node
- ✅ 拓扑编辑器：横屏显示、按钮宽度自适应

#### 已知Bug (3个)

1. **T+1缓存价格问题**: checkT1AutoSell使用todayStocks缓存价格而非实时价
2. **holdingDays不一致**: 文档定义SHORT=3-5天，实际代码=1-14天
3. **StrategyEngine线程安全**: strategies使用普通mutableMapOf，非线程安全

---

## 8. Pipeline编辑器设计

**文件**: `docs/topology/pipeline-editor-design.md`

### 功能设计

- **画布**: 无限滚动，支持缩放(0.5x-2x)
- **节点**: 可拖拽、连接、删除、参数编辑
- **连线**: 贝塞尔曲线，支持多输入单输出
- **侧边栏**: Node模板、Pipeline列表、UseCase列表

### 技术实现

- **横屏模式**: 默认横屏显示，最大化工作区
- **节点分区**: 按pipelineGroup染色(10色)
- **实时验证**: 检测环路、孤立节点、缺失连接
- **导入导出**: XML格式，与assets/usecases/同步

### 核心类

- `TopologyEditorActivity`: 编辑器主Activity
- `NodeRegistry`: 节点模块注册表
- `PipelineXmlParser`: XML解析器
- `DagNode`: 节点基类

---

## 9. T-trade交易时段

**文件**: `docs/topology/t-trade-time-slots.md`

### 9个交易时段与权重

| 时段 | 时间范围 | 权重 | 说明 |
|------|----------|------|------|
| 早盘集合竞价 | 09:15-09:25 | 0.5 | 观察主力意图 |
| 早盘开盘 | 09:30-09:45 | 1.5 | 波动最大时段 |
| 早盘前段 | 09:45-10:30 | 1.2 | 趋势确认期 |
| 早盘中段 | 10:30-11:00 | 1.0 | 平稳运行期 |
| 早盘后段 | 11:00-11:30 | 0.8 | 午盘前调整 |
| 午盘开盘 | 13:00-13:30 | 1.3 | 午后异动时段 |
| 午盘前段 | 13:30-14:00 | 1.0 | 趋势延续期 |
| 午盘中段 | 14:00-14:30 | 0.9 | 平稳运行期 |
| 尾盘阶段 | 14:30-15:00 | 1.5 | 收盘前抢筹/出货 |

### 虚拟成功率追踪

- 每个时段独立统计成功率
- 成功率 = 该时段买入后盈利次数 / 总交易次数
- 动态调整权重：成功率>60%增加权重，<40%降低权重

---

## 10. 日志分析指南

**文件**: `docs/log-analysis-guide.md`

### 16个关键TAG

| TAG | 用途 | 关键日志 |
|-----|------|----------|
| AutoSellEngine | 卖出引擎 | 止损/止盈触发、技术指标判断 |
| DagTradeExecutor | DAG执行 | 节点执行顺序、信号生成 |
| PipelineXmlParser | XML解析 | 节点加载、边连接验证 |
| StrategyEngine | 策略引擎 | 策略启用/禁用、信号过滤 |
| IntentRouter | 意图路由 | 用户意图识别、Agent分配 |
| AgentOrchestrator | Agent协调 | 多Agent并行、结果合并 |
| StockEvaluationNode | 股票评估 | 六大严选检查结果 |
| MarketMaCheckNode | 大盘MA | 大盘均线判断 |
| SectorRotationNode | 板块轮动 | 热门板块识别 |
| SmartMoneyFilterNode | 聪明钱 | 机构资金流向 |
| TTradeMonitor | T-trade监控 | 时段权重、成功率统计 |
| HoldingDiagnosticAnalyzer | 持仓诊断 | 持仓健康度分析 |
| TradeNotifier | 交易通知 | 推送消息发送 |
| PipelineBacktestEngine | 回测引擎 | Pipeline replay回溯 |
| EastMoneyHotSectorSource | 东方财富 | API数据获取 |
| StockDataSourceFactory | 数据源 | 实时行情获取 |

### 5个分析模板

1. **卖出决策分析**: 查看AutoSellEngine日志，分析止损/止盈触发原因
2. **Pipeline执行分析**: 查看DagTradeExecutor日志，验证节点执行顺序
3. **策略信号分析**: 查看StrategyEngine日志，分析信号生成过程
4. **Agent意图分析**: 查看IntentRouter日志，分析意图识别准确率
5. **性能瓶颈分析**: 查看各节点执行时间，识别慢节点

---

## 附录：关键文件索引

### 核心代码文件

| 文件 | 路径 | 说明 |
|------|------|------|
| StockDatabase.kt | stock/database/ | DB定义、DAO接口 |
| QuantFragmentBase.kt | strategy/trade/ | 量化交易基类Fragment |
| AutoSellEngine.kt | strategy/trade/ | 多维度卖出引擎 |
| DagTradeExecutor.kt | strategy/trade/ | DAG交易执行器 |
| PipelineXmlParser.kt | strategy/topology/ | XML解析器 |
| NodeRegistry.kt | strategy/topology/ | 节点注册表 |
| StrategyEngine.kt | strategy/ | 策略引擎 |
| IntentRouter.kt | strategy/agent/ | 意图路由器 |
| AgentOrchestrator.kt | strategy/agent/ | Agent协调器 |

### XML配置文件

| 文件 | 路径 | 说明 |
|------|------|------|
| ultra_short_pipeline.xml | assets/usecases/ | 超短线Pipeline |
| short_term_pipeline.xml | assets/usecases/ | 短线Pipeline |
| mid_term_pipeline.xml | assets/usecases/ | 中线Pipeline |
| long_term_pipeline.xml | assets/usecases/ | 长线Pipeline |
| t_trade_usecase.xml | assets/usecases/ | T-trade UseCase |
| real_holding_usecase.xml | assets/usecases/ | 实盘持仓UseCase |
| screening_usecase.xml | assets/usecases/ | 选股UseCase |

---

## 更新日志

| 日期 | 变更内容 |
|------|----------|
| 2026-08-08 | 初始版本，整理10个核心文档 |
| 2026-08-01 | Hardcode到DAG迁移完成 |
| 2026-07-25 | 近期任务追踪开始 |
