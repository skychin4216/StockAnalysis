# 量化选股与模拟交易系统架构（详细补充）

本文在原架构基础上补充：代码框架（接口/伪代码）、交互逻辑、常见 bug 与修复方法，以及可选的更先进策略建议。目标是给开发团队直接可落地的设计说明。

## 设计原则（精简）
- 明确可复现性：策略执行必须记录 exec_id、参数、模型版本与随机种子。
- 单一职责：策略只负责打分/排序，组合构建与交易由上层服务负责。
- 可配置与可测试：星级权重、合并规则、交易成本等均从配置读取并有契约测试。

---

## 接口与代码框架（伪代码）

策略接口（Python 风格）：

class Strategy:
    id: str
    name: str
    version: str

    def run(self, market_df: DataFrame, params: dict) -> List[Dict]:
        """返回 [{symbol, score, rank, reason, meta}] 并不直接写库"""

策略管理器（负责调用并持久化）:

class StrategyManager:
    def execute(strategy_id, date, params):
        exec_id = create_exec_record(...)
        results = Strategy.load(strategy_id).run(market_df, params)
        validate_results(results)
        write_strategy_results(exec_id, date, strategy_id, results)
        return exec_id

API 示例（Flask / FastAPI 风格）：
- POST /strategies/{id}/run?date=YYYY-MM-DD
  - body: {params}
  - response: {exec_id, status}

- POST /simulations/start?date=YYYY-MM-DD
  - body: {use_strategies: ["A","B"], merge_mode: "star"}
  - behavior: 确保当日 StrategyResults 存在；若不存在则按 use_strategies 顺序触发 execute

---

## UI 与交互逻辑（流程图概念）
1. 用户打开策略面板，看到方案 A / B 的并列列表（评分、理由、建议仓位）。
2. 用户可为每个策略设置星级（1-5）。界面保存到用户偏好 API。
3. 当用户点击“模拟交易”或“开始回测”：
   - 前端调用 POST /simulations/start(date)
   - 后端检查 StrategyResults(date)
     - 存在：按用户偏好合并并下单计划
     - 不存在：按用户设置并行触发策略执行，等待 exec_id 完成并写入 DB，然后继续
4. 合并规则示例（星级优先）：
   - 每个策略 i 有星级 S_i (1-5)，规范化权重 w_i = S_i / sum(S_j)
   - 最终 score_for_symbol = sum_i (w_i * score_i(symbol))
   - 对于仅出现在某策略的 symbol，视为 score_i=-inf 或按最低流动性罚分

---

## 策略-to-portfolio 策略签名（伪代码）

def build_portfolio(strategy_results: List[ {symbol, score}], max_positions: int, exposure: float):
    # 1. 按 score 排序，选 top N
    # 2. 根据 score 分配权重（如 softmax 或按线性归一化）
    # 3. 应用仓位限制与风控规则（单股上限、行业暴露）
    return List[ {symbol, target_weight} ]

下单计划转换示例：
- target_cash_per_position = portfolio_value * target_weight
- qty = floor(target_cash_per_position / current_price / lot_size) * lot_size

---

## 常见 BUG 与修复方法
1. 非确定性结果（复现失败）
   - 原因：模型未设置随机种子、使用了多线程无序操作或未记录版本
   - 修复：模型初始化强制 seed，记录软件/模型版本及依赖（requirements.txt），在 StrategyExecs 存 seed 与 git commit id
2. 策略结果未写入 DB（模拟器拿不到数据）
   - 原因：策略直接返回内存结果或执行中断未捕获异常
   - 修复：策略管理器必须 try/except 捕获异常，保证写入失败状态到 StrategyExecs 并返回清晰错误；写入采用事务性操作
3. 流动性/停牌未过滤导致买入失败模拟
   - 原因：策略只基于信号，无流动性检查
   - 修复：在 StrategyResults 写入前做一轮过滤：成交量、日换手率、是否停牌、最大单日成交额阈值
4. 合并/权重冲突导致超额仓位
   - 原因：简单相加权重未归一化
   - 修复：在合并后进行归一化并应用 max_position 与 cash_cap 限制；记录调整日志供 UI 展示
5. 回测与实时交易差异太大
   - 原因：回测使用理想成交（无滑点/即时成交）
   - 修复：模拟器加滑点模型、成交概率与分笔执行逻辑；用历史成交分布校准参数

---

## 测试与质量保证
- 策略契约测试：给定固定 seed 与 mock 市场数据，策略输出必须与基线 JSON 匹配
- 集成测试：从策略执行到模拟交易完整流水应写到 test DB，并断言最终账户快照
- 回归测试：对关键策略定期跑历史回测比对关键指标（收益/最大回撤/胜率）
- 监控：线上策略执行失败率、执行时长、输出分布漂移报警

