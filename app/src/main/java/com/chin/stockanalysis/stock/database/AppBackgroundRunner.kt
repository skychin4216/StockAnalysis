package com.chin.stockanalysis.stock.database

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ## App 后台统一调度器
 *
 * 启动时执行：
 *  1. 热门板块池定时刷新
 *  2. 股票资料中心初始化
 *  3. 持仓监控（每 5 分钟检查一次）
 */
object AppBackgroundRunner {

    private const val TAG = "AppBgRunner"
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private var monitorJob: Job? = null
    private var _appScope: CoroutineScope? = null

    /** 量化选股运行时设为 true，后台 AI 相关任务应暂停 */
    @Volatile
    var isQuantRunning = false

    /** 暂停后台 AI 任务（量化选股开始时调用） */
    fun pauseForQuant() {
        isQuantRunning = true
        Log.i(TAG, "⏸️ 量化选股开始，暂停后台 AI 任务")
    }

    /** 量化开始前，先确保新闻因子是最新的，然后再暂停后台 */
    suspend fun ensureNewsFreshThenPause(context: Context) {
        try {
            val updater = com.chin.stockanalysis.news.HotSectorNewsUpdater(context.applicationContext)
            updater.updateIfNeeded(forceRefresh = true)
            Log.i(TAG, "📰 新闻因子已刷新，暂停后台 AI 任务")
        } catch (e: Exception) {
            Log.w(TAG, "新闻刷新失败（不阻塞量化）: ${e.message}")
        }
        isQuantRunning = true
    }

    /** 恢复后台 AI 任务并立即触发一次（量化选股结束时调用） */
    suspend fun resumeAfterQuant(context: Context) {
        isQuantRunning = false
        Log.i(TAG, "▶️ 量化选股结束，恢复后台 AI 任务，立即触发一次")
        monitorJob?.cancel()
        try { monitorWatchlist(context) } catch (_: Exception) {}
        _appScope?.let { startPositionMonitor(context.applicationContext, it) }
    }

    /** 触发一次持仓监控（不重启定时器，供量化结束后额外调用） */
    suspend fun monitorWatchlistDirect(context: Context) {
        try { monitorWatchlist(context) } catch (e: Exception) { Log.w(TAG, "额外监控失败: ${e.message}") }
    }

