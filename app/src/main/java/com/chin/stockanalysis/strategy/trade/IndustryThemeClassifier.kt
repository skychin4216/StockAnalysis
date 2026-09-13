package com.chin.stockanalysis.strategy.trade

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.strategy.data.LeaderStockPool

/**
 * ## 产业主线分类器（中线/长线）
 *
 * 中线/长线看的是**产业的逻辑**（而非超短/短线的高弹性动量），
 * 所以中线/长线只应买入属于受关注产业主线的股票，避免追逐无产业逻辑的题材。
 *
 * ### 产业主线主题（近两年重点）
 * | 主题        | 典型板块/子板块                                         |
 * |-------------|---------------------------------------------------------|
 * | AI算力硬件   | AI算力、GPU服务器、光通信/光模块/CPO、铜缆高速连接、电子   |
 * | 存储        | 存储芯片、HBM、存储模组                                  |
 * | PCB         | 服务器PCB、HDI板、IC载板、覆铜板/电子布、铜箔              |
 * | 稀有金属     | 钨钼铌钒钛、稀土、铜、铝、锂等有色/稀缺小金属              |
 * | 稀有气体     | 氦气、稀有气体（电子特气）                                |
 * | 半导体国产替代 | 半导体设备、材料、光刻胶、设计、封测、功率半导体           |
 * | 战争资源     | 石油、黄金（地缘冲突时升温）                              |
 *
 * ### 使用
 * 中线/长线 Pipeline 在技术面（均线粘合）通过后，再叠加「产业主线」过滤：
 * 命中已知产业主题 → 通过；未命中 → 不进入中线/长线（避免无逻辑埋伏）。
 *
 * 通过 LeaderStockPool（用户/Agent 可动态增删）为主，辅以名称/主营关键词匹配。
 */
object IndustryThemeClassifier {

    private const val TAG = "IndustryThemeClassifier"

    /** 产业主题标识 */
    const val THEME_AI = "AI算力硬件"
    const val THEME_STORAGE = "存储"
    const val THEME_PCB = "PCB"
    const val THEME_RARE_METAL = "稀有金属"
    const val THEME_RARE_GAS = "稀有气体"
    const val THEME_SEMI_SUBSTITUTE = "半导体国产替代"
    const val THEME_WAR_RESOURCE = "战争资源(石油/黄金)"
    const val THEME_GRID = "电网设备"
    const val THEME_NEW_ENERGY = "新能源"

    /**
     * 判定结果
     */
    data class IndustryInfo(
        val theme: String?,        // 命中的产业主题，null=未命中
        val themeLabel: String,    // 展示文案（未命中时为"非主线产业"）
        val sector: String,        // 所属板块（LeaderStockPool 板块名）
        val subSector: String      // 所属子板块
    ) {
        val known: Boolean get() = theme != null
    }

    // ── 主题 → 板块/子板块 关键词映射（用于名称/主营关键词匹配） ──
    private val THEME_KEYWORDS: Map<String, List<String>> = mapOf(
        THEME_AI to listOf(
            "AI", "算力", "GPU", "服务器", "光模块", "光通信", "CPO", "铜缆", "高速连接",
            "数据中心", "交换机", "光芯片", "硅光", "算力", "电子", "液冷", "铜连接"
        ),
        THEME_STORAGE to listOf("存储", "HBM", "内存", "闪存", "DRAM", "NAND", "模组", "硬盘"),
        THEME_PCB to listOf("PCB", "印制电路", "覆铜板", "HDI", "IC载板", "载板", "电子布", "铜箔"),
        THEME_RARE_METAL to listOf("钨", "钼", "铌", "钒", "钛", "稀土", "有色", "铜", "铝", "锂", "钴", "镍", "稀有金属", "小金属"),
        THEME_RARE_GAS to listOf("氦", "稀有气体", "特气", "电子特气", "气体", "氖", "氩"),
        THEME_SEMI_SUBSTITUTE to listOf("半导体", "设备", "材料", "光刻", "刻蚀", "薄膜", "封测", "功率", "芯片", "晶圆", "国产替代", "离子注入"),
        THEME_WAR_RESOURCE to listOf("石油", "黄金", "油气", "原油", "石化", "采掘"),
        THEME_GRID to listOf("特高压", "智能电网", "变压器", "电网", "电线电缆", "储能", "电力设备"),
        THEME_NEW_ENERGY to listOf("锂电", "光伏", "绿电", "风电", "储能", "电池", "新能源")
    )