---

## 更先进/建议的策略（优先级）
1. 因子+机器学习混合（推荐）
   - 因子工程（动量、价值、质量、成长、波动率）作为特征，使用 LightGBM/XGBoost 做排序/回归
   - 优点：可解释性 + 非线性能力
2. 时间序列深度学习（次选）
   - 使用 TCN/Transformer（例如 Temporal Fusion Transformer）捕捉多尺度时间依赖
   - 要点：需要大量数据，注意过拟合和漂移
3. 强化学习（仅做执行策略或仓位微调）
   - 用 RL 优化执行成本/滑点或短期资金配比，不建议直接用 RL 做选股（数据效率低、难解释）
4. 简单 ensemble（必备）
   - 把多个模型/规则的排名做加权平均，权重按历史表现（每策略 rolling-sharpe）动态调整

---

## 监控、再训练与模型管理
- 模型注册：在 StrategyExecs 记录 model_artifact_location（路径或哈希）
- 再训练策略：基于漂移检测（KL divergence / PSI / 带标签的回测退化）触发
- 回滚计划：发现新模型效果变差时自动回滚到上一稳定版本并告警

---

## 运维建议（简短）
- 所有关键 API 返回 exec_id 与 status，UI 通过轮询或 websockets 更新执行进度
- 权限与审批：批量执行高风险策略需二次确认（尤其是模拟->实盘通道）
- 审计日志：保存策略输入与输出快照（至少最近 90 天）以便合规/回溯

---

## 代码层注意事项（针对当前代码库）
以下为对现有代码中关键类/行为的提炼，便于开发者快速定位实现并将文档与代码保持一致：

- StrategyEngine
  - 启用策略的持久化位置：SharedPreferences 名称 = "strategy_prefs"，键 = "enabled_strategy_ids"。
  - 执行超时：每个策略执行被限制为 30 秒（见 runOneInternal 中的 withTimeoutOrNull(30_000L)）。
  - 最近结果缓存：lastResults 保存在内存，仅用于 UI 快速展示，非长期持久化。回测应使用 runAllWithData。

- AIPredictionEngine
  - AI 提供商选择：优先使用 ApiConfigManager.createCurrentProvider()，若无有效 key 会回退到 assets 中配置的 "doubao" 提供商。
  - Prompt 与解析：引导 AI 输出严格 JSON（从第一个 '{' 到最后一个 '}' 截取并解析），因此 Prompt 必须明确输出格式样例。
  - 错误处理：predict 在异常时返回 null，并在日志中记录失败原因，调用方需处理 null 情形。

- SimulationTradeEngine
  - 核心常量（PERIOD_DAYS、MAX_STOCKS_PER_STRATEGY、FINAL_TOP3 等）直接影响回测/模拟行为，调参时请优先调整这些常量或通过 TradeSessionConfig 传入。
  - 数据持久化点：savePeriodResultToDb() 与 saveBacktrackResult() 等函数将结果写入 Room DAO（例如 dailyPeriodResultDao）。文档中应列明对应的 DAO/Entity 名称以便审计与迁移。
  - runTradeSession 会在每个周期执行 executeStrategy、新闻评分、板块轮动惩罚、主板过滤并保存周期结果；AI 最终选股基于 allPeriodResults 汇总。

- StrategyConfig
  - 常用构造器：fullMarket(), pool(), custom(params)。建议策略开发者在文档中举例如何通过 params 传入常用参数（如阈值、窗口大小）。

- UI 与交互
  - StrategyResultDialogFragment 暴露 onAskQuestion 回调，前端发送追问需要后端或 Chat 模块提供流式 AI 回复接口（目前为占位实现）。
  - UI 中评分计算在对话框 UI 有轻量计算（评分 = strength * (1 + changePercent/100) / 10），如需严格一致应将该计算抽到后端或共享库。

- DB / DAO（说明性）
  - 代码中引用的 DAO：dailySnapshotDao(), dailyPeriodResultDao(), strategyTradeFittingParamDao() 等。请在 docs/股票數據架構設計.md 中补充对应 Entity 名称与必要字段。

- 建议的快速修复条目（优先级）
  1. 在 StrategyExecs 写入中强制记录随机种子、git commit id 与 model artifact 路径，保证可复现（高优先）。
  2. 在 AIPredictionEngine 的 prompt 构建处增加最大候选数量与超长截断策略，避免超长 prompt 导致 provider 拒绝（中优先）。
  3. 在模拟交易写库前对候选股票进行一次流动性/停牌过滤（中优先）。

---

如需，我可以把本文中的接口/类签名映射到你项目的具体文件（例如：在哪个包下建 Strategy 接口、StrategyManager 的实现位置、SQL 表的建表 SQL）。要我继续映射并且生成具体 PR 吗？