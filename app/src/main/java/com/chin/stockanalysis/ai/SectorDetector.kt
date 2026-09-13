package com.chin.stockanalysis.ai

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase

/**
 * 板块检测器 — 从用户文本中识别板块名称
 *
 * 用于机构推荐消息的板块归属判断，支援：
 * 1. 显式板块关键词匹配（红利、消费、AI硬件等）
 * 2. 股票代码→板块反查（通过 StockDataCenter）
 * 3. 股票名称→板块推断（通过名称关键词）
 */
object SectorDetector {

    private const val TAG = "SectorDetector"

    /**
     * 板块关键词映射表 — 每个板块的多个别名/关键词
     * 按优先级排列：精确匹配优先，模糊匹配在后
     */
    private val SECTOR_KEYWORDS: List<Pair<String, List<String>>> = listOf(
        // 科技/电子
        "半导体" to listOf("半导体", "芯片", "集成电路", "晶圆", "封测", "光刻", "存储芯片", "DRAM", "NAND"),
        "AI算力" to listOf("AI算力", "算力", "服务器", "AI服务器", "液冷", "GPU", "高速计算"),
        "光通信" to listOf("光通信", "光模块", "光纤", "CPO", "硅光", "光连接"),
        "消费电子" to listOf("消费电子", "手机产业链", "折叠屏", "VR", "AR", "MR", "智能穿戴"),
        "PCB" to listOf("PCB", "印制电路板", "覆铜板"),
        "存储" to listOf("存储", "存储芯片", "HBM", "内存"),

        // 新能源
        "新能源" to listOf("新能源", "光伏", "风电", "储能", "氢能", "锂电池", "钙钛矿", "固态电池"),
        "电力设备" to listOf("电力设备", "电网", "特高压", "充电桩", "配电"),
        "汽车" to listOf("汽车", "新能源汽车", "整车", "汽车零部件", "智能驾驶", "无人驾驶", "车路云"),

        // 金融/价值
        "银行" to listOf("银行", "大行", "股份行", "城商行"),
        "保险" to listOf("保险", "寿险", "财险"),
        "证券" to listOf("证券", "券商", "投行"),
        "红利" to listOf("红利", "高股息", "股息", "派息", "现金分红"),

        // 消费
        "消费" to listOf("消费", "大消费", "食品饮料", "白酒", "啤酒", "乳品", "调味品", "免税", "零售", "百货", "纺织服装", "服装", "家纺", "化妆品", "美容护理", "家用电器", "小家电"),
        "医药" to listOf("医药", "生物医药", "创新药", "CXO", "医疗器械", "中药", "疫苗", "体外诊断"),

        // 资源/材料
        "有色金属" to listOf("有色金属", "有色", "铜", "铝", "锌", "镍", "黄金", "白银", "稀土"),
        "稀缺小金属" to listOf("稀缺小金属", "小金属", "钨", "钼", "锗", "铟", "镓", "锗", "锑", "锡"),
        "煤炭" to listOf("煤炭", "焦煤", "焦炭", "动力煤"),
        "石油石化" to listOf("石油", "石化", "原油", "天然气", "油服"),
        "钢铁" to listOf("钢铁", "特钢", "板材", "铁矿石"),
        "化工" to listOf("化工", "化学", "农药", "化肥", "聚氨酯", "钛白粉", "维生素", "氟化工", "磷化工"),
        "建材" to listOf("建材", "水泥", "玻璃", "防水材料"),

        // 基础设施
        "电力" to listOf("电力", "火电", "水电", "核电", "绿电"),
        "交通运输" to listOf("交通运输", "高速公路", "港口", "机场", "航空", "航运", "物流"),
        "房地产" to listOf("房地产", "地产", "物业", "房地产开发"),

        // 其他概念
        "军工" to listOf("军工", "国防", "航天", "航空装备", "卫星", "商业航天"),
        "机器人" to listOf("机器人", "人形机器人", "工业机器人", "减速器", "伺服"),
        "低空经济" to listOf("低空经济", "eVTOL", "无人机", "飞行汽车"),
        "数据要素" to listOf("数据要素", "数据中心", "算力网络", "数字经济"),
        "国产替代" to listOf("国产替代", "自主可控", "信创", "操作系统", "数据库")
    )

