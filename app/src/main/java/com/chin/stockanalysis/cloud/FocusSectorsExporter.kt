package com.chin.stockanalysis.cloud

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ## 用户「重点关注板块」采集器
 *
 * 数据源：`user_focus_sectors` 表（is_active=1）。该表内容来自用户在各入口
 * （量化选股 → AI 分析 / 策略助手等）输入的关键词，可能是板块名、主题词，
 * 也可能是具体股票代码/名称。
 *
 * 上传 schema（PC 侧 `smalltools/_etf_holdings.load_focus_sectors` 消费）：
 * ```
 * {
 *   "asof":    "2026-09-06 15:30",
 *   "sectors": ["半导体", "酿酒行业", ...],                 // 归一化板块名（保序去重）
 *   "stocks":  [{"code": "600519", "name": "贵州茅台", "sector": "酿酒行业"}, ...]  // 个股兜底
 * }
 * ```
 *
 * 归一化规则：
 * 1. 输入含 6 位代码 → 视为个股，反查所属板块（sector_stocks）→ 板块进 sectors、个股进 stocks；
 * 2. 否则命中本地板块名集合（sector_daily_record / sector_stocks）→ 取最贴合规范名进 sectors；
 * 3. 否则命中股票名称（stock_basics）→ 反查板块进 sectors、个股进 stocks；
 * 4. 仍不命中（宽泛主题词等）→ 原文进 sectors，交由 PC 端主题关键词（THEME_RULES）模糊匹配。
 */
class FocusSectorsExporter(private val context: Context) {

    private val db get() = StockDatabase.getInstance(context)

    /** 采集并序列化关注板块 → JSONObject（asof / sectors / stocks）。 */
    suspend fun buildJson(): JSONObject {
        val root = JSONObject()
        root.put("asof", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date()))

        val focusNames = readActiveFocusNames()
        val localSectors = loadLocalSectorNames()
        val sectors = LinkedHashSet<String>()
        val stocks = LinkedHashMap<String, JSONObject>()

        for (raw in focusNames) {
            val rawName = raw.trim()
            if (rawName.isEmpty() || rawName.length == 1) continue

            // ── 提取可能内嵌的 6 位代码（如 "600519贵州茅台" / "sh600519"）──
            val codeMatch = CODE_REGEX.find(rawName)
            if (codeMatch != null) {
                resolveStockByCode(codeMatch.groupValues[1], sectors, stocks)
                continue
            }

            val cleaned = cleanKeyword(rawName)
            if (cleaned.isEmpty()) continue

            // ── 优先板块名匹配 ──
            val canonical = pickCanonicalSector(cleaned, localSectors)
            if (canonical != null) {
                sectors.add(canonical)
                continue
            }

            // ── 其次股票名称匹配（反查板块）──
            val stock = findStockByName(cleaned)
            if (stock != null) {
                resolveStockByCode(stock.code, sectors, stocks, nameHint = stock.name)
                continue
            }

            // ── 兜底：保留原文作主题词，交由 PC 端 THEME_RULES 模糊匹配 ──
            sectors.add(rawName)
        }

