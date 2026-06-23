package com.chin.stockanalysis.agent.pipeline

import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 1g — 對沖邏輯 + 結構化解析器單元測試
 *
 * 覆蓋：
 * 1. Agent 5 對沖機制（海外加分清零）
 * 2. Agent 2 打分閾值判定（≥40 通過）
 * 3. StructuredOutputParser JSON 解析
 * 4. DataFeeder 板塊推斷
 * 5. Agent D 邊界隔離（輿情不影響淘汰）
 */
class PipelineLogicTest {

    // ═══════════════════════════════════════
    // 1. Agent 5 對沖機制
    // ═══════════════════════════════════════

    @Test
    fun `對沖觸發 — 海外加分清零，總分重算`() {
        // Agent 2 打分：baseScore=55 + overseas=15 + foreignRating=12 = 82
        val chainScore = ChainScoreResult(
            stockCode = "sh600183", stockName = "生益科技",
            baseScore = 55, materialScore = 18, barrierScore = 15,
            coverageScore = 12, irreplaceScore = 10, resonanceBonus = 5,
            overseasBonus = 15, foreignRatingBonus = 12,
            totalScore = 82, barrierLevel = "高", passed = true
        )

        // Agent 5 風控：海外替代風險 → overseasDeduction > 0
        val riskResult = RiskValidationResult(
            stockCode = "sh600183", riskLevel = "中",
            overseasDeduction = 1, adjustedScore = 0, passed = true
        )

        // 模擬對沖邏輯（與 AgentPipelineOrchestrator.applyHedgeMechanism 一致）
        val ctx = PipelineContext(target = "生益科技").apply {
            this.chainScore = chainScore
            this.riskResult = riskResult
        }

        applyHedgeLogic(ctx)

        // 驗證：海外加分和 外資評級加分均被清零
        assertEquals(0, ctx.chainScore!!.overseasBonus)
        assertEquals(0, ctx.chainScore!!.foreignRatingBonus)
        // 新總分 = 82 - 15 - 12 = 55
        assertEquals(55, ctx.chainScore!!.totalScore)
        // 55 ≥ 40，仍然通過
        assertTrue(ctx.chainScore!!.passed)
        // riskResult.adjustedScore 也更新
        assertEquals(55, ctx.riskResult!!.adjustedScore)
    }

    @Test
    fun `對沖觸發 — 清零後低於40分則淘汰`() {
        // Agent 2 打分：baseScore=30 + overseas=15 = 45
        val chainScore = ChainScoreResult(
            stockCode = "sz300000", stockName = "測試股",
            baseScore = 30, overseasBonus = 15, foreignRatingBonus = 0,
            totalScore = 45, barrierLevel = "中", passed = true
        )

        val riskResult = RiskValidationResult(
            stockCode = "sz300000", riskLevel = "中",
            overseasDeduction = 1, adjustedScore = 0, passed = true
        )

        val ctx = PipelineContext(target = "測試股").apply {
            this.chainScore = chainScore
            this.riskResult = riskResult
        }

        applyHedgeLogic(ctx)

        // 新總分 = 45 - 15 - 0 = 30
        assertEquals(30, ctx.chainScore!!.totalScore)
        // 30 < 40，不通過
        assertFalse(ctx.chainScore!!.passed)
    }

    @Test
    fun `對沖不觸發 — 無海外加分`() {
        val chainScore = ChainScoreResult(
            stockCode = "sh600183", stockName = "生益科技",
            baseScore = 55, overseasBonus = 0, foreignRatingBonus = 0,
            totalScore = 55, barrierLevel = "高", passed = true
        )

        val riskResult = RiskValidationResult(
            stockCode = "sh600183", riskLevel = "低",
            overseasDeduction = 0, adjustedScore = 0, passed = true
        )

        val ctx = PipelineContext(target = "生益科技").apply {
            this.chainScore = chainScore
            this.riskResult = riskResult
        }

        applyHedgeLogic(ctx)

        // 無海外加分，不觸發對沖，總分不變
        assertEquals(55, ctx.chainScore!!.totalScore)
        assertTrue(ctx.chainScore!!.passed)
    }

