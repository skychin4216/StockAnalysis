package com.chin.stockanalysis.strategy.trade

import android.graphics.Color
import android.graphics.BitmapFactory
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.HoldingPeriod
import com.chin.stockanalysis.strategy.topology.xml.DagTradeExecutor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * ## 實倉管理 Tab — 用戶真實持倉 + 大盤分析 Pipeline
 *
 * 擁有獨立的 UseCase (real_holding) 和 DAG Pipeline：
 * - 大盤行情分析（冷/熱/溫和 + 板塊輪動方向）
 * - 結論適用於所有持倉股票的評估
 *
 * ### 週期自動分類規則
 * - 持倉 ≤1 天 → 超短線
 * - 持倉 2-14 天 → 短線
 * - 持倉 15-180 天 → 中線
 * - 持倉 >180 天 → 長線
 */
class RealHoldingQuantFragment : QuantFragmentBase() {

    companion object {
        private const val TAG = "RealHolding"
    }

    /** 截圖選擇器（用于 OCR 導入） */
    private val screenshotPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { processScreenshotOcr(it) }
    }

    override fun getQuantType() = "RealHolding"

    override fun onFittingClick() {
        Toast.makeText(requireContext(), "實倉無擬合功能", Toast.LENGTH_SHORT).show()
    }

    override fun onBacktrackClick() {
        Toast.makeText(requireContext(), "實倉無回溯功能", Toast.LENGTH_SHORT).show()
    }

    override fun onClearClick() {
        Toast.makeText(requireContext(), "實倉數據不可清除", Toast.LENGTH_SHORT).show()
    }

    override fun initEngine() {
        super.initEngine()
    }

    override fun buildUI() {
        addTitleRow("🏦 實倉管理（真實持倉）", textSize = 18f)
        rootLayout.addView(createProgressRow())
        rootLayout.addView(createButtonRow())
        addSeparator()
        rootLayout.addView(createContentScrollArea())
        refreshPositions()
    }

    /** 實倉的建倉按鈕 → 彈出菜單：分析Pipeline / 手動添加 / 截圖導入 */
    override fun onBuildClick() {
        val items = arrayOf(
            "📊 執行實倉分析 Pipeline",
            "✏️ 手動添加持倉",
            "📷 截圖識別導入"
        )
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("實倉建倉")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> runRealHoldingPipeline()
                    1 -> showManualAddDialog()
                    2 -> screenshotPicker.launch("image/*")
                }
            }
            .show()
    }

    /** 執行實倉分析 Pipeline */
    private fun runRealHoldingPipeline() {
        runDagPipeline(
            holdingPeriod = HoldingPeriod.MID,
            useCaseId = "real_holding",
            orderType = "RealHolding",
            importDays = 30,
            titlePrefix = "實倉分析"
        )
    }

    override fun refreshPositions() {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val report = withContext(Dispatchers.Default) {
                buildRealHoldingReport(ctx)
            }
            val contentArea = rootLayout.findViewWithTag<LinearLayout>("content_area")
            contentArea?.post {
                contentArea.removeAllViews()
                contentArea.addView(report)
            }
        }
    }

    private suspend fun buildRealHoldingReport(ctx: android.content.Context): LinearLayout {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 4, 8, 4)
        }

        val db = StockDatabase.getInstance(ctx)
        val orders = withContext(Dispatchers.IO) {
            db.strategyTradeOrderDao().getRecent(500)
        }.filter { it.status == "BUYING" || it.status == "PENDING" || it.status == "HOLDING" }

        // 也讀取真實持倉
        val realPositions = withContext(Dispatchers.IO) {
            db.realPositionDao().getAllActive()
        }

        if (orders.isEmpty() && realPositions.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "暫無持倉記錄\n\n點擊「📈建倉」執行大盤分析 Pipeline\n點擊「📦持倉」添加真實持倉"
                setTextColor(Color.GRAY)
                textSize = 14f
                setPadding(16, 32, 16, 32)
            })
            return container
        }

        val today = LocalDate.now()

        // 真實持倉區
        if (realPositions.isNotEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "🏦 真實持倉 (${realPositions.size} 只)"
                setTextColor(Color.parseColor("#1565C0"))
                textSize = 14f
                setPadding(0, 8, 0, 4)
            })
            for (p in realPositions) {
                container.addView(TextView(ctx).apply {
                    text = buildString {
                        append("▸ ${p.stockName}(${p.stockCode}) ")
                        append("${p.quantity}股 ¥${"%.2f".format(p.avgBuyPrice)}")
                        if (p.periodType.isNotEmpty()) append(" [${p.periodType}]")
                    }
                    setTextColor(Color.parseColor("#333333"))
                    textSize = 12f
                    setPadding(16, 2, 0, 2)
                })
            }
        }

        // 策略持倉區
        if (orders.isNotEmpty()) {
            // 按週期分組
            val grouped = orders.groupBy { order ->
                val buyDate = try { LocalDate.parse(order.tradeDate) } catch (_: Exception) { today }
                val daysHeld = ChronoUnit.DAYS.between(buyDate, today).toInt().coerceAtLeast(0)
                classifyPeriod(daysHeld)
            }

            container.addView(TextView(ctx).apply {
                text = "📊 策略持倉 (${orders.size} 筆)"
                setTextColor(Color.parseColor("#E65100"))
                textSize = 14f
                setPadding(0, 12, 0, 4)
            })

            val periodOrder = listOf(HoldingPeriod.ULTRA_SHORT, HoldingPeriod.SHORT, HoldingPeriod.MID, HoldingPeriod.LONG)
            for (period in periodOrder) {
                val periodOrders = grouped[period] ?: continue
                val label = period.label
                val icon = period.icon

                container.addView(TextView(ctx).apply {
                    text = "$icon $label（${periodOrders.size} 筆）"
                    setTextColor(Color.parseColor("#E65100"))
                    textSize = 13f
                    setPadding(8, 8, 0, 2)
                })

                for (order in periodOrders) {
                    val buyDate = try { LocalDate.parse(order.tradeDate) } catch (_: Exception) { today }
                    val daysHeld = ChronoUnit.DAYS.between(buyDate, today).toInt().coerceAtLeast(0)
                    val pnl = order.profitPct

                    container.addView(TextView(ctx).apply {
                        text = buildString {
                            append("${order.stockName}(${order.stockCode}) ")
                            append("持倉${daysHeld}天 ")
                            append("買入¥${"%.2f".format(order.buyPrice)} ")
                            append("盈虧${"%.2f".format(pnl)}%")
                        }
                        setTextColor(if (pnl >= 0) Color.parseColor("#C62828") else Color.parseColor("#2E7D32"))
                        textSize = 12f
                        setPadding(16, 2, 0, 2)
                    })
                }
            }
        }

        return container
    }

    private fun classifyPeriod(daysHeld: Int): HoldingPeriod {
        return when {
            daysHeld <= 1 -> HoldingPeriod.ULTRA_SHORT
            daysHeld <= 14 -> HoldingPeriod.SHORT
            daysHeld <= 180 -> HoldingPeriod.MID
            else -> HoldingPeriod.LONG
        }
    }

    override fun getDefaultUseCaseId(): String = "real_holding"

    // ═══════════════════════════════════════
    // 手動添加持倉
    // ═══════════════════════════════════════

    /** 顯示手動添加持倉對話框 */
    private fun showManualAddDialog() {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }
        scroll.addView(form)

        val codeInput = EditText(ctx).apply {
            hint = "股票代碼（如 sh600519）"; inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(8, 8, 8, 8)
        }
        val nameInput = EditText(ctx).apply {
            hint = "股票名稱（如 貴州茅台）"; inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(8, 8, 8, 8)
        }
        val qtyInput = EditText(ctx).apply {
            hint = "持有數量（股）"; inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(8, 8, 8, 8)
        }
        val priceInput = EditText(ctx).apply {
            hint = "買入均價（元）"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(8, 8, 8, 8)
        }
        val dateInput = EditText(ctx).apply {
            hint = "買入日期（yyyy-MM-dd）"; inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(LocalDate.now().toString())
            setPadding(8, 8, 8, 8)
        }
        val periodSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                listOf("未分類", "超短線", "短線", "中線", "長線"))
        }

        form.addView(TextView(ctx).apply { text = "股票代碼"; textSize = 12f; setPadding(0, 4, 0, 2) })
        form.addView(codeInput)
        form.addView(TextView(ctx).apply { text = "股票名稱"; textSize = 12f; setPadding(0, 4, 0, 2) })
        form.addView(nameInput)
        form.addView(TextView(ctx).apply { text = "持有數量"; textSize = 12f; setPadding(0, 4, 0, 2) })
        form.addView(qtyInput)
        form.addView(TextView(ctx).apply { text = "買入均價"; textSize = 12f; setPadding(0, 4, 0, 2) })
        form.addView(priceInput)
        form.addView(TextView(ctx).apply { text = "買入日期"; textSize = 12f; setPadding(0, 4, 0, 2) })
        form.addView(dateInput)
        form.addView(TextView(ctx).apply { text = "持倉週期"; textSize = 12f; setPadding(0, 8, 0, 2) })
        form.addView(periodSpinner)

        android.app.AlertDialog.Builder(ctx)
            .setTitle("✏️ 手動添加持倉")
            .setView(scroll)
            .setPositiveButton("添加") { _, _ ->
                val code = codeInput.text.toString().trim()
                val name = nameInput.text.toString().trim()
                val qty = qtyInput.text.toString().toIntOrNull() ?: 0
                val price = priceInput.text.toString().toDoubleOrNull() ?: 0.0
                val date = dateInput.text.toString().trim()
                val periodIdx = periodSpinner.selectedItemPosition
                val period = when (periodIdx) {
                    1 -> "UltraShortQuant"; 2 -> "ShortTermQuant"
                    3 -> "MidTermQuant"; 4 -> "LongTermQuant"; else -> ""
                }
                if (code.isEmpty() || name.isEmpty() || qty <= 0 || price <= 0.0) {
                    Toast.makeText(ctx, "請填寫完整信息", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                saveRealPosition(code, name, qty, price, date, period)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 保存真實持倉到數據庫 */
    private fun saveRealPosition(code: String, name: String, qty: Int, price: Double, date: String, period: String) {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(ctx)
                val entity = RealPositionEntity(
                    stockCode = code, stockName = name, quantity = qty,
                    avgBuyPrice = price, buyDate = date, periodType = period
                )
                db.realPositionDao().insert(entity)
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "✅ 已添加 $name($code)", Toast.LENGTH_SHORT).show()
                    refreshPositions()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "添加失敗: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ═══════════════════════════════════════
    // 截圖 OCR 識別
    // ═══════════════════════════════════════

    /** 處理截圖 OCR：識別文字 → AI 解析持倉 → 確認添加 */
    private fun processScreenshotOcr(uri: android.net.Uri) {
        val ctx = requireContext()
        statusTv.text = "🔄 正在識別截圖..."

        try {
            val inputStream = ctx.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (bitmap == null) {
                statusTv.text = "❌ 無法讀取圖片"
                return
            }

            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (!isAdded) return@addOnSuccessListener
                    val rawText = visionText.text
                    android.util.Log.i(TAG, "OCR 原文:\n$rawText")

                    // 使用 AI 解析 OCR 文字
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        statusTv.text = "🤖 AI 正在解析持倉信息..."
                        val parsed = parseHoldingWithAi(rawText)

                        withContext(Dispatchers.Main) {
                            if (!isAdded) return@withContext
                            if (parsed.isEmpty()) {
                                // AI 解析失敗，嘗試正則備選
                                val fallbackParsed = parseHoldingFromOcr(rawText)
                                if (fallbackParsed.isEmpty()) {
                                    statusTv.text = "⚠️ 未識別到持倉信息，請確保截圖包含持倉數據"
                                    showOcrRawText(rawText)
                                } else {
                                    showOcrConfirmDialog(fallbackParsed)
                                }
                            } else {
                                showOcrConfirmDialog(parsed)
                            }
                        }
                    }
                }
                .addOnFailureListener { e ->
                    if (!isAdded) return@addOnFailureListener
                    statusTv.text = "❌ OCR 識別失敗: ${e.message}"
                }
        } catch (e: Exception) {
            if (isAdded) statusTv.text = "❌ 圖片處理失敗: ${e.message}"
        }
    }

    /**
     * 使用 AI 從 OCR 文字中解析持倉信息。
     * AI 會理解表格結構，正確關聯股票名稱、代碼、數量、價格等。
     */
    private suspend fun parseHoldingWithAi(ocrText: String): List<RealPositionEntity> {
        val slot = com.chin.stockanalysis.ai.AiProviderPool.acquire(
            requireContext(),
            callerTag = "RealHoldingOCR",
            timeoutMs = 60_000L
        ) ?: return emptyList()

        try {
            val prompt = buildString {
                appendLine("你是一個專業的股票持倉信息提取助手。")
                appendLine("以下是從券商APP截圖中OCR識別出的文字，格式可能比較混亂。")
                appendLine("請從中提取所有持倉股票信息，包括：股票代碼(6位)、股票名稱、數量、價格。")
                appendLine()
                appendLine("OCR文字內容：")
                appendLine("---")
                appendLine(ocrText)
                appendLine("---")
                appendLine()
                appendLine("請以JSON格式返回，格式如下：")
                appendLine("[")
                appendLine("  {\"code\": \"601168\", \"name\": \"西部礦業\", \"quantity\": 1000, \"price\": 43.07},")
                appendLine("  ...")
                appendLine("]")
                appendLine()
                appendLine("注意：")
                appendLine("1. 股票代碼是6位數字（如601168, 000037）")
                appendLine("2. 數量通常是100的倍數")
                appendLine("3. 價格帶小數點")
                appendLine("4. 如果無法確定某個字段，請根據上下文推斷")
                appendLine("5. 只返回JSON，不要其他說明文字")
            }

            val response = withTimeoutOrNull(60_000L) {
                kotlinx.coroutines.suspendCancellableCoroutine<String> { cont ->
                    slot.provider.sendMessageStream(
                        messages = emptyList(),
                        systemPrompt = prompt,
                        onSuccess = {},
                        onComplete = { full -> cont.resumeWith(Result.success(full)) },
                        onError = { err -> cont.resumeWith(Result.failure(Exception(err))) }
                    )
                }
            }

            if (response == null) {
                android.util.Log.w(TAG, "AI 解析超時")
                return emptyList()
            }

            android.util.Log.i(TAG, "AI 解析結果: $response")

            // 解析 JSON 響應
            return parseAiResponse(response)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "AI 解析失敗: ${e.message}", e)
            return emptyList()
        } finally {
            com.chin.stockanalysis.ai.AiProviderPool.releaseNonBlocking(slot)
        }
    }

    /**
     * 解析 AI 返回的 JSON 響應
     */
    private fun parseAiResponse(response: String): List<RealPositionEntity> {
        val results = mutableListOf<RealPositionEntity>()
        try {
            // 提取 JSON 部分 (可能包含在 markdown code block 中)
            val jsonMatch = Regex("""\[[\s\S]*\]""").find(response)
            val jsonStr = jsonMatch?.value ?: response

            val jsonArray = org.json.JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val rawCode = obj.optString("code", "")
                val name = obj.optString("name", "未知")
                val quantity = obj.optInt("quantity", 0)
                val price = obj.optDouble("price", 0.0)

                // 提取6位數字代碼
                val codeMatch = Regex("""(\d{6})""").find(rawCode)
                val code = codeMatch?.groupValues?.get(1) ?: continue
                if (code.length != 6) continue

                // 添加交易所前綴
                val fullCode = normalizeStockCode(code)

                if (quantity > 0 && price > 0) {
                    results.add(RealPositionEntity(
                        stockCode = fullCode,
                        stockName = name,
                        quantity = quantity,
                        avgBuyPrice = price,
                        buyDate = LocalDate.now().toString()
                    ))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "解析 AI 響應失敗: ${e.message}", e)
        }
        return results
    }

    /**
     * 為股票代碼添加交易所前綴
     */
    private fun normalizeStockCode(code: String): String {
        return when {
            code.startsWith("6") -> "sh$code"
            code.startsWith("0") || code.startsWith("3") -> "sz$code"
            code.startsWith("4") || code.startsWith("8") -> "bj$code"
            else -> "sh$code"
        }
    }

    /**
     * 從 OCR 文字中解析持倉信息。
     * 支持常見券商截圖格式，寬鬆匹配：
     * - 股票代碼（6位數字，可帶 sh/sz/SH/SZ 前綴）
     * - 股票名稱（中文字符，可在代碼前或後）
     * - 數量（整數，通常 100 的倍數）
     * - 價格（帶小數的數字）
     */
    private fun parseHoldingFromOcr(text: String): List<RealPositionEntity> {
        val results = mutableListOf<RealPositionEntity>()
        val lines = text.lines()

        // 寬鬆匹配：6位數字股票代碼（可帶 sh/sz 前綴，大小寫不限）
        val codeRegex = Regex("""(?i)(?:sh|sz)?(\d{6})""")
        val numRegex = Regex("""\d+\.?\d*""")
        // 中文名稱匹配
        val nameRegex = Regex("""[\u4e00-\u9fa5]{2,6}""")

        for (line in lines) {
            val codeMatch = codeRegex.find(line) ?: continue
            val code = codeMatch.groupValues[1]
            if (code.length != 6) continue

            // 基本過濾：000000 或全相同數字通常不是真實代碼
            if (code.all { it == code[0] }) continue

            // 提取所有數字
            val numbers = numRegex.findAll(line).map { it.value }.toList()
            if (numbers.size < 2) continue  // 至少需要數量和價格

            // 嘗試提取股票名稱（代碼前或後的中文）
            val beforeCode = line.substring(0, codeMatch.range.first)
            val afterCode = line.substring(codeMatch.range.last + 1)
            val nameBefore = nameRegex.find(beforeCode)?.value
            val nameAfter = nameRegex.find(afterCode)?.value
            val name = nameBefore ?: nameAfter ?: "未知"

            // 過濾掉明顯不是數量/價格的數字（如股票代碼本身）
            val candidateNums = numbers.filter { it != code && it != codeMatch.groupValues[0] }
            if (candidateNums.size < 2) continue

            // 嘗試識別數量和價格：
            // 數量通常是整數且 >= 100（A股最小交易單位）
            // 價格通常帶小數且 > 1
            val qty = candidateNums.firstOrNull {
                val d = it.toDouble()
                d >= 100 && !it.contains(".")
            }?.toIntOrNull()
                ?: candidateNums.firstOrNull { it.toDouble() >= 100 }?.toIntOrNull()
                ?: continue

            val price = candidateNums.firstOrNull {
                it.contains(".") && it.toDouble() > 1.0
            }?.toDoubleOrNull()
                ?: candidateNums.firstOrNull { it.toDouble() > 1.0 }?.toDoubleOrNull()
                ?: continue

            if (qty > 0 && price > 0) {
                // 添加交易所前綴
                val fullCode = normalizeStockCode(code)
                // 避免重複添加同一只股票
                if (results.none { it.stockCode == fullCode }) {
                    results.add(RealPositionEntity(
                        stockCode = fullCode,
                        stockName = name,
                        quantity = qty,
                        avgBuyPrice = price,
                        buyDate = LocalDate.now().toString()
                    ))
                }
            }
        }
        return results
    }

    /** 顯示 OCR 識別結果確認對話框 */
    private fun showOcrConfirmDialog(positions: List<RealPositionEntity>) {
        if (!isAdded) return
        val ctx = requireContext()
        val msg = buildString {
            appendLine("識別到 ${positions.size} 只持倉：\n")
            for (p in positions) {
                appendLine("  ${p.stockName}(${p.stockCode})")
                appendLine("    ${p.quantity}股 ¥${"%.2f".format(p.avgBuyPrice)}")
            }
            appendLine("\n確認添加？")
        }

        android.app.AlertDialog.Builder(ctx)
            .setTitle("📷 截圖識別結果")
            .setMessage(msg)
            .setPositiveButton("確認添加") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = StockDatabase.getInstance(ctx)
                        db.realPositionDao().insertAll(positions)
                        withContext(Dispatchers.Main) {
                            if (!isAdded) return@withContext
                            statusTv.text = "✅ 已添加 ${positions.size} 只持倉"
                            Toast.makeText(ctx, "✅ 已添加 ${positions.size} 只持倉", Toast.LENGTH_SHORT).show()
                            refreshPositions()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            if (!isAdded) return@withContext
                            Toast.makeText(ctx, "添加失敗: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("查看原文") { _, _ ->
                // 可選：顯示原始 OCR 文字供用戶核對
            }
            .show()
    }

    /** 顯示 OCR 原始文字（用於調試或識別失敗時） */
    private fun showOcrRawText(text: String) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("OCR 識別原文")
            .setMessage(text.take(2000))
            .setPositiveButton("確定", null)
            .show()
    }
}
