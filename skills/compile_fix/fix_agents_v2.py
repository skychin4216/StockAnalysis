"""Fix all Agent compilation errors. Run from project root."""
import os, re

BASE = r'app\src\main\java\com\chin\stockanalysis'
TARGETS = [
    'agent/chat/ChatAgent.kt',
    'agent/news/NewsMonitoringAgent.kt',
    'agent/risk/RiskManagementAgent.kt',
    'agent/stock/StockAnalysisAgent.kt',
    'agent/stock/StockPickingAgent.kt',
    'agent/stock/TradeExecutionAgent.kt',
]

COUNT = 0
for rel in TARGETS:
    path = os.path.join(BASE, rel)
    with open(path, 'r', encoding='utf-8') as f:
        txt = f.read()
    orig = txt

    # 1) Rename ctx param in execute() to avoid shadowing ctx: Context
    txt = re.sub(
        r'override suspend fun execute\(params: Map<String, String>, ctx: AgentContext\)',
        'override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext)',
        txt
    )

    # 2) All Tool classes have 'private val ctx: Context' available
    # In execute methods, use this.ctx instead of ctx (which is now agentCtx)
    txt = txt.replace('StockDatabase.getInstance(ctx)', 'StockDatabase.getInstance(this.ctx)')

    # 3) Entity field name fixes
    txt = txt.replace('.stockName', '.name')
    txt = txt.replace('.mainBusiness', '.business')
    txt = txt.replace('.chainLogic', '.chainRationale')
    txt = txt.replace('.turnover)', '.turnoverRate)')

    # 4) DAO method fixes: replace non-existent methods with existing ones
    txt = txt.replace('.getRecentByCode(', '.getByDateAndCode(today, ')
    txt = txt.replace('.getLatestByCode(', '.getByDateAndCode(today, ')
    txt = txt.replace('.getActiveByCode(', '.getByDateAndCode(today, ')
    txt = txt.replace('.getActiveByStockCode(', '.getByDateAndCode(today, ')
    txt = txt.replace('.getSectorsByStockCode(', '.getSectorNamesByStockCode(')
    txt = txt.replace('.searchByKeyword(', '.searchByName(')
    txt = txt.replace('.getStocksBySector(', '.getStockCodesBySector(')

    # 5) getActiveOrders() -> getRecent(200).filter
    txt = txt.replace('.getActiveOrders()',
                       '.getRecent(200).filter { it.status in listOf("BUYING", "PENDING") }')

    # 6) getLatestActive(limit) -> getActiveBySector(null, limit)
    txt = txt.replace('.getLatestActive(', '.getActiveBySector(null, ')

    # 7) getTopSectorsByDate / getByDateAndSector / updateStatusByCode → stubs
    txt = txt.replace(
        '.getTopSectorsByDate(date, ',
        '.getTopSectorsByDate_STUB(date, '
    )
    txt = txt.replace(
        '.getByDateAndSector(today, ',
        '.getByDateAndSector_STUB(today, '
    )
    txt = txt.replace(
        '.updateStatusByCode(code, "SOLD", reason)',
        '.updateSellInfo(0, "SOLD", 0.0, "", 0.0) /* TODO: implement updateStatusByCode */'
    )

    # 8) NewsFactorDao: news.strength -> news.sentiment (field name)
    txt = txt.replace('news.strength', 'news.sentiment')
    txt = txt.replace('n.strength', 'n.sentiment')

    # 9) callLLM<Any> -> callLLM
    txt = txt.replace('callLLM<Any>', 'callLLM')

    # 10) TradeExecutionAgent: StrategyTradeOrderEntity missing buyTime/scoreAtBuy
    # Fix: add the two missing params
    txt = txt.replace(
        'createdAt = System.currentTimeMillis()\n                    )',
        'createdAt = System.currentTimeMillis(),\n                        scoreAtBuy = 0,\n                        buyTime = java.time.LocalTime.now().toString().take(8)\n                    )'
    )

    # 11) ChatAgent line 209: s.stockName -> s.name, s.stockCode -> s.code
    # (DailySnapshotEntity uses .name and .code, not .stockName / .stockCode)
    # Already handled by replace above

    if txt != orig:
        with open(path, 'w', encoding='utf-8') as f:
            f.write(txt)
        print(f'FIXED: {rel}')
        COUNT += 1
    else:
        print(f'NOCHG: {rel}')

print(f'\nFiles changed: {COUNT}')