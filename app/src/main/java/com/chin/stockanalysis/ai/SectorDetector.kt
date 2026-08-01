package com.chin.stockanalysis.ai

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase

/**
 * 板塊檢測器 — 從用戶文本中識別板塊名稱
 *
 * 用於機構推薦消息的板塊歸屬判斷，支援：
 * 1. 顯式板塊關鍵詞匹配（紅利、消費、AI硬件等）
 * 2. 股票代碼→板塊反查（通過 StockDataCenter）
 * 3. 股票名稱→板塊推斷（通過名稱關鍵詞）
 */
object SectorDetector {

    private const val TAG = "SectorDetector"

    /**
     * 板塊關鍵詞映射表 — 每個板塊的多個別名/關鍵詞
     * 按優先級排列：精確匹配優先，模糊匹配在後
     */
    private val SECTOR_KEYWORDS: List<Pair<String, List<String>>> = listOf(
        // 科技/電子
        "半導體" to listOf("半導體", "芯片", "集成電路", "晶圓", "封測", "光刻", "存儲芯片", "DRAM", "NAND"),
        "AI算力" to listOf("AI算力", "算力", "服務器", "AI服務器", "液冷", "GPU", "高速計算"),
        "光通信" to listOf("光通信", "光模塊", "光纖", "CPO", "硅光", "光連接"),
        "消費電子" to listOf("消費電子", "手機產業鏈", "折叠屏", "VR", "AR", "MR", "智能穿戴"),
        "PCB" to listOf("PCB", "印製電路板", "覆銅板"),
        "存儲" to listOf("存儲", "存儲芯片", "HBM", "內存"),

        // 新能源
        "新能源" to listOf("新能源", "光伏", "風電", "儲能", "氫能", "鋰電池", "鈣鈦礦", "固態電池"),
        "電力設備" to listOf("電力設備", "電網", "特高壓", "充電樁", "配電"),
        "汽車" to listOf("汽車", "新能源汽車", "整車", "汽車零部件", "智能駕駛", "無人駕駛", "車路雲"),

        // 金融/價值
        "銀行" to listOf("銀行", "大行", "股份行", "城商行"),
        "保險" to listOf("保險", "壽險", "財險"),
        "證券" to listOf("證券", "券商", "投行"),
        "紅利" to listOf("紅利", "高股息", "股息", "派息", "現金分紅"),

        // 消費
        "消費" to listOf("消費", "大消費", "食品飲料", "白酒", "啤酒", "乳品", "調味品", "免稅", "零售", "百貨", "紡織服裝", "服裝", "家紡", "化妝品", "美容護理", "家用電器", "小家電"),
        "醫藥" to listOf("醫藥", "生物醫藥", "創新藥", "CXO", "醫療器械", "中藥", "疫苗", "體外診斷"),

        // 資源/材料
        "有色金屬" to listOf("有色金屬", "有色", "銅", "鋁", "鋅", "鎳", "黃金", "白銀", "稀土"),
        "稀缺小金屬" to listOf("稀缺小金屬", "小金屬", "鎢", "鉬", "鍺", "銦", "鎵", "鍺", "銻", "錫"),
        "煤炭" to listOf("煤炭", "焦煤", "焦炭", "動力煤"),
        "石油石化" to listOf("石油", "石化", "原油", "天然氣", "油服"),
        "鋼鐵" to listOf("鋼鐵", "特鋼", "板材", "鐵礦石"),
        "化工" to listOf("化工", "化學", "農藥", "化肥", "聚氨酯", "鈦白粉", "維生素", "氟化工", "磷化工"),
        "建材" to listOf("建材", "水泥", "玻璃", "防水材料"),

        // 基礎設施
        "電力" to listOf("電力", "火電", "水電", "核電", "綠電"),
        "交通運輸" to listOf("交通運輸", "高速公路", "港口", "機場", "航空", "航運", "物流"),
        "房地產" to listOf("房地產", "地產", "物業", "房地產開發"),

        // 其他概念
        "軍工" to listOf("軍工", "國防", "航天", "航空裝備", "衛星", "商業航天"),
        "機器人" to listOf("機器人", "人形機器人", "工業機器人", "減速器", "伺服"),
        "低空經濟" to listOf("低空經濟", "eVTOL", "無人機", "飛行汽車"),
        "數據要素" to listOf("數據要素", "數據中心", "算力網絡", "數字經濟"),
        "國產替代" to listOf("國產替代", "自主可控", "信創", "操作系統", "數據庫")
    )

