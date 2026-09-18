package com.chin.stockanalysis.strategy.topology.pipelines

import com.chin.stockanalysis.config.DataConfig
import com.chin.stockanalysis.stock.database.StockDatabase

/**
 * 增强过滤（v7）：smalltools/_pool_filters.py 里回测验证有效的过滤规则搬回 APK。
 *
 * 与 smalltool 口径一致，全部通过 app_config.json `poolFilter` 区块开关：
 * - stickyHardPeriods：③ 粘合持续 硬性（中线默认，消灭"瞬间收敛"假粘合）
 * - shortHardPeriods ：② 多头排列 + ⑬ 三日不新低 硬性（超短/短线默认）
 * - sectorPeriods    ：板块代理过滤（行业近20日平均涨幅 < sectorThreshold 剔除；长线豁免）
 * - passCountOverride：按周期提高 minPassCount 门槛（如 短线 7 / 中线 7）
 *
 * 板块过滤：用池内同板块股票近 N 日平均涨幅近似行业强弱（APK 侧无全行业指数数据，
 * 口径与发布链路 smalltools/_publish_candidates.py hot_sectors 一致）。
 */
object EnhancedPoolFilter {

    /** 总开关（false 时全部增强失效，等价 smalltool 未启用） */
    val enabled: Boolean
        get() = DataConfig.get("poolFilter.enable", "true").toBoolean()

    /** 粘合持续硬性的周期集合 */
    fun stickyHardPeriods(): Set<String> =
        parseSet(DataConfig.get("poolFilter.stickyHardPeriods", ""), setOf("中线"))

    /** 多头排列+三日不新低硬性的周期集合 */
    fun shortHardPeriods(): Set<String> =
        parseSet(DataConfig.get("poolFilter.shortHardPeriods", ""), setOf("超短线", "短线"))

    /** 板块代理过滤的周期集合（长线默认豁免） */
    fun sectorPeriods(): Set<String> =
        parseSet(DataConfig.get("poolFilter.sectorPeriods", ""), setOf("超短线", "短线", "中线"))

    /** 板块过滤阈值：行业近20日平均涨幅低于该值（%）剔除 */
    fun sectorThreshold(): Double =
        DataConfig.get("poolFilter.sectorThreshold", "-3.0").toDoubleOrNull() ?: -3.0

    /** minPassCount 周期覆盖（如 poolFilter.passCountOverride.短线=7） */
    fun passCountOverride(period: String): Int? =
        DataConfig.get("poolFilter.passCountOverride.$period", "").toIntOrNull()?.takeIf { it > 0 }

    /** 板块涨幅计算回看窗口（日） */
    val sectorLookback: Int
        get() = DataConfig.get("poolFilter.sectorLookback", "20").toIntOrNull() ?: 20

    private fun parseSet(raw: String, def: Set<String>): Set<String> =
        if (raw.isBlank()) def
        else raw.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    // ── 行业/板块映射（对齐 smalltools/_pool_filters.py SECTOR_RULES 关键词规则） ──
    private val SECTOR_RULES: List<Pair<String, List<String>>> = listOf(
        "稀土有色" to listOf("稀土", "钨", "铜", "铝", "钴", "镍", "锂", "钛", "锡", "锌", "铅", "钼", "有色", "资源", "矿业"),
        "贵金属" to listOf("黄金", "金", "银泰", "山金"),
        "半导体电子" to listOf("半导体", "芯片", "中芯", "澜起", "卓胜微", "长电", "至纯", "联创", "容大", "彤程", "京瓷", "积电", "新阳"),
        "光通信" to listOf("光", "仕佳", "烽火", "铭普", "中际", "旭创", "新易盛", "天孚", "光迅", "博创", "特发"),
        "光伏新能源" to listOf("光伏", "隆基", "日升", "阳光电源", "锦浪", "正泰", "晶澳", "通威", "德业", "捷佳"),
        "电网设备" to listOf("电网", "南瑞", "风范", "宝胜", "积成", "平高", "特变", "许继", "思源", "国电南自", "东方电子"),
        "电力公用" to listOf("电力", "华能", "长电", "水电", "华电", "大唐", "三峡", "核电", "明星"),
        "液冷温控" to listOf("英维克", "申菱", "高澜", "佳力图", "同飞", "依米康", "川润"),
        "医药生物" to listOf("医药", "沃森", "迈瑞", "白药", "生物", "长春", "百济", "恒瑞", "智飞", "康泰", "药明", "同仁堂", "片仔"),
        "化工材料" to listOf("化工", "索普", "新材", "昊华", "万华", "华鲁", "恒力", "荣盛", "三友", "中泰", "巨化", "川恒"),
        "钢铁" to listOf("钢", "西宁", "宝钢", "鞍钢", "太钢", "华菱"),
        "消费" to listOf("茅台", "五粮液", "海天", "伊利", "泸州", "美的", "格力", "海尔"),
    )

    /** 股票所属板块（按名称关键词，命中失败归"其他"） */
    fun sectorOf(code: String, name: String): String {
        for ((sector, kws) in SECTOR_RULES) {
            if (kws.isNotEmpty() && kws.any { name.contains(it) }) return sector
        }
        return "其他"
    }

    // ── 板块涨幅表 ──

    /**
     * 构建板块近 N 日平均涨幅表 {板块: 平均涨幅%}。
     * 基于池内股票的 daily_snapshot 聚合（与发布链路 hot_sectors 口径一致）。
     * 单只股票数据不足/非最新日期会被跳过；池为空或数据不足时返回空表（不启用过滤）。
     */
    suspend fun buildSectorRet20(
        db: StockDatabase,
        codes: List<String>,
        date: String,
        lookback: Int = sectorLookback
    ): Map<String, Double> {
        if (!enabled || codes.isEmpty()) return emptyMap()
        val dao = db.dailySnapshotDao()
        val sums = HashMap<String, MutableList<Double>>()
        for (code in codes) {
            val snaps = try {
                dao.getByCode(code, lookback + 10).sortedBy { it.date }
            } catch (e: Exception) {
                continue
            }
            if (snaps.size <= lookback || snaps.lastOrNull()?.date != date) continue
            val closes = snaps.map { it.close }
            val base = closes[closes.size - lookback]
            if (base <= 0) continue
            val pct = (closes.last() / base - 1) * 100
            val sector = sectorOf(code, snaps.lastOrNull()?.name ?: code)
            sums.getOrPut(sector) { ArrayList() }.add(pct)
        }
        return sums.mapValues { (_, v) -> v.sum() / v.size }
    }
}
