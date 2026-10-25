package com.chin.stockanalysis.strategy.ssa

import android.content.Context
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.data.StockDataFacade
import com.chin.stockanalysis.strategy.data.HistoricalDataFetcher
import com.chin.stockanalysis.strategy.data.IntradayKlineFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ## 短线预警 · 持仓池批量重算（含 BBI12拐点+移动止盈8% 覆盖）
 *
 * 供系统悬浮窗 / ER 持仓卡片使用：对「做T页持仓池」每只票拉 1H K 线，
 * 统一跑 [SsaBbiErEngine]（稳健模式 + 固定成本止损，冠军口径；2026-10-22 起不再使用旧多指标评分）；
 * 触发固定止损/移动止盈/峰顶卖出 → advice 覆盖；空仓出现 Δ5ER 金叉 → 「金叉预警」。
 * 可执行建议（买入/卖出/止盈卖出/止损卖出/峰顶卖出）自动追加到 [SsaAlertStore] 推荐台账。
 */
object SsaAlertBatch {

    /**
     * @param hours 计算用的 1H K 线根数（默认 200）
     */
    suspend fun run(
        ctx: Context,
        members: List<SsaT0PoolStore.PoolMember>,
        hours: Int = 200,
        p: SsaAlertEngine.AlertParams = SsaAlertEngine.AlertParams()
    ): List<SsaAlertStore.OverlayRow> = withContext(Dispatchers.IO) {
        if (members.isEmpty()) return@withContext emptyList()
        val updated = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date())
        val ledgerTs = System.currentTimeMillis()

        val quotes: Map<String, StockRealtime> = runCatching {
            StockDataFacade.getInstance(ctx).getRealtimeQuotes(members.map { it.code })
        }.getOrDefault(emptyMap())

        // 一次性取全持仓池 ER 分位（本地补算，失败则 fallback null）
        val erPctMap: Map<String, Double> = runCatching {
            SsaErLocal.corePoolPercentiles(ctx, members.map { it.code }.toSet())
        }.getOrDefault(emptyMap())