    fun start(context: Context, scope: CoroutineScope) {
        Log.i(TAG, "🚀 启动后台任务")
        EastMoneyHotSectorSource.startPoolScheduler(scope)
        StockDataCenter.init(context.applicationContext, scope)
        _appScope = scope

        // 启动时执行一次：迁移超过 5 天的 AI 精选到自选股
        scope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(context.applicationContext)
                val today = LocalDate.now().format(DATE_FMT)
                migrateOldAiPicksToWatchlist(context.applicationContext, db, today)
            } catch (_: Exception) {}
        }

        // 启动时增量同步 daily_snapshot（拉取缺失的交易日数据）
        scope.launch(Dispatchers.IO) {
            syncMissingTradingDays(context.applicationContext)
        }

        // 启动时修复 strategy_trade_orders 中缺失的股票名称
        // （选股时可能因数据未导入导致名称为空，此处自动补全）
        scope.launch(Dispatchers.IO) {
            fixMissingOrderStockNames(context.applicationContext)
        }

        // 启动时监控真实持仓做T机会（后台自动生成推荐）
        scope.launch(Dispatchers.IO) {
            monitorTTradeOpportunities(context.applicationContext)
        }

        // 启动时更新板块周期摘要（每周/每月主要板块追踪）
        scope.launch(Dispatchers.IO) {
            try {
                val tracker = com.chin.stockanalysis.strategy.backtest.SectorPeriodTracker(context.applicationContext)
                tracker.update()
            } catch (e: Exception) {
                Log.w(TAG, "板块周期摘要更新失败: ${e.message}")
            }
        }

        // 启动时保存当日板块数据（供走势/轮动 Tab 使用）
        scope.launch(Dispatchers.IO) {
            try {
                // 等待板块池首次刷新完成
                kotlinx.coroutines.delay(5000)
                val engine = com.chin.stockanalysis.strategy.backtest.SectorRotationEngine(context.applicationContext)
                engine.saveDailySectorData()
            } catch (e: Exception) {
                Log.w(TAG, "板块每日数据保存失败: ${e.message}")
            }
        }

        // 启动时刷新热门板块-股票映射（确保 sector_stocks 表覆盖当前热门板块）
        scope.launch(Dispatchers.IO) {
            try {
                kotlinx.coroutines.delay(8000) // 等待板块池刷新完成
                val hotSource = EastMoneyHotSectorSource
                val sectorSource = com.chin.stockanalysis.stock.data.sources.EastMoneySectorSource()
                val db = StockDatabase.getInstance(context.applicationContext)
                // 取行业+概念热门板块（按 code 去重，用中文名作为 sectorKey）
                val hotSectors = (hotSource.industrySectors + hotSource.conceptSectors)
                    .distinctBy { it.code }
                    .map { it.code to it.name }
                if (hotSectors.isEmpty()) {
                    Log.i(TAG, "热门板块映射：池为空，跳过")
                    return@launch
                }
                var updated = 0
                for ((bkCode, sectorName) in hotSectors) {
                    try {
                        val stocks = sectorSource.fetchSectorComponents(bkCode, topN = 30, excludeKcb = false, excludeCyb = false)
                        if (stocks.isEmpty()) continue
                        val entities = stocks.map { s ->
                            SectorStockEntity(
                                sectorKey = sectorName,
                                sectorName = sectorName,
                                stockCode = s.code
                            )
                        }
                        db.sectorStockDao().insertAll(entities)
                        updated++
                    } catch (_: Exception) {}
                }
                Log.i(TAG, "🔥 热门板块映射刷新: $updated/${hotSectors.size} 个板块")
            } catch (e: Exception) {
                Log.w(TAG, "热门板块映射刷新失败: ${e.message}")
            }
        }

        startPositionMonitor(context.applicationContext, scope)
    }

    /**
     * 增量同步 daily_snapshot：
     * 查询本地最后一条数据日期，拉取从那天到今天之间所有缺失的交易日数据。
     *
     * - 首次安装：拉取最近 5 个交易日
     * - 正常使用：只拉取缺失的天数（通常 0-1 天）
     * - 长期未使用：拉取缺失的所有交易日（最多 30 天）
     */
    private suspend fun syncMissingTradingDays(context: Context) {
        try {
            val db = StockDatabase.getInstance(context)
            val today = LocalDate.now()

            // 查询本地最新数据日期
            val existingDates = try {
                db.dailySnapshotDao().getAvailableDates(30)
            } catch (_: Exception) { emptyList() }

            val latestDate = existingDates
                .filter { it <= today.format(DATE_FMT) }
                .maxOrNull()

            if (latestDate != null && latestDate >= today.format(DATE_FMT)) {
                Log.i(TAG, "📅 daily_snapshot 已是最新（$latestDate），跳过增量同步")
                return
            }

            // 计算需要拉取的天数
            val startLocalDate = if (latestDate != null) {
                LocalDate.parse(latestDate, DATE_FMT).plusDays(1)
            } else {
                today.minusDays(5)  // 首次安装，拉取最近 5 天
            }
            val daysToFetch = java.time.temporal.ChronoUnit.DAYS.between(startLocalDate, today).toInt().coerceIn(0, 30)

            if (daysToFetch <= 0) {
                Log.i(TAG, "📅 无需增量同步")
                return
            }

            Log.i(TAG, "📅 增量同步 daily_snapshot：从 ${startLocalDate.format(DATE_FMT)} 到 ${today.format(DATE_FMT)}（约 $daysToFetch 天）")

            val fetcher = com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(context)
            val count = fetcher.fetchAllHistoricalData(
                days = daysToFetch + 2,  // 多拉 2 天保险
                startDateOverride = startLocalDate,
                onProgress = { progress ->
                    Log.d(TAG, "📅 增量同步: ${progress.completedStocks}/${progress.totalStocks} (${progress.totalRecords} 条)")
                }
            )
            Log.i(TAG, "📅 增量同步完成：写入 $count 条记录")
        } catch (e: Exception) {
            Log.w(TAG, "📅 增量同步失败（不阻塞启动）: ${e.message}")
        }
    }

    /**
     * 修复所有数据表中缺失的股票名称
     *
     * 选股管道可能选出尚未导入 daily_snapshot 的股票，导致各表中 stockName 为空。
     * 使用 StockNameResolver 统一补全，覆盖以下表：
     *   - strategy_trade_orders（策略订单）
     *   - user_watchlist（自选股）
     *   - ai_selected_stock（AI 精选）
     *   - institutional_tips（机构线索）
     */
    private suspend fun fixMissingOrderStockNames(context: Context) {
        try {
            val db = StockDatabase.getInstance(context)
            val allMissingCodes = mutableSetOf<String>()

            // 1. strategy_trade_orders
            val ordersMissing = db.strategyTradeOrderDao().getRecent(200)
                .filter { it.stockName.isBlank() }
                .map { it.id to it.stockCode }
            allMissingCodes += ordersMissing.map { it.second }

            // 2. user_watchlist
            val watchlistMissing = try {
                db.userWatchlistDao().getAll()
                    .filter { it.stockName.isBlank() }
                    .map { it.stockCode to it.stockCode }
            } catch (_: Exception) { emptyList() }
            allMissingCodes += watchlistMissing.map { it.first }

            // 3. ai_selected_stock
            val aiMissing = try {
                db.aiSelectedStockDao().getAll()
                    .filter { it.stockName.isBlank() }
                    .map { it.id to it.stockCode }
            } catch (_: Exception) { emptyList() }
            allMissingCodes += aiMissing.map { it.second }

            // 4. institutional_tips
            val today = LocalDate.now().format(DATE_FMT)
            val tipsMissing = try {
                db.institutionalTipDao().getActiveTips(today)
                    .filter { it.stockName.isBlank() }
                    .map { it.id to it.stockCode }
            } catch (_: Exception) { emptyList() }
            allMissingCodes += tipsMissing.map { it.second }

            if (allMissingCodes.isEmpty()) return

            Log.i(TAG, "🔧 fixMissingOrderStockNames: ${allMissingCodes.size} 只股票缺少名称，开始补全")

            // 统一调用 StockNameResolver 批量解析
            val nameMap = StockNameResolver.resolveBatch(context, allMissingCodes.toList())
            Log.i(TAG, "  StockNameResolver 解析命中: ${nameMap.size}/${allMissingCodes.size}")

            // 回写各表
            var fixed = 0

            // strategy_trade_orders
            for ((id, code) in ordersMissing) {
                val name = nameMap[code] ?: continue
                db.strategyTradeOrderDao().updateStockName(id, name)
                fixed++
            }

            // user_watchlist（用 insert REPLACE 覆盖）
            for ((code, _) in watchlistMissing) {
                val name = nameMap[code] ?: continue
                try {
                    val existing = db.userWatchlistDao().getByCode(code)
                    if (existing != null) {
                        db.userWatchlistDao().insert(existing.copy(stockName = name))
                        fixed++
                    }
                } catch (_: Exception) {}
            }

            // ai_selected_stock
            for ((id, code) in aiMissing) {
                val name = nameMap[code] ?: continue
                try {
                    val entity = db.aiSelectedStockDao().getAll().find { it.id == id } ?: continue
                    db.aiSelectedStockDao().insert(entity.copy(stockName = name))
                    fixed++
                } catch (_: Exception) {}
            }

            // institutional_tips
            for ((id, code) in tipsMissing) {
                val name = nameMap[code] ?: continue
                try {
                    val tip = db.institutionalTipDao().getActiveTips(today).find { it.id == id } ?: continue
                    db.institutionalTipDao().insert(tip.copy(stockName = name))
                    fixed++
                } catch (_: Exception) {}
            }

            Log.i(TAG, "  ✅ fixMissingOrderStockNames 完成: 补全 $fixed 笔记录")
        } catch (e: Exception) {
            Log.w(TAG, "fixMissingOrderStockNames 失败: ${e.message}")
        }
    }

    private fun startPositionMonitor(context: Context, scope: CoroutineScope) {
        monitorJob?.cancel()
        monitorJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try { monitorWatchlist(context) }
                catch (e: Exception) { Log.w(TAG, "监控异常: ${e.message}") }
                // 每5分钟同时检查做T机会
                try { monitorTTradeOpportunities(context) }
                catch (e: Exception) { Log.w(TAG, "做T监控异常: ${e.message}") }
                delay(5 * 60 * 1000L)
            }
        }
    }

    private suspend fun monitorWatchlist(context: Context) {
        val db = StockDatabase.getInstance(context)
        val today = LocalDate.now().format(DATE_FMT)

        // ── 1. 监控自选股买卖点 ──
        val watchlist = db.userWatchlistDao().getAll()
        if (watchlist.isNotEmpty()) {
            val codes = watchlist.map { it.stockCode }

            val snapshots = try { db.dailySnapshotDao().getByDate(today) } catch (_: Exception) { emptyList() }
            val snapMap = snapshots.associateBy { s -> s.code }

            var buySignals = 0; var sellSignals = 0
            for (item in watchlist) {
                if (item.status == "SOLD") continue
                val snap = snapMap[item.stockCode] ?: continue
                val changePct = snap.changePct

                when {
                    item.status == "WATCHING" && changePct > 2.0 -> {
                        db.userWatchlistDao().update(item.copy(status = "BOUGHT", buyPrice = snap.close, buyDate = today))
                        buySignals++
                        Log.i(TAG, "🟢 自动买入: ${item.stockName}(${item.stockCode})")
                    }
                    item.status == "BOUGHT" && (changePct > 5.0 || changePct < -3.0) -> {
                        db.userWatchlistDao().update(item.copy(status = "SOLD", sellPrice = snap.close, sellDate = today))
                        sellSignals++
                        Log.i(TAG, "🟡 自动卖出: ${item.stockName}(${item.stockCode})")
                    }
                }
            }
            if (buySignals > 0 || sellSignals > 0)
                Log.i(TAG, "📊 监控(自选): ${watchlist.size}只 买入${buySignals} 卖出${sellSignals}")
        }

        // ── 2. 监控 AI 精选股买卖点 ──
        monitorAiSelectedStocks(context, db, today)
    }

    /**
     * 保留最近 5 天的 AI 精选记录，超出的迁移到自选股后删除
     */
    private suspend fun migrateOldAiPicksToWatchlist(context: Context, db: StockDatabase, today: String) {
        val aiDao = db.aiSelectedStockDao()
        val minDate = java.time.LocalDate.now().minusDays(5).format(DATE_FMT)
        val allAiStocks = aiDao.getAll()
        val oldStocks = allAiStocks.filter { it.selectedDate < minDate }
        if (oldStocks.isEmpty()) return

        Log.i(TAG, "🔄 迁移 ${oldStocks.size} 只超 5 天 AI 精选股到自选股...")
        val watchlistDao = db.userWatchlistDao()
        for (stock in oldStocks) {
            val existing = watchlistDao.getByCode(stock.stockCode)
            if (existing == null) {
                watchlistDao.insert(UserWatchlistEntity(
                    stockCode = stock.stockCode,
                    stockName = stock.stockName,
                    source = "ai_${stock.source}",
                    addedDate = today,
                    status = "WATCHING",
                    scoreAtAdd = stock.score
                ))
                Log.i(TAG, "  ➕ ${stock.stockName}(${stock.stockCode}) → 自选股")
            }
        }
        // 删除超过 5 天的记录
        aiDao.deleteBeforeDate(minDate)
        Log.i(TAG, "✅ AI 精选迁移完成，保留近 5 天数据")
    }

    /**
     * 监控 AI 精选股（当天）
     */
    private suspend fun monitorAiSelectedStocks(context: Context, db: StockDatabase, today: String) {
        val aiDao = db.aiSelectedStockDao()
        val aiStocks = aiDao.getByDate(today)
        if (aiStocks.isEmpty()) return

        val snapshots = try { db.dailySnapshotDao().getByDate(today) } catch (_: Exception) { emptyList() }
        val snapMap = snapshots.associateBy { s -> s.code }

        var buySignals = 0
        for (stock in aiStocks) {
            val snap = snapMap[stock.stockCode] ?: continue
            val changePct = snap.changePct

            // AI 精选当天涨幅 > 2% → 自动加入自选股并标记为 BOUGHT
            if (changePct > 2.0) {
                val watchlistDao = db.userWatchlistDao()
                val existing = watchlistDao.getByCode(stock.stockCode)
                if (existing == null) {
                    watchlistDao.insert(UserWatchlistEntity(
                        stockCode = stock.stockCode,
                        stockName = stock.stockName,
                        source = "ai_${stock.source}",
                        addedDate = today,
                        status = "BOUGHT",
                        buyPrice = snap.close,
                        buyDate = today,
                        scoreAtAdd = stock.score
                    ))
                    buySignals++
                    Log.i(TAG, "🤖 AI精选自动买入: ${stock.stockName}(${stock.stockCode})")
                }
            }
        }
        if (buySignals > 0)
            Log.i(TAG, "📊 AI监控: ${aiStocks.size}只精选 买入${buySignals}")
    }

    /** 将策略精选股添加到自选股 */
    suspend fun addToWatchlist(context: Context, stockCode: String, stockName: String, source: String, score: Int = 0) {
        val db = StockDatabase.getInstance(context)
        if (db.userWatchlistDao().getByCode(stockCode) == null) {
            db.userWatchlistDao().insert(UserWatchlistEntity(
                stockCode = stockCode, stockName = stockName, source = source,
                addedDate = LocalDate.now().format(DATE_FMT), scoreAtAdd = score
            ))
        }
    }

    suspend fun addBatchToWatchlist(context: Context, stocks: List<Triple<String, String, Int>>, source: String, tradeDate: String = LocalDate.now().format(DATE_FMT)) {
        StockDatabase.getInstance(context).userWatchlistDao().insertAll(
            stocks.map { (c, n, s) -> UserWatchlistEntity(stockCode = c, stockName = n, source = source, addedDate = tradeDate, scoreAtAdd = s) }
        )
    }

    /** 保存当天 AI 精选股（保留 5 天记录，清除超过 5 天的旧数据） */
    suspend fun saveAiSelectedStocks(context: Context, stocks: List<AiSelectedStockEntity>) {
        val db = StockDatabase.getInstance(context)
        val today = LocalDate.now().format(DATE_FMT)
        val minDate = LocalDate.now().minusDays(5).format(DATE_FMT)
        // 删除超过 5 天的记录，保留近 5 天
        db.aiSelectedStockDao().deleteBeforeDate(minDate)
        db.aiSelectedStockDao().insertAll(stocks)
        Log.i(TAG, "🤖 AI精选保存: ${stocks.size} 只 → ai_selected_stock（保留近 5 天）")
    }

    /** 清除当天 AI 精选（策略重新运行前调用） */
    suspend fun clearTodayAiSelected(context: Context) {
        val db = StockDatabase.getInstance(context)
        val today = LocalDate.now().format(DATE_FMT)
        db.aiSelectedStockDao().deleteByDate(today)
    }

    /**
     * 后台监控做T机会（覆盖所有周期 + 真实持仓）
     *
     * 每5分钟自动执行：
     * 1. 过期前一天仍为 PENDING 的推荐
     * 2. 扫描 4 个周期模拟持仓 + 真实持仓，生成做T信号
     * 3. 跟踪已有推荐的价格轨迹，检查目标是否触及
     * 4. 收盘时（15:00后）标记当日推荐为 TARGET_MISSED
     *
     * 用户可在「做T」面板查看推荐历史和执行状态。
     */
    private suspend fun monitorTTradeOpportunities(context: Context) {
        val db = StockDatabase.getInstance(context)
        val tEngine = com.chin.stockanalysis.strategy.trade.TTradeEngine(context)
        val today = java.time.LocalDate.now().toString()

        // 1. 过期旧推荐
        try { tEngine.expireOldRecommendations() } catch (_: Exception) {}

        // 2. 收盘结算（15:00后只执行一次）
        val now = java.time.LocalDateTime.now()
        if (now.hour >= 15 && now.minute < 5) {
            try { tEngine.markDayEnd(today) } catch (_: Exception) {}
        }

        // 3. 透过做T Pipeline 进行多维度分析（日K机构意图 + 外盘情绪 + 板块新闻）
        val periodTypes = listOf("UltraShortQuant", "ShortTermQuant", "MidTermQuant", "LongTermQuant")
        var totalSaved = 0
        var totalSignals = 0

        for (periodType in periodTypes) {
            try {
                val result = com.chin.stockanalysis.strategy.topology.xml.DagTradeExecutor
                    .executeTTradePipeline(context, periodType)
                totalSignals += result.signalsCount
                totalSaved += result.savedCount
            } catch (e: Exception) {
                Log.w(TAG, "做T Pipeline[$periodType] 失败: ${e.message}")
            }
        }

        // 4. 真实持仓也走 Pipeline（periodType = RealPosition）
        try {
            val positions = db.realPositionDao().getAllActive()
            if (positions.isNotEmpty()) {
                val result = com.chin.stockanalysis.strategy.topology.xml.DagTradeExecutor
                    .executeTTradePipeline(context, "RealPosition")
                totalSignals += result.signalsCount
                totalSaved += result.savedCount
            }
        } catch (e: Exception) {
            Log.w(TAG, "做T Pipeline[RealPosition] 失败: ${e.message}")
        }

        // 5. 跟踪已有推荐的价格轨迹（使用 daily snapshot 收盘价）
        val currentPrices = mutableMapOf<String, Double>()
        val snapshots = try { db.dailySnapshotDao().getByDate(today) } catch (_: Exception) { emptyList() }
        for (snap in snapshots) {
            currentPrices[snap.code] = snap.close
        }
        if (currentPrices.isNotEmpty()) {
            try { tEngine.trackOutcomeForRecommendations(currentPrices) } catch (_: Exception) {}
        }

        if (totalSaved > 0 || totalSignals > 0) {
            Log.i(TAG, "做T Pipeline 监控: 生成 $totalSignals 条信号，保存 $totalSaved 条推荐")

            // 6. 通知新产生的做T推荐（避免重复通知）
            try {
                val prefs = context.getSharedPreferences("t_trade_notification", Context.MODE_PRIVATE)
                val notifiedIds = prefs.getStringSet("notified_rec_ids", mutableSetOf<String>()) ?: mutableSetOf()
                val pendingRecs = db.tTradeRecommendationDao().getPendingByDate(today)
                val newRecs = pendingRecs.filter { it.id.toString() !in notifiedIds }

                if (newRecs.isNotEmpty()) {
                    val newIds = newRecs.map { it.id.toString() }.toSet()
                    val updatedNotified = notifiedIds.toMutableSet().apply { addAll(newIds) }
                    // 只保留最近 200 条记录，避免无限增长
                    val trimmed = updatedNotified.toList().takeLast(200).toSet()
                    prefs.edit().putStringSet("notified_rec_ids", trimmed).apply()

                    val notifier = com.chin.stockanalysis.notification.TradeNotifier
                    for (rec in newRecs) {
                        val signalLabel = when (rec.signalType) {
                            "T_BUY" -> "做T买入"
                            "T_SELL" -> "做T卖出"
                            "RT_SELL" -> "反T卖出"
                            "RT_BUY" -> "反T买回"
                            else -> rec.signalType
                        }
                        val periodLabel = when (rec.periodType) {
                            "UltraShortQuant" -> "超短"
                            "ShortTermQuant" -> "短线"
                            "MidTermQuant" -> "中线"
                            "LongTermQuant" -> "长线"
                            "RealPosition" -> "持仓"
                            else -> rec.periodType.take(4)
                        }
                        val title = "$signalLabel — ${rec.stockName}(${rec.stockCode.takeLast(4)})"
                        val body = buildString {
                            appendLine("[$periodLabel] ${"%.2f".format(rec.suggestedPrice)} → 目标 ${"%.2f".format(rec.targetPrice)}")
                            appendLine("建议 ${rec.quantity}股 | 预期 ${"%.2f%%".format(rec.expectedProfitPct)}")
                            if (rec.reason.isNotBlank()) append(rec.reason)
                            // 附成功率统计反馈
                            try {
                                val sr = tEngine.getSuccessRate(7)
                                if (sr.total > 0) {
                                    appendLine()
                                    append("📊 近7天: ${sr.total}条信号 | 命中率 ${"%.1f%%".format(sr.overallSuccessRate)} | 盈利占比 ${"%.1f%%".format(sr.profitRate)}")
                                }
                            } catch (_: Exception) {}
                        }.trim()
                        notifier.send(context, title, body, "TREC_${rec.id}")
                        // 自动执行（置信度超阈值时自动买卖）
                        autoExecuteIfEligible(context, rec, tEngine, title)
                    }
                    Log.i(TAG, "做T通知: 发送 ${newRecs.size} 条新推荐通知")
                }
            } catch (e: Exception) {
                Log.w(TAG, "做T通知发送失败: ${e.message}")
            }
        }
    }

    /**
     * 自动执行做T信号：当推荐置信度 ≥ 用户设定的阈值时，
     * 自动调用 TTradeEngine.executeTTrade 完成买卖，并标记推荐为已执行。
     */
    private suspend fun autoExecuteIfEligible(
        context: Context,
        rec: com.chin.stockanalysis.strategy.trade.TTradeRecommendationEntity,
        tEngine: com.chin.stockanalysis.strategy.trade.TTradeEngine,
        title: String
    ) {
        try {
            val notifier = com.chin.stockanalysis.notification.TradeNotifier
            if (!notifier.isAutoExecuteEnabled(context)) return

            // 只自动执行开仓腿（T_BUY / RT_SELL）；配对腿依赖已有持仓，交由用户确认
            val openType = when (rec.signalType) {
                "T_BUY" -> com.chin.stockanalysis.strategy.trade.TTradeType.T_BUY
                "RT_SELL" -> com.chin.stockanalysis.strategy.trade.TTradeType.RT_SELL
                else -> return
            }

            // 从 reason 前缀解析置信度：[置信度78%...]
            val confidencePct = Regex("置信度(\\d+)%")
                .find(rec.reason)?.groupValues?.get(1)?.toIntOrNull() ?: return
            val thresholdPct = (notifier.getAutoExecuteThreshold(context) * 100).toInt()
            if (confidencePct < thresholdPct) return

            val signal = com.chin.stockanalysis.strategy.trade.TTradeSignal(
                stockCode = rec.stockCode,
                stockName = rec.stockName,
                signalType = openType,
                suggestedPrice = rec.suggestedPrice,
                targetPrice = rec.targetPrice,
                quantity = rec.quantity,
                reason = rec.reason,
                expectedProfitPct = rec.expectedProfitPct,
                periodType = rec.periodType,
                confidence = confidencePct
            )
            val recordId = tEngine.executeTTrade(signal, rec.periodType)
            tEngine.markRecommendationExecuted(rec.id, rec.suggestedPrice)
            Log.i(TAG, "🤖 自动执行: ${rec.stockName}(${rec.stockCode}) ${rec.signalType} " +
                "置信度${confidencePct}%≥${thresholdPct}% @ ${rec.suggestedPrice} → 记录#$recordId")
            notifier.send(
                context,
                "🤖 已自动执行 — $title",
                "置信度${confidencePct}% ≥ 阈值${thresholdPct}%，系统已自动${
                    if (openType == com.chin.stockanalysis.strategy.trade.TTradeType.T_BUY) "买入" else "卖出"
                }\n${rec.stockName} ${"%.2f".format(rec.suggestedPrice)} × ${rec.quantity}股",
                "AUTO_EXEC_${rec.id}"
            )
        } catch (e: Exception) {
            Log.w(TAG, "自动执行做T失败: ${e.message}")
        }
    }
}