    /**
     * 從用戶文本中檢測板塊
     * @param text 用戶輸入文本
     * @return 檢測到的板塊名稱列表（去重，按出現順序）
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
     * 查詢股票所屬板塊（通過 StockDataCenter）
     * @param stockCode 股票代碼（如 sh600519）
     * @param context Android Context
     * @return 板塊名稱列表，可能為空
     */
    suspend fun detectSectorForStock(stockCode: String, context: Context): List<String> {
        return try {
            com.chin.stockanalysis.stock.database.StockDataCenter.getSectorsByStock(stockCode)
        } catch (_: Exception) {
            // 降級：從數據庫 sector_stock 表查詢
            try {
                val db = StockDatabase.getInstance(context)
                db.sectorStockDao().getSectorNamesByStockCode(stockCode)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /**
     * 根據股票名稱推斷板塊（名稱關鍵詞匹配）
     * @param stockName 股票名稱（如「美邦股份」）
     * @return 推斷的板塊名稱，可能為空
     */
    fun inferSectorByName(stockName: String): String? {
        for ((sectorName, keywords) in SECTOR_KEYWORDS) {
            if (keywords.any { stockName.contains(it) || it.contains(stockName.take(2)) }) {
                return sectorName
            }
        }
        // 常見股票名稱模式推斷
        return when {
            stockName.contains("銀行") || stockName.contains("商行") -> "銀行"
            stockName.contains("保險") -> "保險"
            stockName.contains("證券") || stockName.contains("證") -> "證券"
            stockName.contains("醫藥") || stockName.contains("製藥") || stockName.contains("生物") -> "醫藥"
            stockName.contains("化工") || stockName.contains("化學") -> "化工"
            stockName.contains("電子") || stockName.contains("半導體") || stockName.contains("芯片") -> "半導體"
            stockName.contains("汽車") -> "汽車"
            stockName.contains("電力") || stockName.contains("水電") || stockName.contains("火電") -> "電力"
            stockName.contains("煤炭") -> "煤炭"
            stockName.contains("鋼鐵") || stockName.contains("鋼") -> "鋼鐵"
            stockName.contains("地產") || stockName.contains("置業") || stockName.contains("地產") -> "房地產"
            else -> null
        }
    }

    /**
     * 綜合檢測：結合文本板塊關鍵詞 + 股票板塊反查 + 名稱推斷
     *
     * @param message 用戶完整消息文本
     * @param stocks 股票列表 (code, name)
     * @param context Android Context
     * @return 板塊檢測結果
     */
    suspend fun detect(
        message: String,
        stocks: List<Pair<String, String>>,
        context: Context
    ): DetectionResult {
        // 1. 從消息文本中檢測顯式提及的板塊
        val textSectors = detectSectors(message)

        // 2. 為每隻股票查找板塊
        val stockSectors = mutableMapOf<String, List<String>>()
        for ((code, name) in stocks) {
            val sectors = detectSectorForStock(code, context).ifEmpty {
                // 降級：名稱推斷
                listOfNotNull(inferSectorByName(name))
            }
            if (sectors.isNotEmpty()) {
                stockSectors[code] = sectors
            }
        }

        // 3. 合併所有板塊（文本檢測 + 股票反查）
        val allSectors = (textSectors + stockSectors.values.flatten()).distinct()

        Log.i(TAG, "板塊檢測: 文本檢測=${textSectors}, 股票板塊=$stockSectors, 合併=$allSectors")

        return DetectionResult(
            textSectors = textSectors,
            stockSectors = stockSectors,
            allSectors = allSectors
        )
    }

    /**
     * 板塊檢測結果
     */
    data class DetectionResult(
        /** 從消息文本中檢測到的板塊 */
        val textSectors: List<String>,
        /** 每隻股票對應的板塊 (stockCode -> sectors) */
        val stockSectors: Map<String, List<String>>,
        /** 合併後所有板塊（去重） */
        val allSectors: List<String>
    )
}
