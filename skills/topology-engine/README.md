# Topology Engine Skill — UseCase/DAG Pipeline 拓扑执行引擎

## 模块职责
项目最核心的**策略执行引擎**。通过 XML 描述 UseCase（用例）→ Pipeline（管道）→ Node（节点）的 DAG 拓扑，
加载后按拓扑顺序执行，实现"平台策略 / 量化选股 / 一键建仓"等所有高级策略的编排与执行。

## 触发条件
用户提到以下关键词时，优先查阅本 Skill：
- 平台策略没输出 / 执行策略 / 运行 XX 没有输出
- UseCase / Pipeline / 节点 / Node / DAG / 拓扑
- 新增或修改策略流程（改 XML）
- 豆包体系 / 完整闭环 / 长中短期研判
- 某个 pipeline 为什么不执行 / 条件判断

## 关键目录结构
```
app/src/main/java/com/chin/stockanalysis/strategy/topology/
├── core/    # 引擎核心抽象
│   ├── BaseNode.kt          # 节点基类（execute 抽象）
│   ├── BasePipeline.kt      # 管道基类
│   ├── BaseUseCase.kt       # 用例基类
│   ├── Node.kt / Pipeline.kt / PipelineContext.kt / PipelineTypes.kt / DagPipeline.kt
│   └── OrderTypeUtils.kt
├── nodes/   # 24 个内置节点实现
│   ├── AMarketAnalysisNode.kt      # 大盘分析（n_adaptive / direction 输出）
│   ├── MarketMaCheckNode.kt / MarketMaUnifiedNode.kt  # MA 均线校验
│   ├── SectorLeaderAnalysisNode.kt # 板块龙头分析
│   ├── StockDeepAnalysisNode.kt / StockEvaluationNode.kt
│   ├── TTradeEvalNode.kt / HoldingPredictionNode.kt / HoldingDiagnosticNode.kt
│   ├── PeriodV23Nodes.kt / MarketPublicPipelineNodes.kt / PipelineNodes.kt
│   └── ...（详见 nodes/ 目录）
├── xml/     # XML 解析与执行
│   ├── UseCaseLoader.kt          # 加载 usecases/*.xml → UseCase 对象
│   ├── UseCaseExecution.kt       # 执行入口（最常用！）
│   ├── NodeRegistry.kt           # 节点类型注册表（type → 节点工厂）
│   ├── PipelineXmlParser.kt      # pipeline XML → Pipeline
│   ├── DagTradeExecutor.kt       # DAG 交易执行器
│   ├── PipelineFailureAnalyzer.kt# 失败分析
│   └── HoldingDiagnosticAnalyzer.kt
├── pipelines/ # 代码型 pipeline（QuantTradingPipeline.kt 等）
├── ui/ / viz/  # 拓扑可视化
```

## 配置文件位置
`app/src/main/assets/usecases/*.xml`（49 个），命名规律：
- `*_usecase.xml` — UseCase 顶层配置（含 pipeline 引用列表）
- `*_pipeline.xml` — Pipeline 定义（含节点 DAG）
- `common_usecase.xml` — 平台公共策略
- `complete_closed_loop_usecase.xml` — 豆包体系完整闭环交易系统
- `stock_picking_common_pipeline.xml` — 一键建仓公共研判管线

## 关键机制
### 1. 加载链路
```
assets/usecases/xxx.xml
  → UseCaseLoader（解析 XML）→ UseCase 对象（含 Pipeline 列表）
  → UseCaseExecution.execute(usecaseId, params)  ← 策略执行入口
      → 逐 pipeline 执行（PipelineXmlParser 已解析节点 DAG）
      → 节点通过 NodeRegistry 按 type 实例化
      → PipelineContext 传递数据（输入/输出字段）
```

### 2. 节点条件（"为什么不执行"的最常见原因）
Pipeline 中的节点/子 pipeline 可带 `if="${节点id}.字段 == '值'"` 条件。
例：`if="${n_adaptive}.direction == 'BULLISH'"` —— 若上游 `AMarketAnalysisNode`
未输出 `direction` 字段，**整个 pipeline 会被跳过**，表现为"没有输出"。
排查顺序：
1. 看 XML 中 pipeline 是否有 `if` 条件
2. 确认条件引用的节点（如 `n_adaptive`）是否真实输出该字段
3. 在 `UseCaseExecution` 中打印各 pipeline 的 skip 原因

### 3. 节点输出字段
节点执行结果写入 `PipelineContext`，字段名 = 节点 id + "." + 字段名（如 `n_adaptive.direction`）。
新增节点需：① 在 `nodes/` 实现 BaseNode；② 在 `NodeRegistry` 注册 type。

## 常见任务指引
### 1. 排查"运行 XX 没有输出"
- 先确认该 UseCase 的 XML 存在且被加载（搜 `assets/usecases/`）
- 检查 XML 中每层 pipeline 的 `if` 条件是否成立
- 在 `UseCaseExecution` 的 pipeline 循环处加日志，打印 `skipped` 原因
- 确认 `n_adaptive` 等前置节点在目标周期（超短/短/中/长）是否已执行

### 2. 修改策略流程
- 只改 XML：调整 pipeline 顺序 / 条件 / 节点参数，无需改代码
- 新增节点类型：`nodes/` 新建类继承 `BaseNode`，`NodeRegistry` 注册
- 修改选股逻辑：改对应 pipeline 的 StockEvaluationNode / 筛选节点参数

## 文件清单
- `skills/topology-engine/README.md` — 本文件（skill 定义）
