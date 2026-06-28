"""Safe version: only does replacements that cannot break try/catch blocks."""
import os, re

BASE = r'app\src\main\java\com\chin\stockanalysis'
FILES = [
    'agent/stock/StockAnalysisAgent.kt',
    'agent/stock/StockPickingAgent.kt',
]

def fix_file(rel):
    path = os.path.join(BASE, rel)
    if not os.path.exists(path): return False
    with open(path, 'r', encoding='utf-8') as f: txt = f.read()
    orig = txt

    # ctx shadow fix
    txt = re.sub(
        r'override suspend fun execute\(params: Map<String, String>, ctx: AgentContext\)',
        'override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext)',
        txt)

    # Insert val c = ctx before withContext in execute methods
    lines = txt.split('\n')
    out = []
    for i, line in enumerate(lines):
        if 'return withContext(Dispatchers.IO) {' in line:
            for j in range(max(0,i-4), i):
                if 'execute(params: Map<String, String>, agentCtx: AgentContext)' in lines[j]:
                    indent = len(line) - len(line.lstrip())
                    out.append(' ' * indent + 'val c = ctx')
                    break
        out.append(line)
    txt = '\n'.join(out)
    txt = txt.replace('StockDatabase.getInstance(ctx)', 'StockDatabase.getInstance(c)')
    txt = txt.replace('StockDatabase.getInstance(this.ctx)', 'StockDatabase.getInstance(c)')

    # Field names
    for old, new in [
        ('.mainBusiness', '.business'),
        ('.chainLogic', '.chainRationale'),
        ('.turnover)', '.turnoverRate)'),
    ]:
        txt = txt.replace(old, new)

    # DAO methods (only existing ones)
    for old, new in [
        ('.getSectorsByStockCode(', '.getSectorNamesByStockCode('),
        ('.searchByKeyword(', '.searchByName('),
        ('.getStocksBySector(', '.getStockCodesBySector('),
        ('.getActiveOrders()', '.getRecent(200).filter { it.status in listOf("BUYING", "PENDING") }'),
        ('.getLatestActive(', '.getActiveBySector("", '),
    ]:
        txt = txt.replace(old, new)

    # getByCode(code, days) for snapshot multi-day queries
    txt = txt.replace('.getRecentByCode(', '.getByCode(')
    # getByDateAndCode(today, code) for single-day snapshot
    txt = txt.replace('.getLatestByCode(', '.getByDateAndCode(today, ')
    txt = txt.replace('.getActiveByCode(', '.getByDateAndCode(today, ')
    txt = txt.replace('.getActiveByStockCode(', '.getByDateAndCode(today, ')

    # Truly non-existent methods — replace ONLY the full line
    # Do NOT use /* */ comments inside try blocks
    lines2 = txt.split('\n')
    for i in range(len(lines2)):
        if '.getByDateAndSector(' in lines2[i] or '.getTopSectorsByDate(' in lines2[i]:
            indent = lines2[i][:len(lines2[i]) - len(lines2[i].lstrip())]
            lines2[i] = indent + 'val sectorRecord: Any? = null  // TODO'
        if '.updateStatusByCode(' in lines2[i]:
            indent = lines2[i][:len(lines2[i]) - len(lines2[i].lstrip())]
            lines2[i] = indent + '"SOLD: " + params["stock_code"]!!  // TODO'
    txt = '\n'.join(lines2)

    # callLLM
    txt = txt.replace('callLLM<Any>', 'callLLM')

    # TradeExecutionAgent missing params
    txt = txt.replace(
        'createdAt = System.currentTimeMillis()\n                    )',
        'createdAt = System.currentTimeMillis(),\n                        scoreAtBuy = 0,\n                        buyTime = java.time.LocalTime.now().toString().take(8)\n                    )')

    if txt != orig:
        with open(path, 'w', encoding='utf-8') as f: f.write(txt)
        return True
    return False

count = sum(1 for f in FILES if fix_file(f))
print(f'Fixed: {count} files')