    /**
     * 从用户文本中检测板块
     * @param text 用户输入文本
     * @return 检测到的板块名称列表（去重，按出现顺序）
     */
    fun detectSectors(text: String): List<String> {
        val result = mutableListOf<String>()
        for ((sectorName, keywords) in SECTOR_KEYWORDS) {
            if (keywords.any { text.contains(it) }) {
                if (result.none { it == sectorName }) result.add(sectorName)
            }
        }
        return result
    }

    /**
     * 查询股票所属板块（通过 StockDataCenter）
     * @param stockCode 股票代码（如 sh600519）
     * @param context Android Context
     * @return 板块名称列表，可能为空
     */
    suspend fun detectSectorForStock(stockCode: String, context: Context): List<String> {
        return try {
            com.chin.stockanalysis.stock.database.StockDataCenter.getSectorsByStock(stockCode)
        } catch (_: Exception) {
            // 降级：从数据库 sector_stock 表查询
            try {
                val db = StockDatabase.getInstance(context)
                db.sectorStockDao().getSectorNamesByStockCode(stockCode)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /**
     * 根据股票名称推断板块（名称关键词匹配）
     * @param stockName 股票名称（如「美邦股份」）
     * @return 推断的板块名称，可能为空
     */
    fun inferSectorByName(stockName: String): String? {
        for ((sectorName, keywords) in SECTOR_KEYWORDS) {
            if (keywords.any { stockName.contains(it) || it.contains(stockName.take(2)) }) {
                return sectorName
            }
        }
        // 常见股票名称模式推断
        return when {
            stockName.contains("银行") || stockName.contains("商行") -> "银行"
            stockName.contains("保险") -> "保险"
            stockName.contains("证券") || stockName.contains("证") -> "证券"
            stockName.contains("医药") || stockName.contains("制药") || stockName.contains("生物") -> "医药"
            stockName.contains("化工") || stockName.contains("化学") -> "化工"
            stockName.contains("电子") || stockName.contains("半导体") || stockName.contains("芯片") -> "半导体"
            stockName.contains("汽车") -> "汽车"
            stockName.contains("电力") || stockName.contains("水电") || stockName.contains("火电") -> "电力"
            stockName.contains("煤炭") -> "煤炭"
            stockName.contains("钢铁") || stockName.contains("钢") -> "钢铁"
            stockName.contains("地产") || stockName.contains("置业") || stockName.contains("地产") -> "房地产"
            else -> null
        }
    }

    /**
     * 综合检测：结合文本板块关键词 + 股票板块反查 + 名称推断
     *
     * @param message 用户完整消息文本
     * @param stocks 股票列表 (code, name)
     * @param context Android Context
     * @return 板块检测结果
     */
    suspend fun detect(
        message: String,
        stocks: List<Pair<String, String>>,
        context: Context
    ): DetectionResult {
        // 1. 从消息文本中检测显式提及的板块
        val textSectors = detectSectors(message)

        // 2. 为每只股票查找板块
        val stockSectors = mutableMapOf<String, List<String>>()
        for ((code, name) in stocks) {
            val sectors = detectSectorForStock(code, context).ifEmpty {
                // 降级：名称推断
                listOfNotNull(inferSectorByName(name))
            }
            if (sectors.isNotEmpty()) {
                stockSectors[code] = sectors
            }
        }

        // 3. 合并所有板块（文本检测 + 股票反查）
        val allSectors = (textSectors + stockSectors.values.flatten()).distinct()

        Log.i(TAG, "板块检测: 文本检测=${textSectors}, 股票板块=$stockSectors, 合并=$allSectors")

        return DetectionResult(
            textSectors = textSectors,
            stockSectors = stockSectors,
            allSectors = allSectors
        )
    }

    /**
     * 板块检测结果
     */
    data class DetectionResult(
        /** 从消息文本中检测到的板块 */
        val textSectors: List<String>,
        /** 每只股票对应的板块 (stockCode -> sectors) */
        val stockSectors: Map<String, List<String>>,
        /** 合并后所有板块（去重） */
        val allSectors: List<String>
    )
}
