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
---

## AI 对话与股票分析规范（合并自 AI_Dialogue_Stock_Analysis.md）

# AI 对话：以炒股思维构建的交互与后端需求

目标：提升 AI 与用户在股票咨询场景的对话质量，使回复体现真实交易者的思路 —— 快速识别要点、获取板块/热股/流动性/分红/订单面信息，做出可解释的投资提示并以表格形式呈现相关股票与产业链信息。

1. 核心设计原则
- 以交易者思路优先：核心回答包含（1）板块及热点（2）流动性与订单面（3）盈利与分红信息（4）上下游/产业链相关公司（5）短中长期风险提示与操作建议。 
- 可复现的 AI 输出：要求模型输出严格的 JSON 字段，便于前端程序化渲染与风险审计。
- 数据驱动：AI 必须以实时/近实时数据为依据，任何主观推断需标注置信度与数据来源。

2. 后端必须提供的数据（按优先级）
- 目标股票基本信息：公司名称、所属行业/板块、最新价、昨收、涨跌幅、市值、流通市值、每股收益、净利率。
- 财务摘录：近四季度营收、净利、现金流、股息/分红记录（近5年），ROE/ROA 等关键指标。
- 板块热门股票：同板块按成交量或涨幅排序的 Top N（N=5-10），包含当日成交额、换手率
- 订单簿/委托信息：当日买卖盘口深度（如前五档）、大单监测（大于门槛的买入或卖出）
- 新闻/研报因子：近30天新闻情绪、重大公告（增持/减持/回购/财报预告/政策）
- 产业链/上下游关系：公司供应商与客户名单（若有），并标注与目标公司业务相关度
- 历史行情切片：近1/5/20/60/120日的OHLCV

3. 后端处理与 API 设计（建议）
- 新增接口: GET /api/analysis/stock_overview?symbol=sh600519&date=YYYY-MM-DD
  - 返回：{symbol, name, sector, market_cap, liquidityMetrics, financials, dividends, top_sector_peers: [], orderbook_summary: {}, news: [], supply_chain: []}
- 支持批量：GET /api/analysis/sector_peers?sector=新能源&limit=10
- 支持大单/盘口查询：GET /api/market/orderbook?symbol=sh600519&depth=5
- AI 调用入口：POST /api/ai/analyze_stock
  - body: { symbol: "sh600519", date: "2026-06-07", mode: "auto|A|B", include_peers: true }
  - 行为：后端拉取 stock_overview、sector_peers、orderbook，构建 prompt，调用 LLM，解析并返回结构化 JSON 与 human_summary

4. Prompt 设计与约束（强制）
- System 指令必须固定格式，强调：
  - 仅基于提供的数据进行判断，不要编造事实
  - 输出必须为 JSON，遵循下面的 schema
  - 如置信度低于阈值（例如 0.6），在字段中标注 "confidence": <0-1>

- Prompt 模板（摘要）:
  - "你是专业的A股量化分析师。输入：{stock_overview, peers, orderbook, financials, news, supply_chain, historical}. 请按 schema 输出 JSON，并给出最多 5 条可操作建议。"

5. AI 输出 Schema（严格）
{
  "symbol": "sh600519",
  "selected_date": "2026-06-07",
  "market_outlook": "bullish|neutral|bearish",
  "confidence": 0.78,
  "score": 72, // 0-100
  "top_peers": [ {"symbol":"sz002594","name":"比亚迪","reason":"成交额高，动量强","metric": {...} } ],
  "orderbook_summary": { "bid_pressure": 0.6, "ask_pressure": 0.4, "large_buy_count": 3, "large_sell_count": 1 },
  "financial_highlights": { "ttm_net_profit": ..., "dividend_yield": 0.015 },
  "supply_chain_related": [ {"symbol":"...","relation":"supplier|customer","relevance":0.8} ],
  "recommendations": [ {"action":"watch|buy|buy_partial|sell|avoid","reason":"...","confidence":0.7,"suggested_size":"10%"} ],
  "table_view": {
    "columns": ["symbol","name","最新价","涨跌%","成交额","换手","盈亏","分红","relation"],
    "rows": [ ["sh600519","贵州茅台",...], ... ]
  }
}

6. 前端呈现（UI 要点）
- 首段：简短人类可读结论（1-2句）和风险提示。
- 表格：使用 table_view 内容显示同行/上下游/推荐标的，支持点击跳转到个股详情。
- 交互按钮："查看板块热度"、"查看订单簿"、"将选股加入模拟"（若用户已有模拟会话则直接加入）。
- 变体：在结果旁显示 source badges（数据来源：行情、新闻、公告、AI-model-x）和 exec_id 供审计。

7. 交互流程（示例）
- 用户: "分析贵州茅台" → 前端调用 POST /api/ai/analyze_stock
- 后端：检查是否有当天 stock_overview；若无则并行拉取数据，保存 snapshot；构建 prompt（包含示例输出），调用 LLM
- 后端：解析 LLM 输出，做一致性检查（字段存在性、数值边界、是否与原始数据冲突），若冲突则标注并降低 confidence
- 返回给前端：结构化 JSON + human_summary。前端渲染并允许用户进一步追问（追问时附带已存在 context）

8. 审计、缓存与性能
- 缓存层：对 stock_overview 缓存 30s（交易中），5min（盘后），并保留每日快照供回溯。
- 请求限流：对 AI 分析请求做 rate-limit 与排队（exec_id 模式），避免滥用造成费用暴涨。
- 日志：记录 exec_id、请求者、prompt hash、AI raw response、parsed JSON，保存至少90天。

9. 风险与治理
- 强制输出来源与置信度；任何投资建议前须显示免责声明。
- 敏感字段屏蔽：不在 UI 中显示完整订单簿原始流水，只显示摘要（除非用户权限允许）。
- 模型回退：若 AI 输出为空或解析失败，后端应返回基于规则的备选（如基于动量/流动性排序的 Top3）

10. 示例交互（简短）
- 返回 human_summary:
  - "板块：白酒，短期中性偏强。茅台今日成交额放大，榜单中出现数笔大买单（买盘强度0.65）。建议：逢低分批小仓位建仓（建议初始仓位5%），止损3%" 
- 表格（示意）:
  | 代码 | 名称 | 现价 | 涨跌% | 成交额 | 换手 | 盈利 | 分红 | 关系 |
  |------|------|------:|------:|------:|-----:|-----:|-----:|-----:|
  | sh600519 | 贵州茅台 | 1600.00 | +1.2% | 1.2亿 | 0.8% | 18% | 1.2% | self |
  | sz000858 | 五粮液 | 120.00 | +0.8% | 8000万 | 1.0% | 12% | 0.9% | peer |

11. 测试用例与验收标准
- 精度验收：在历史样本上，AI 提取的 top_peers 与数据库排序 Top5 的重合度 >= 0.8
- 质量验收：生成的 JSON 必须通过 schema 校验，且 human_summary 不超过 2 个句子
- 性能验收：99% 请求在 5s 内返回（若含 AI 调用则可为排队+异步，返回 exec_id 在 1s 内）

---

下步：
- 我可以把上述规范加入 docs/Quantitative_Simulated_Trading.md 并生成对应的 API 片段与示例请求/响应（并提交 PR）。
- 或者把后端示例实现（伪代码）生成到 strategy 服务里的 RemoteDataService/AI handler。 

请选择：
- "写入 docs 并提交 PR" 或 "先查看变更" 或 "生成后端伪代码"。
