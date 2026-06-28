package com.chin.stockanalysis.agent.router

import android.content.Context
import com.chin.stockanalysis.agent.news.NewsAssessment
import com.chin.stockanalysis.agent.news.NewsMonitorResult
import com.chin.stockanalysis.agent.news.NewsMonitoringAgent
import com.chin.stockanalysis.config.FeatureFlagManager
import com.chin.stockanalysis.news.HotSectorNewsUpdater
import com.chin.stockanalysis.stock.database.StockDatabase

/**
 * ## 新聞監控路由層
 *
 * Legacy: HotSectorNewsUpdater.updateIfNeeded() → 查 DB 最新結果 → 轉換為 NewsMonitorResult
 * Agent: NewsMonitoringAgent.monitorSector()
 */
interface NewsMonitoringService {
    suspend fun monitor(context: Context, sector: String? = null): NewsMonitorResult
}

/** Legacy 實現 — 調用 HotSectorNewsUpdater + 查 DB */
class LegacyNewsMonitoringService : NewsMonitoringService {
    override suspend fun monitor(context: Context, sector: String?): NewsMonitorResult {
        // 1. 觸發後台新聞更新
        val updater = HotSectorNewsUpdater(context)
        updater.updateIfNeeded(forceRefresh = true, ignoreQuantPause = true)

        // 2. 從 DB 讀取最新結果
        val db = StockDatabase.getInstance(context)
        val newsFactors = if (sector.isNullOrBlank()) {
            db.newsFactorDao().getAllActive(20)
        } else {
            db.newsFactorDao().getActiveBySector(sector, 20)
        }

        if (newsFactors.isEmpty()) {
            return NewsMonitorResult(
                success = false,
                summary = "未找到相關新聞",
                rawOutput = "No news factors found"
            )
        }

        // 3. 轉換為 NewsAssessment
        val items = newsFactors.map { entity ->
            NewsAssessment(
                title = entity.title,
                sentiment = if (entity.sentiment > 0) "利好" else if (entity.sentiment < 0) "利空" else "中性",
                strength = entity.impactStrength,
                scope = entity.sector.ifBlank { "全局" },
                duration = "",
                certainty = if (entity.impactStrength >= 70) "高" else if (entity.impactStrength >= 40) "中" else "低",
                affectedStocks = entity.tags.split(",").filter { it.isNotBlank() },
                recommendation = if (entity.sentiment > 0) "關注" else if (entity.sentiment < 0) "回避" else "觀望"
            )
        }

        val positiveCount = items.count { it.sentiment == "利好" }
        val negativeCount = items.count { it.sentiment == "利空" }

        return NewsMonitorResult(
            success = true,
            items = items,
            summary = "共 ${items.size} 條新聞：利好 $positiveCount / 利空 $negativeCount / 中性 ${items.size - positiveCount - negativeCount}",
            rawOutput = items.joinToString("\n") { "[${it.sentiment}] ${it.title}" }
        )
    }
}

/** Agent 實現 */
class AgentNewsMonitoringService : NewsMonitoringService {
    override suspend fun monitor(context: Context, sector: String?): NewsMonitorResult {
        val agent = NewsMonitoringAgent(context)
        return agent.monitorSector(sector)
    }
}

object NewsRouter {
    fun getService(): NewsMonitoringService {
        return when (FeatureFlagManager.resolveRoute(FeatureFlagManager.newsMonitoringRoute)) {
            com.chin.stockanalysis.config.AgentRoute.AGENT_FRAMEWORK -> AgentNewsMonitoringService()
            else -> LegacyNewsMonitoringService()
        }
    }
}