        root.put("sectors", JSONArray().apply { sectors.forEach { put(it) } })
        root.put("stocks", JSONArray().apply { stocks.values.forEach { put(it) } })
        Log.i(TAG, "关注板块采集：启用 ${focusNames.size} 项 → sectors ${sectors.size} / stocks ${stocks.size}")
        return root
    }

    /** 读取 user_focus_sectors 中启用项（保序：最新启用在前，避免抖动） */
    private suspend fun readActiveFocusNames(): List<String> = try {
        db.userFocusSectorDao().getAll()
            .filter { it.isActive }
            .map { it.sectorName }
    } catch (e: Exception) {
        Log.w(TAG, "读取 user_focus_sectors 失败: ${e.message}")
        emptyList()
    }

    /** 本地板块名集合：板块行情近 90 日 + sector_stocks 全量映射，双源互补 */
    private suspend fun loadLocalSectorNames(): Set<String> {
        val names = LinkedHashSet<String>()
        try {
            db.sectorDailyRecordDao().getRecentDays(90)
                .forEach { it.sectorName.trim().takeIf { s -> s.isNotEmpty() }?.let { names.add(it) } }
        } catch (e: Exception) {
            Log.w(TAG, "读取板块行情名失败: ${e.message}")
        }
        try {
            db.sectorStockDao().getAllStockSectorPairs()
                .forEach { it.sector_name.trim().takeIf { s -> s.isNotEmpty() }?.let { names.add(it) } }
        } catch (e: Exception) {
            Log.w(TAG, "读取板块成分映射失败: ${e.message}")
        }
        return names
    }

    /** 个股代码 → 板块（sector_stocks 反查，多板块取首个）进 sectors，个股进 stocks */
    private suspend fun resolveStockByCode(
        code: String,
        sectors: LinkedHashSet<String>,
        stocks: LinkedHashMap<String, JSONObject>,
        nameHint: String = ""
    ) {
        val stockName = try {
            (if (nameHint.isNotBlank()) nameHint
            else db.stockBasicDao().getByCode(code)?.name).orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "查询股票 $code 基本信息失败: ${e.message}")
            nameHint
        }
        val sector = try {
            db.sectorStockDao().getSectorNamesByStockCode(code).firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "反查股票 $code 所属板块失败: ${e.message}")
            null
        }
        sector?.trim()?.takeIf { it.isNotEmpty() }?.let { sectors.add(it) }
        if (stockName.isNotBlank()) {
            stocks[code] = JSONObject()
                .put("code", code)
                .put("name", stockName)
                .put("sector", sector?.trim() ?: "")
        }
    }

    /** 板块名集合中挑选最贴合规范名：完全相等 > 以其开头 > 包含之（取最短） */
    private fun pickCanonicalSector(name: String, localSectors: Set<String>): String? {
        if (name.isEmpty()) return null
        localSectors.firstOrNull { it == name }?.let { return it }
        if (name.length >= 2) {
            localSectors.firstOrNull { it.startsWith(name) }?.let { return it }
        }
        // 包含匹配易误伤短词（如 "AI"），要求关键词 >= 3 字且差距不超过 8 字
        if (name.length >= 3) {
            val hits = localSectors
                .filter { it.contains(name) && it.length in (name.length + 1)..(name.length + 8) }
                .sortedBy { it.length }
            if (hits.isNotEmpty()) return hits.first()
        }
        return null
    }

    /** 按股票名称反查基础信息（精确优先，否则首个模糊命中） */
    private suspend fun findStockByName(name: String): StockHit? = try {
        val hits = db.stockBasicDao().searchByName(name)
        if (hits.isEmpty()) null
        else hits.firstOrNull { it.name == name }?.let { StockHit(it.code, it.name) }
            ?: StockHit(hits.first().code, hits.first().name)
    } catch (e: Exception) {
        Log.w(TAG, "按名称查询股票 $name 失败: ${e.message}")
        null
    }

    /** 关键词清洗：去掉常见后缀/空白/行情代码括号 */
    private fun cleanKeyword(raw: String): String {
        var s = raw.trim()
        if (s.length <= 1) return ""
        for (suffix in SECTOR_SUFFIXES) {
            if (s.endsWith(suffix) && s.length > suffix.length) s = s.dropLast(suffix.length)
        }
        s = s.trim()
        return if (s.length >= 2) s else ""
    }

    private data class StockHit(val code: String, val name: String)

    companion object {
        private const val TAG = "FocusSectorsExporter"
        private val CODE_REGEX = Regex("\\d{6}")
        private val SECTOR_SUFFIXES = listOf("板块", "概念", "指数", "行业", "类")
    }
}