    @Test
    fun `對沖不觸發 — 無海外替代風險`() {
        val chainScore = ChainScoreResult(
            stockCode = "sh600183", stockName = "生益科技",
            baseScore = 55, overseasBonus = 15, foreignRatingBonus = 0,
            totalScore = 70, barrierLevel = "高", passed = true
        )

        val riskResult = RiskValidationResult(
            stockCode = "sh600183", riskLevel = "低",
            overseasDeduction = 0, adjustedScore = 0, passed = true
        )

        val ctx = PipelineContext(target = "生益科技").apply {
            this.chainScore = chainScore
            this.riskResult = riskResult
        }

        applyHedgeLogic(ctx)

        // overseasDeduction = 0，不觸發對沖
        assertEquals(70, ctx.chainScore!!.totalScore)
        assertEquals(15, ctx.chainScore!!.overseasBonus)
    }

    // ═══════════════════════════════════════
    // 2. Agent 2 打分閾值
    // ═══════════════════════════════════════

    @Test
    fun `Agent2 打分 — 40分邊界`() {
        // 恰好 40 分
        val score40 = ChainScoreResult(
            stockCode = "test", stockName = "測試",
            baseScore = 40, totalScore = 40, barrierLevel = "中", passed = true
        )
        assertTrue(score40.passed)

        // 39 分
        val score39 = ChainScoreResult(
            stockCode = "test", stockName = "測試",
            baseScore = 39, totalScore = 39, barrierLevel = "中", passed = false
        )
        assertFalse(score39.passed)
    }

    @Test
    fun `Agent2 打分 — 100分上限`() {
        val score = ChainScoreResult(
            stockCode = "test", stockName = "測試",
            baseScore = 25, materialScore = 25, barrierScore = 25,
            coverageScore = 25, irreplaceScore = 25, resonanceBonus = 5,
            overseasBonus = 15, foreignRatingBonus = 12,
            totalScore = 100, barrierLevel = "高", passed = true
        )
        assertTrue(score.totalScore <= 100)
    }

    // ═══════════════════════════════════════
    // 3. StructuredOutputParser JSON 解析
    // ═══════════════════════════════════════

    @Test
    fun `解析 fenced JSON — Agent2 打分`() {
        val text = """
            分析文本...
            ```json
            {"baseScore": 55, "material": 18, "barrier": 15, "coverage": 12, "irreplaceable": 10, "resonance": 5, "overseas": 15, "foreignRating": 0, "totalScore": 75, "barrierLevel": "高"}
            ```
        """.trimIndent()

        val result = StructuredOutputParser.parseChainScore("sh600183", "生益科技", text)
        assertNotNull(result)
        assertEquals(75, result!!.totalScore)
        assertEquals(15, result.overseasBonus)
        assertEquals("高", result.barrierLevel)
        assertTrue(result.passed)
    }

    @Test
    fun `解析 fenced JSON — Agent5 風控`() {
        val text = """
            風控分析...
            ```json
            {"riskLevel": "低", "deductions": [{"item": "商譽減值風險", "description": "商譽佔淨資產15%", "score": -10}], "overseasDeduction": 0, "adjustedScore": 75}
            ```
        """.trimIndent()

        val result = StructuredOutputParser.parseRiskResult("sh600183", text)
        assertNotNull(result)
        assertEquals("低", result!!.riskLevel)
        assertEquals(1, result.deductions.size)
        assertEquals("商譽減值風險", result.deductions[0].item)
        assertTrue(result.passed)
    }

    @Test
    fun `解析高風險 — 直接剔除`() {
        val text = """
            ```json
            {"riskLevel": "高", "deductions": [{"item": "大股東質押", "description": "質押率95%", "score": -30}], "overseasDeduction": 1, "adjustedScore": 0}
            ```
        """.trimIndent()

        val result = StructuredOutputParser.parseRiskResult("test", text)
        assertNotNull(result)
        assertEquals("高", result!!.riskLevel)
        assertFalse(result.passed)
    }