    // ── LeaderStockPool 板块名 → 主题 ──
    private val SECTOR_TO_THEME: Map<String, String> = mapOf(
        "AI算力" to THEME_AI,
        "光通信" to THEME_AI,
        "存储" to THEME_STORAGE,
        "PCB" to THEME_PCB,
        "稀缺小金属" to THEME_RARE_METAL,
        "有色金属" to THEME_RARE_METAL,
        "氦气" to THEME_RARE_GAS,
        "半导体" to THEME_SEMI_SUBSTITUTE,
        "电网设备" to THEME_GRID,
        "新能源" to THEME_NEW_ENERGY
    )

    /**
     * 判断一只股票是否属于已知产业主线，返回产业信息。
     *
     * @param context ApplicationContext
     * @param code    股票代码（sh600519 或 600519）
     * @param name    股票名称（可选，用于关键词匹配）
     * @param business 主营描述（可选，用于关键词匹配）
     */
    fun classify(context: Context, code: String, name: String, business: String = ""): IndustryInfo {
        val normalized = normalizeCode(code)

        // 1. 优先从 LeaderStockPool 板块配置精确匹配
        val poolTheme = poolThemeFor(context, normalized)
        if (poolTheme != null) return poolTheme

        // 2. 关键词匹配（名称 + 主营）
        val text = "$name $business"
        for ((theme, keywords) in THEME_KEYWORDS) {
            if (keywords.any { text.contains(it, ignoreCase = true) }) {
                return IndustryInfo(
                    theme = theme, themeLabel = theme,
                    sector = keywordSector(theme, text), subSector = ""
                )
            }
        }

        return IndustryInfo(
            theme = null, themeLabel = "非主线产业",
            sector = "其他", subSector = ""
        )
    }

    /**
     * 是否属于已知产业主线（快捷判断）。
     */
    fun isMainline(context: Context, code: String, name: String = "", business: String = ""): Boolean {
        return classify(context, code, name, business).known
    }

    // ── 内部实现 ──

    /** 从 LeaderStockPool 匹配板块 → 主题 */
    private fun poolThemeFor(context: Context, code: String): IndustryInfo? {
        return try {
            for (cfg in LeaderStockPool.getMainlineConfigs(context)) {
                for (ss in cfg.subSectors) {
                    if (code in ss.stocks) {
                        val theme = SECTOR_TO_THEME[cfg.name]
                        if (theme != null) {
                            return IndustryInfo(theme, theme, cfg.name, ss.name)
                        }
                        // 板块名未映射到主题，但属于产业主线 → 用板块名作主题
                        return IndustryInfo(cfg.name, cfg.name, cfg.name, ss.name)
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "LeaderStockPool 匹配失败: ${e.message}")
            null
        }
    }

    /** 关键词命中时，反推最可能的板块名（展示用） */
    private fun keywordSector(theme: String, text: String): String {
        return when (theme) {
            THEME_AI -> if (text.contains("光")) "光通信" else "AI算力"
            THEME_STORAGE -> "存储"
            THEME_PCB -> "PCB"
            THEME_RARE_METAL -> if (text.contains("稀")) "稀缺小金属" else "有色金属"
            THEME_RARE_GAS -> "稀有气体"
            THEME_SEMI_SUBSTITUTE -> "半导体"
            THEME_WAR_RESOURCE -> "石油/黄金"
            THEME_GRID -> "电网设备"
            THEME_NEW_ENERGY -> "新能源"
            else -> "产业"
        }
    }

    /** 标准化代码为带前缀形式（sh600519） */
    private fun normalizeCode(code: String): String {
        val c = code.trim().lowercase()
        return when {
            c.startsWith("sh") || c.startsWith("sz") || c.startsWith("bj") -> c
            c.startsWith("6") -> "sh$c"
            c.startsWith("0") || c.startsWith("2") || c.startsWith("3") -> "sz$c"
            else -> c
        }
    }
}