        val fetcher = IntradayKlineFetcher(ctx)
        members.map { m ->
            runCatching {
                val bars = fetcher.fetchHourlyHistory(m.code, hours)
                    .filter { it.close > 0 && it.high > 0 && it.low > 0 }
                    .sortedBy { it.datetime }
                    .takeLast(hours)
                    .map {
                        SsaAlertEngine.Bar(
                            it.date, it.datetime.substringAfter(' ', ""),
                            it.open, it.high, it.low, it.close, it.volume
                        )
                    }
                if (bars.size < 120) throw IllegalStateException("1H K线不足(${bars.size})")

                // ── 统一为 BBI12 冠军口径（2026-10-22 用户要求）：STEADY + 固定成本止损；
                //    ER70 横截面闸只在「买入」建议上生效，与荐股池扫描同口径。旧多指标评分已弃用。──
                val closes = DoubleArray(bars.size) { bars[it].close }
                val dates = bars.map { "${it.date} ${it.time}" }
                val st = SsaBbiErEngine.run(closes, dates, SsaBbiErEngine.Mode.STEADY, fixedStop = true)
                val n = bars.size
                val lastQ = bars[n - 1]
                val lastSig = st.signals.lastOrNull()
                val recentBuy = lastSig?.kind == SsaBbiErEngine.Kind.BUY && lastSig.index >= n - 4
                val recentSell = lastSig?.takeIf {
                    it.index >= n - 4 &&
                        (it.kind == SsaBbiErEngine.Kind.SELL_PEAK ||
                         it.kind == SsaBbiErEngine.Kind.SELL_TRAIL ||
                         it.kind == SsaBbiErEngine.Kind.SELL_STOP)
                }
                val erPct = erPctMap[m.code]
                val erGatePass = erPct != null && erPct >= 70.0

                val advice = when {
                    recentSell?.kind == SsaBbiErEngine.Kind.SELL_STOP -> "止损卖出"
                    recentSell?.kind == SsaBbiErEngine.Kind.SELL_TRAIL -> "止盈卖出"
                    recentSell?.kind == SsaBbiErEngine.Kind.SELL_PEAK -> "峰顶卖出"
                    recentBuy -> if (erGatePass) "买入" else "关注(ER<70)"
                    st.gcAlert -> "金叉预警"
                    st.holding -> "持仓(止损线)"
                    else -> "空仓观望"
                }
                val score = when {
                    advice == "买入" -> 100
                    advice == "关注(ER<70)" -> 80
                    advice == "金叉预警" -> 70
                    st.holding -> 60
                    else -> 0
                }
                val reason = when {
                    recentSell != null -> recentSell.reason
                    recentBuy -> lastSig!!.reason +
                        (erPct?.let { " · ER分位 ${"%.0f".format(it)}" } ?: " · ER分位 —")
                    else -> st.reason + (erPct?.let { " · ER分位 ${"%.0f".format(it)}" } ?: "")
                }

                val q = quotes[m.code]
                SsaAlertStore.OverlayRow(
                    code = m.code, name = m.name.ifBlank { q?.name ?: m.code },
                    price = q?.price?.takeIf { it > 0.0 } ?: lastQ.close,
                    changePct = q?.changePercent ?: 0.0,
                    score = score, windowOpen = st.holding || recentBuy,
                    advice = advice, reason = reason, updated = updated,
                    erPct = erPct
                )
            }.getOrElse {
                SsaAlertStore.OverlayRow(
                    code = m.code, name = m.name.ifBlank { m.code },
                    price = quotes[m.code]?.price ?: 0.0,
                    changePct = quotes[m.code]?.changePercent ?: 0.0,
                    score = 0, windowOpen = false,
                    advice = "数据缺失", reason = it.message ?: "拉取失败", updated = updated
                )
            }
        }.also { rows ->
            // 推荐台账：可执行建议落库（买入/卖出/止盈卖出/止损卖出/峰顶卖出），预警页「历史」Tab 展示
            val actionable = rows.filter { it.advice in setOf("买入", "卖出", "止盈卖出", "止损卖出", "峰顶卖出") }
            if (actionable.isNotEmpty()) SsaAlertStore.appendRecs(ctx, actionable.map { r ->
                SsaAlertStore.Rec(ledgerTs, r.code, r.name, "ENTRY_BBI", r.advice, r.price, r.erPct, r.reason, updated)
            })
        }
    }

    /**
     * ## 荐股池全池扫描（2026-10-20 方案A：自动推荐入场）
     *
     * 对每日荐股核心池（[HistoricalDataFetcher.getCoreStockPool]，~171 只）逐只拉 1H K 线，
     * 跑 [SsaBbiErEngine] 冠军口径（STEADY + 固定成本止损），最近 4 根内出现买入信号 → 入场候选。
     * ER70 横截面闸在建议级应用（[SsaErLocal] 日线口径，与持仓预警一致）：分位 <70 → 「关注(ER<70)」。
     * 并发 6，171 只约 30~60s；结果落盘 [SsaAlertStore.saveScan]，买入票落台账（channel=SCAN_POOL）。
     */
    suspend fun scanPool(ctx: Context): SsaAlertStore.ScanResult = withContext(Dispatchers.IO) {
        val codes = HistoricalDataFetcher.getCoreStockPool(ctx)
            .map { it.takeLast(6) }.filter { it.length == 6 }.distinct()
        val updated = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date())
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        if (codes.isEmpty()) return@withContext SsaAlertStore.ScanResult(today, updated, 0, emptyList())

        val erPctMap = runCatching { SsaErLocal.corePoolPercentiles(ctx, codes.toSet()) }.getOrDefault(emptyMap())
        val names: Map<String, String> = runCatching {
            StockDataFacade.getInstance(ctx).getRealtimeQuotes(codes)
                .mapValues { it.value.name }
        }.getOrDefault(emptyMap())
        val prices: Map<String, StockRealtime> = runCatching {
            StockDataFacade.getInstance(ctx).getRealtimeQuotes(codes)
        }.getOrDefault(emptyMap())

        val fetcher = IntradayKlineFetcher(ctx)
        val sem = Semaphore(6)
        val rows = coroutineScope {
            codes.map { c6 -> async {
                sem.withPermit {
                    runCatching {
                        val ents = fetcher.fetchHourlyHistory(c6, 200)
                            .filter { it.close > 0 && it.high > 0 && it.low > 0 }
                            .sortedBy { it.datetime }.takeLast(200)
                        if (ents.size < 90) return@runCatching null
                        val st = SsaBbiErEngine.run(
                            DoubleArray(ents.size) { ents[it].close },
                            ents.map { "${it.date} ${it.datetime.substringAfter(' ', "")}" },
                            SsaBbiErEngine.Mode.STEADY, fixedStop = true)
                        val sig = st.signals.lastOrNull() ?: return@runCatching null
                        if (sig.kind != SsaBbiErEngine.Kind.BUY || sig.index < ents.size - 4) return@runCatching null
                        val erPct = erPctMap[c6]
                        val gatePass = erPct != null && erPct >= 70.0
                        SsaAlertStore.OverlayRow(
                            code = c6, name = names[c6] ?: c6,
                            price = prices[c6]?.price?.takeIf { it > 0.0 } ?: ents.last().close,
                            changePct = prices[c6]?.changePercent ?: 0.0,
                            score = if (gatePass) 100 else 60, windowOpen = true,
                            advice = if (gatePass) "买入" else "关注(ER<70)",
                            reason = sig.reason + (erPct?.let { " · ER分位 ${"%.0f".format(it)}" } ?: ""),
                            updated = updated, erPct = erPct)
                    }.getOrNull()
                }
            } }
        }.awaitAll().filterNotNull()

        val sorted = rows.sortedWith(
            compareByDescending<SsaAlertStore.OverlayRow> { it.advice == "买入" }
                .thenByDescending { it.erPct ?: 0.0 })
        SsaAlertStore.saveScan(ctx, today, updated, codes.size, sorted)
        val buys = sorted.filter { it.advice == "买入" }
        if (buys.isNotEmpty()) SsaAlertStore.appendRecs(ctx, buys.map { r ->
            SsaAlertStore.Rec(System.currentTimeMillis(), r.code, r.name, "SCAN_POOL", r.advice,
                r.price, r.erPct, r.reason, updated)
        })
        SsaAlertStore.ScanResult(today, updated, codes.size, sorted)
    }
}
