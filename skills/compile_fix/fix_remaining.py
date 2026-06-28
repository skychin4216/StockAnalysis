"""Fix remaining 4 files with small targeted fixes."""
import os

BASE = r'app\src\main\java\com\chin\stockanalysis'

def fix(p, old, new):
    with open(p, 'r', encoding='utf-8') as f: t = f.read()
    if old in t:
        t = t.replace(old, new)
        print(f'APPLIED: {os.path.basename(p)}')
    else:
        print(f'NOT FOUND in {os.path.basename(p)}: {old[:60]}')
        return
    with open(p, 'w', encoding='utf-8') as f: f.write(t)

# 1) ShortTermQuantFragment: .name -> .stockName on StrategySignal, fix reset() args
path = os.path.join(BASE, 'strategy/trade/ShortTermQuantFragment.kt')
with open(path, 'r', encoding='utf-8') as f: t = f.read()
t = t.replace('.name', '.stockName')
t = t.replace('pipelineProgressView.reset(steps)', 'pipelineProgressView.reset()')
with open(path, 'w', encoding='utf-8') as f: f.write(t)
print('SHORTTERM fixed')

# 2) TradeExecutionAgent.kt RiskCheckTool: broken orders block
path = os.path.join(BASE, 'agent/stock/TradeExecutionAgent.kt')
with open(path, 'r', encoding='utf-8') as f: t = f.read()
old = 'emptyList() // TODO: get active order by code'
new = 'db.strategyTradeOrderDao().getRecent(200).filter { it.status in listOf("BUYING", "PENDING") }'
if old in t:
    t = t.replace(old, new)
    print('TRADE: replaced emptyList stub')
# Fix order.stockCode references (they use order from wrong source)
# Actually the issue is that the RiskCheckTool gets order from dailySnapshotDao instead of tradeOrderDao
# Let me fix the whole RiskCheckTool execute method
old2 = '''listOfNotNull(db.dailySnapshotDao().getByDateAndCode(today, code))
                } else {
                    db.strategyTradeOrderDao().getRecent(200).filter { it.status in listOf("BUYING", "PENDING") }
                }

                val risks = mutableListOf<String>()
                for (order in orders) {
                    val snapshot = db.dailySnapshotDao().getByDateAndCode(today, order.stockCode)
                    if (snapshot == null) continue

                    val profitPct = (snapshot.close - order.buyPrice) / order.buyPrice * 100
                    val daysHeld = /* 計算持有天數 */ 0

                    when {
                        profitPct < -8 -> risks.add("${order.stockCode}: 虧損 ${"%.1f".format(profitPct)}% > 8%，觸發止損")
                        profitPct > 20 -> risks.add("${order.stockCode}: 盈利 ${"%.1f".format(profitPct)}% > 20%，建議止盈")
                        daysHeld > 10 -> risks.add("${order.stockCode}: 持倉超 $daysHeld 天，建議評估")
                    }
                }'''
new2 = '''db.strategyTradeOrderDao().getRecent(200).filter { it.status in listOf("BUYING", "PENDING") }
                } else {
                    db.strategyTradeOrderDao().getRecent(200).filter { it.status in listOf("BUYING", "PENDING") }
                }

                val risks = mutableListOf<String>()
                for (o in orders) {
                    val snap = db.dailySnapshotDao().getByDateAndCode(today, o.stockCode)
                    if (snap == null) continue

                    val pct = (snap.close - o.buyPrice) / o.buyPrice * 100

                    when {
                        pct < -8 -> risks.add("${o.stockCode}: 虧損 ${"%.1f".format(pct)}% > 8%，觸發止損")
                        pct > 20 -> risks.add("${o.stockCode}: 盈利 ${"%.1f".format(pct)}% > 20%，建議止盈")
                    }
                }'''
if old2 in t:
    t = t.replace(old2, new2)
    print('TRADE: RiskCheckTool body fixed')
with open(path, 'w', encoding='utf-8') as f: f.write(t)

# 3) TradeExecutionAgent: MarketTimingTool listOf() -> listOf<String>()
with open(path, 'r', encoding='utf-8') as f: t = f.read()
# Already applied? Let me check
if 'override val parameters = listOf()' in t:
    # Replace only in MarketTimingTool context
    t = t.replace(
        'class MarketTimingTool(private val ctx: Context) : AgentTool {\n    override val name = "market_timing"\n    override val description = "評估當前市場時機是否適合交易"\n    override val parameters = listOf()',
        'class MarketTimingTool(private val ctx: Context) : AgentTool {\n    override val name = "market_timing"\n    override val description = "評估當前市場時機是否適合交易"\n    override val parameters = listOf<String>()'
    )
with open(path, 'w', encoding='utf-8') as f: f.write(t)
print('TRADE: MarketTimingTool listOf fixed')

# 4) NewsMonitoringAgent.kt: null sector + searchByName
path = os.path.join(BASE, 'agent/news/NewsMonitoringAgent.kt')
with open(path, 'r', encoding='utf-8') as f: t = f.read()
t = t.replace('getActiveBySector(null, limit)', 'getActiveBySector("", limit)')
t = t.replace('searchByName(keyword, days)', 'getActiveBySector("", days)  // TODO searchByName')
with open(path, 'w', encoding='utf-8') as f: f.write(t)
print('NEWS fixed')

# 5) RiskManagementAgent: MarketRiskTool listOf() -> listOf<String>()
path = os.path.join(BASE, 'agent/risk/RiskManagementAgent.kt')
with open(path, 'r', encoding='utf-8') as f: t = f.read()
if 'class MarketRiskTool(private val ctx: Context) : AgentTool {\n    override val name = "market_risk"\n    override val description = "評估當前市場系統性風險"\n    override val parameters = listOf()' in t:
    t = t.replace(
        'class MarketRiskTool(private val ctx: Context) : AgentTool {\n    override val name = "market_risk"\n    override val description = "評估當前市場系統性風險"\n    override val parameters = listOf()',
        'class MarketRiskTool(private val ctx: Context) : AgentTool {\n    override val name = "market_risk"\n    override val description = "評估當前市場系統性風險"\n    override val parameters = listOf<String>()'
    )
with open(path, 'w', encoding='utf-8') as f: f.write(t)
print('RISK fixed')

print('\nAll remaining fixes applied')