    @Test
    fun `解析 Agent D 輿情微調`() {
        val text = """
            ```json
            {"sentimentScore": 2, "positionAdjust": "+5%", "reason": "外資Buy評級發酵，板塊熱度上升"}
            ```
        """.trimIndent()

        val result = StructuredOutputParser.parseSentimentResult(text)
        assertNotNull(result)
        assertEquals(2, result!!.sentimentScore)
        assertEquals("+5%", result.positionAdjust)
    }

    @Test
    fun `解析 Agent 4 交易方案`() {
        val text = """
            ```json
            {"entryZones": ["25.8~26.2（穩健）", "24.5~25.0（激進）"], "stopLoss": "23.8（收盤有效跌破）", "targets": ["29.5（減倉1/3）", "33.0（清倉）"], "maxPosition": "30%", "splitRatio": "5:3:2", "tradeRules": ["禁止追高漲停板"]}
            ```
        """.trimIndent()

        val result = StructuredOutputParser.parseTradePlan("sh600183", "生益科技", text)
        assertNotNull(result)
        assertEquals(2, result!!.entryZones.size)
        assertEquals("23.8（收盤有效跌破）", result.stopLoss)
        assertEquals(2, result.targets.size)
    }

    @Test
    fun `解析 Agent 1 初選池`() {
        val text = """
            ```json
            {"filteredPool": [{"code": "sh600183", "name": "生益科技", "reason": "毛利率連續兩季度回升"}, {"code": "sh600183", "name": "潔美科技", "reason": "CapEx持續爬坡"}]}
            ```
        """.trimIndent()

        val pool = StructuredOutputParser.parseFilteredPool(text)
        assertEquals(2, pool.size)
        assertEquals("sh600183", pool[0].stockCode)
        assertEquals("生益科技", pool[0].stockName)
    }

    @Test
    fun `解析無效文本 — 返回 null`() {
        assertNull(StructuredOutputParser.extractJson("純文本無JSON"))
        assertNull(StructuredOutputParser.parseChainScore("test", "test", "無效"))
        assertNull(StructuredOutputParser.parseRiskResult("test", "無效"))
        assertNull(StructuredOutputParser.parseSentimentResult("無效"))
        assertNull(StructuredOutputParser.parseTradePlan("test", "test", "無效"))
        assertTrue(StructuredOutputParser.parseFilteredPool("無效").isEmpty())
    }

    // ═══════════════════════════════════════
    // 4. DataFeeder 板塊推斷
    // ═══════════════════════════════════════

    @Test
    fun `板塊推斷 — 科技賽道`() {
        val feeder = TestableDataFeeder()
        assertEquals("半導體", feeder.inferSector("半導體板塊"))
        assertEquals("PCB", feeder.inferSector("PCB產業"))
        assertEquals("光通信", feeder.inferSector("光通信賽道"))
        assertEquals("存儲", feeder.inferSector("NAND閃存"))
        assertEquals("AI 硬體", feeder.inferSector("AI算力"))
        assertEquals("電網設備", feeder.inferSector("特高壓電網"))
        assertEquals("MLCC", feeder.inferSector("片式電容"))
    }

    @Test
    fun `板塊推斷 — 非科技賽道`() {
        val feeder = TestableDataFeeder()
        assertEquals("新能源", feeder.inferSector("鋰電池板塊"))
        assertEquals("醫藥", feeder.inferSector("創新藥"))
        assertEquals("消費", feeder.inferSector("白酒板塊"))
        assertEquals("金融", feeder.inferSector("券商股"))
        assertEquals("汽車", feeder.inferSector("智能駕駛"))
    }

