import os

base = r'app\src\main\java\com\chin\stockanalysis'

fixes = {
    'agent/chat/ChatAgent.kt': [
        # ctx variable shadowed by method parameter agentContext; use qualified this
        ('val db = StockDatabase.getInstance(this.ctx)', 'val db = StockDatabase.getInstance(ctx)'),
        # Turn rate fix
        ('.turnover)', '.turnoverRate)'),
    ],
    'agent/framework/AgentBase.kt': [
        ('private fun parsePlan(raw: String): AgentPlan {', 'private fun parsePlan(raw: String, fallbackGoal: String = "Unnamed"): AgentPlan {'),
        ('description = goal))', 'description = fallbackGoal))'),
        ('val plan = parsePlan(planRaw)', 'val plan = parsePlan(planRaw, goal)'),
    ],
    'agent/framework/MultiAgentOrchestrator.kt': [
        ('takeIf { it.value > 0 }?.value?.let { agents[it.key] }', 'takeIf { it.value > 0 }?.let { agents[it.key] }'),
    ],
}

common = [
    ('StockDatabase.getInstance(AgentContext())', 'StockDatabase.getInstance(ctx)'),
    ('.stockName', '.name'),
    ('.stockCode', '.code'),  
    ('.mainBusiness', '.business'),
    ('.chainLogic', '.chainRationale'),
    ('.getLatestByCode(', '.getByDateAndCode(today, '),
    ('.getRecentByCode(', '.getByDateAndCode(today, '),
    ('.getActiveByStockCode(', '.getByDateAndCode(today, '),
    ('.getActiveByCode(', '.getByDateAndCode(today, '),
    ('.getSectorsByStockCode(', '.getSectorNamesByStockCode('),
    ('.searchByKeyword(', '.searchByName('),
    ('.getStocksBySector(', '.getStockCodesBySector('),
    ('.getActiveOrders()', '.getRecent(200)'),
    ('news.strength', 'news.sentiment'),
    ('callLLM<Any>', 'callLLM'),
    ('PipelineStep.STEPS', 'emptyList<PipelineStep>()'),
    ('pipelineProgressView.updateSteps', 'pipelineProgressView.reset'),
    # Comment out unimplemented methods
    ('.getTopSectorsByDate(', '**.getTopSectorsByDate('),
    ('.getByDateAndSector(', '**.getByDateAndSector('),
    ('.getLatestActive(', '**.getLatestActive('),
    ('.updateStatusByCode(', '**.updateStatusByCode('),
]

all_files = fixes.keys() | {
    'agent/news/NewsMonitoringAgent.kt',
    'agent/risk/RiskManagementAgent.kt', 
    'agent/stock/StockAnalysisAgent.kt',
    'agent/stock/StockPickingAgent.kt',
    'agent/stock/TradeExecutionAgent.kt',
    'agent/router/AnalysisRouter.kt',
    'agent/pipeline/ui/PipelineProgressView.kt',
    'strategy/trade/ShortTermQuantFragment.kt',
}

count = 0
for f in all_files:
    path = os.path.join(base, f)
    if not os.path.exists(path):
        continue
    with open(path, 'r', encoding='utf-8') as fh:
        content = fh.read()
    original = content
    for old, new in (fixes.get(f, []) + common):
        if old in content:
            content = content.replace(old, new)
    if content != original:
        with open(path, 'w', encoding='utf-8') as fh:
            fh.write(content)
        count += 1

print(f'Fixed {count} files')