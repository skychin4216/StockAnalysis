package com.chin.stockanalysis.strategy.data

/**
 * ## 行业季节/周期日历（v6）
 *
 * 两类驱动：
 * 1. **日历季节**：按月份生效的主题（春耕、夏季用电高峰、金九银十、年底备货…），
 *    命中主题板块的候选股获得 strength 加分。
 * 2. **商品锚定**：油价/锂价/铜价/金价等大宗价格方向确认后，锚定板块才生效
 *    （锚定方向由 backtest_params.json 的 seasonality.anchors 配置，可被 AI/人工每日更新）。
 *
 * 板块匹配：按股票名称关键词匹配（池内为中文名）。
 * 跨年月份：monthFrom > monthTo 表示跨年窗口（如 11 月 → 次年 1 月）。
 */
data class SeasonalityEntry(
    val theme: String,
    val keywords: List<String>,
    val monthFrom: Int,
    val monthTo: Int,
    val weight: Double = 1.0,
    val anchor: String? = null,
    val anchorUp: Boolean = true
)

/** 当期生效的季节主题（含锚定方向确认） */
data class ActiveSeasonality(
    val entry: SeasonalityEntry,
    val anchorState: String? = null // up/down（无锚定时为 null）
)

object IndustrySeasonalityCalendar {

    /** 锚定品种取值：up=上涨 down=下跌 unknown/缺省=未确认 */
    const val ANCHOR_UP = "up"
    const val ANCHOR_DOWN = "down"

    val ENTRIES: List<SeasonalityEntry> = listOf(
        // ── 日历季节（按月生效） ──
        SeasonalityEntry("春耕备耕", listOf("化肥", "农药", "种业", "种子", "农机", "尿素", "钾肥", "磷肥"), 2, 4, 1.2),
        SeasonalityEntry("年报预增季", listOf("预增"), 1, 4, 0.8),
        SeasonalityEntry("汛期防汛", listOf("水利", "防汛", "管网", "水泵"), 5, 7, 0.9),
        SeasonalityEntry("夏季用电高峰", listOf("电力", "电网", "特高压", "智能电网", "变压器", "电线电缆", "光伏", "储能"), 6, 8, 1.1),
        SeasonalityEntry("光伏装机旺季", listOf("光伏", "太阳能", "多晶硅", "逆变器"), 6, 11, 0.9),
        SeasonalityEntry("中报预增季", listOf("预增"), 7, 8, 0.8),
        SeasonalityEntry("金九银十", listOf("消费电子", "汽车电子", "家电", "PCB", "铜缆"), 9, 10, 1.0),
        SeasonalityEntry("年底备货", listOf("半导体", "算力", "存储", "光模块", "光通信", "服务器", "PCB"), 11, 12, 1.0),
        SeasonalityEntry("春季拉货", listOf("半导体", "面板", "PCB"), 2, 3, 0.9),
        SeasonalityEntry("供暖季", listOf("燃气", "煤炭", "供热", "电力"), 11, 1, 0.9),

        // ── 商品锚定（需方向确认） ──
        SeasonalityEntry("锂价上涨", listOf("锂", "盐湖"), 1, 12, 1.0, "锂价", true),
        SeasonalityEntry("锂价下跌(电池受益)", listOf("电池", "正极", "负极", "电解液"), 1, 12, 0.8, "锂价", false),
        SeasonalityEntry("油价上涨", listOf("石油", "油气", "油服", "石化"), 1, 12, 1.0, "油价", true),
        SeasonalityEntry("油价下跌(化工受益)", listOf("化工", "化纤", "塑料", "PTA"), 1, 12, 0.8, "油价", false),
        SeasonalityEntry("铜价上涨", listOf("铜", "电缆"), 1, 12, 1.0, "铜价", true),
        SeasonalityEntry("金价上涨", listOf("黄金", "贵金属"), 1, 12, 0.9, "金价", true)
    )

    /** 当前月份是否在 [from, to] 窗口内（支持跨年） */
    private fun monthInWindow(month: Int, from: Int, to: Int): Boolean =
        if (from <= to) month in from..to else month >= from || month <= to

    /**
     * 计算当期生效的季节主题。
     * @param month 当前月份 1..12
     * @param anchors 锚定方向表：品种("锂价"/"油价"/…) → ANCHOR_UP/ANCHOR_DOWN；缺省视为未确认
     */
    fun activeThemes(month: Int, anchors: Map<String, String>): List<ActiveSeasonality> =
        ENTRIES.mapNotNull { e ->
            val windowOk = monthInWindow(month, e.monthFrom, e.monthTo)
            if (!windowOk) return@mapNotNull null
            if (e.anchor != null) {
                val dir = anchors[e.anchor]
                val expected = if (e.anchorUp) ANCHOR_UP else ANCHOR_DOWN
                if (dir != expected) return@mapNotNull null
                ActiveSeasonality(e, dir)
            } else {
                ActiveSeasonality(e, null)
            }
        }

    /** 股票名命中哪些主题（返回命中条目） */
    fun matchStock(name: String, month: Int, anchors: Map<String, String>): List<SeasonalityEntry> =
        activeThemes(month, anchors)
            .filter { it.entry.keywords.any { k -> name.contains(k) } }
            .map { it.entry }
}
