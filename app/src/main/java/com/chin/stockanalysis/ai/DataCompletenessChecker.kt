package com.chin.stockanalysis.ai

import android.util.Log
import com.chin.stockanalysis.ApiProvider
import com.chin.stockanalysis.stock.database.SectorStockEntity
import com.chin.stockanalysis.stock.database.StockDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * ## 数据完整性检查器
 *
 * 从远端拉取数据后，检查并修复缺失的板块信息。
 *
 * ### 工作流程
 * ```
 * 检测股票是否在 sector_stocks 表中有记录
 *   ├─ 有 → 直接返回
 *   └─ 无 → AI 判断板块
 *          ├─ AI 成功 → 写入 DB
 *          └─ AI 失败 → hardcoded 回退 → 写入 DB
 * ```
 *
 * ### 使用方式
 * ```kotlin
 * val checker = DataCompletenessChecker(db)
 * val sector = checker.ensureSector(stockCode, stockName, provider)
 * ```
 */
class DataCompletenessChecker(private val db: StockDatabase) {

    companion object {
        private const val TAG = "DataChecker"

        /** Hardcoded 板块回退 (当 AI 不可用时) */
        private val HARDCORD_SECTOR: Map<String, String> = mapOf(
            "歌尔" to "消费电子", "沪电" to "PCB", "中芯" to "代工",
            "立讯" to "代工", "京东方" to "面板", "TCL" to "面板", "深天马" to "面板",
            "宁德" to "电池", "比亚迪" to "整车", "亿纬" to "电池",
            "赣锋" to "锂矿", "天齐" to "锂矿", "华友" to "钴镍",
            "紫金" to "金铜", "洛阳钼业" to "钼矿", "西部矿业" to "铜矿",
            "北方华创" to "设备", "中微" to "刻蚀", "盛美" to "清洗", "拓荆" to "镀膜",
            "长电" to "封测", "通富" to "封测", "华天" to "封测",
            "三环" to "MLCC", "风华" to "MLCC", "火炬" to "MLCC", "洁美" to "MLCC",
            "中际" to "光模块", "新易盛" to "光模块", "天孚" to "光器件", "光迅" to "光模块",
            "德科立" to "光模块", "联特" to "光模块", "博创" to "光器件", "太辰" to "光器件",
            "中兴" to "通信", "烽火" to "通信",
            "浪潮" to "服务器", "曙光" to "超算", "海光" to "CPU", "寒武纪" to "AI芯",
            "金山" to "办公", "中望" to "CAD",
            "兆易" to "存储", "韦尔" to "CIS", "闻泰" to "代工",
            "生益" to "覆铜板", "深南" to "基板", "鹏鼎" to "软板", "景旺" to "PCB",
            "长飞" to "光纤", "亨通" to "光纤",
            "阳光" to "逆变器", "固德" to "逆变器", "锦浪" to "逆变器",
            "隆基" to "硅片", "通威" to "硅料", "晶澳" to "组件", "福莱" to "玻璃",
            "迈瑞" to "器械", "联影" to "影像",
            "恒瑞" to "创新药", "百济" to "创新药", "药明" to "CXO", "康龙" to "CXO",
            "爱尔" to "眼科", "通策" to "口腔", "泰格" to "CXO",
            "德赛西威" to "智驾", "均胜" to "安全",
            "斯达" to "IGBT", "时代电气" to "IGBT",
            "中科" to "超算", "信维" to "射频", "东山" to "软板",
            "江丰" to "靶材", "安集" to "抛光液", "华虹" to "代工",
            "鱼跃" to "家用", "凯莱英" to "CXO", "福斯" to "胶膜"
        )
    }

    /**
     * 确保股票有板块信息。先从 DB 查询，没有则 AI 判断，再回退硬编码。
     *
     * @return 板块名称
     */
    suspend fun ensureSector(
        stockCode: String,
        stockName: String,
        provider: ApiProvider? = null
    ): String = withContext(Dispatchers.IO) {
        // 1. 查 DB
        try {
            val existing = db.sectorStockDao().getSectorNamesByStockCode(stockCode)
            if (existing.isNotEmpty()) return@withContext existing.first()
        } catch (_: Exception) { /* DB 查询失败继续 */ }

        // 2. AI 判断
        if (provider != null) {
            val aiSector = try {
                guessSectorViaAI(provider, stockName)
            } catch (_: Exception) { null }
            if (aiSector != null) {
                // 写入 DB
                try {
                    db.sectorStockDao().insertAll(listOf(SectorStockEntity(
                        sectorKey = aiSector.lowercase().replace(" ", "_"),
                        sectorName = aiSector,
                        stockCode = stockCode
                    )))
                    Log.d(TAG, "✅ AI 补齐 $stockName → $aiSector")
                } catch (_: Exception) { /* 插入失败忽略 */ }
                return@withContext aiSector
            }
        }

        // 3. Hardcoded 回退
        val fallback = hardcodedSector(stockName)
        if (fallback != "-") {
            try {
                db.sectorStockDao().insertAll(listOf(SectorStockEntity(
                    sectorKey = fallback.lowercase().replace(" ", "_"),
                    sectorName = fallback,
                    stockCode = stockCode
                )))
                Log.d(TAG, "📋 硬编码补齐 $stockName → $fallback")
            } catch (_: Exception) { /* 插入失败忽略 */ }
        }
        return@withContext fallback
    }

    /**
     * 快速模式：只用 DB + 硬编码，不调 AI。适合展示用（零延迟）。
     */
    suspend fun ensureSectorFast(stockCode: String, stockName: String): String = withContext(Dispatchers.IO) {
        try {
            val existing = db.sectorStockDao().getSectorNamesByStockCode(stockCode)
            if (existing.isNotEmpty() && existing.first() != "null") return@withContext existing.first()
        } catch (_: Exception) { }
        val fallback = hardcodedSector(stockName)
        if (fallback != "-") {
            try {
                db.sectorStockDao().insertAll(listOf(SectorStockEntity(
                    sectorKey = fallback.lowercase().replace(" ", "_"),
                    sectorName = fallback, stockCode = stockCode
                )))
            } catch (_: Exception) { }
        }
        return@withContext fallback
    }

    /** AI 判断股票所属板块 */
    private suspend fun guessSectorViaAI(provider: ApiProvider, stockName: String): String? {
        return suspendCancellableCoroutine { cont ->
            provider.sendMessageStream(
                messages = emptyList(),
                systemPrompt = "你是A股股票分析师。仅输出股票所属的A股板块名称(5字以内)。例如: 输入\"宁德时代\" → 输出\"电池\"。输入\"$stockName\" →",
                onSuccess = {},
                onComplete = { full ->
                    val result = full.trim().take(8).replace("\"", "")
                    cont.resume(if (result.isBlank() || result == "null") null else result)
                },
                onError = { cont.resume(null) }
            )
        }
    }

    /** 硬编码匹配 */
    fun hardcodedSector(name: String): String {
        for ((kw, label) in HARDCORD_SECTOR) {
            if (name.contains(kw)) return label
        }
        return "-"
    }
}