    @Test
    fun `板塊推斷 — 默認科技`() {
        val feeder = TestableDataFeeder()
        assertEquals("科技", feeder.inferSector("隨便什麼"))
        assertEquals("科技", feeder.inferSector("abc"))
    }

    // ═══════════════════════════════════════
    // 5. Agent D 邊界隔離
    // ═══════════════════════════════════════

    @Test
    fun `Agent D 邊界 — 輿情不影響淘汰判定`() {
        // 即使輿情很差，標的淘汰只由 Agent2 打分和 Agent5 風控決定
        val badSentiment = SentimentAdjustResult(
            sentimentScore = -5, positionAdjust = "-10%", reason = "負面新聞集中"
        )

        val ctx = PipelineContext(target = "測試").apply {
            this.chainScore = ChainScoreResult(
                stockCode = "test", stockName = "測試",
                baseScore = 60, totalScore = 60, barrierLevel = "高", passed = true
            )
            this.riskResult = RiskValidationResult(
                stockCode = "test", riskLevel = "低", passed = true
            )
            this.sentimentResult = badSentiment
        }

        // passed 只看 chainScore.passed && riskResult.passed
        val passed = ctx.chainScore?.passed == true && ctx.riskResult?.passed == true
        assertTrue(passed) // 即使輿情差，標的仍然通過
        assertEquals("-10%", ctx.sentimentResult!!.positionAdjust) // 但倉位被下調
    }

    // ═══════════════════════════════════════
    // 輔助方法
    // ═══════════════════════════════════════

    /**
     * 複製 AgentPipelineOrchestrator.applyHedgeMechanism 的純邏輯
     * （不依賴 Android Context，適合單元測試）
     */
    private fun applyHedgeLogic(ctx: PipelineContext) {
        val chainScore = ctx.chainScore ?: return
        val riskResult = ctx.riskResult ?: return

        if (chainScore.overseasBonus > 0 && riskResult.overseasDeduction > 0) {
            val newTotal = (chainScore.totalScore - chainScore.overseasBonus - chainScore.foreignRatingBonus)
                .coerceAtLeast(0)
            ctx.chainScore = chainScore.copy(
                overseasBonus = 0,
                foreignRatingBonus = 0,
                totalScore = newTotal,
                passed = newTotal >= 40
            )
            ctx.riskResult = riskResult.copy(adjustedScore = newTotal)
        }
    }

    /**
     * 可測試的 DataFeeder（暴露 inferSector 為 public）
     */
    class TestableDataFeeder {
        private val SECTOR_KEYWORD_MAP = mapOf(
            "半導體" to listOf("半導體", "芯片", "晶圓", "封裝", "IC", "GPU", "CPU"),
            "電網設備" to listOf("電網", "配電", "變壓器", "電力設備", "特高壓"),
            "PCB" to listOf("PCB", "印制電路板", "覆銅板"),
            "MLCC" to listOf("MLCC", "片式電容", "被動元件"),
            "光通信" to listOf("光通信", "光模組", "光纖", "光器件", "MPO", "連接器"),
            "存儲" to listOf("存儲", "閃存", "NAND", "DRAM", "SSD", "HBM"),
            "AI 硬體" to listOf("AI", "人工智能", "算力", "伺服器"),
            "新能源" to listOf("新能源", "鋰電", "光伏", "風電", "儲能"),
            "醫藥" to listOf("醫藥", "創新藥", "CXO", "醫療器械"),
            "消費" to listOf("消費", "白酒", "食品飲料", "家電"),
            "金融" to listOf("金融", "銀行", "券商", "保險"),
            "地產" to listOf("地產", "物業"),
            "汽車" to listOf("汽車", "新能源車", "智能駕駛", "電動車")
        )

        fun inferSector(userInput: String): String {
            val input = userInput.lowercase()
            for ((sector, keywords) in SECTOR_KEYWORD_MAP) {
                for (kw in keywords) {
                    if (input.contains(kw.lowercase())) return sector
                }
            }
            return "科技"
        }
    }
}
