package com.chin.stockanalysis.agent.pipeline

/**
 * Pipeline 結果格式化器
 *
 * 將 PipelineResult 轉換為對話友好型自然語言報告。
 */
object PipelineResultFormatter {

    /**
     * 格式化 Pipeline 最終結果為對話文本
     */
    fun format(result: PipelineResult): String = buildString {
        appendLine("📊 ${result.target} 專家分析報告")
        appendLine("分析模式：${result.analysisMode}（${result.stepsCompleted}/${result.totalSteps} 步完成）")
        appendLine()

        if (result.errorMessage != null) {
            appendLine("⚠️ 部分步驟異常：${result.errorMessage}")
            appendLine()
        }

        if (result.stocks.isEmpty()) {
            appendLine("未找到匹配的股票標的，請嘗試提供更具體的股票名稱或代碼。")
            return@buildString
        }

        result.stocks.forEach { stock ->
            appendLine("【${stock.stockName} ${stock.stockCode}】")
            appendLine(if (stock.passed) "✅ 通過流水線" else "❌ 未通過流水線")
            appendLine()

            // 產業鏈打分
            stock.chainScore?.let { score ->
                appendLine("🎯 產業鏈打分")
                appendLine("  總分：${score.totalScore}/100（壁壘：${score.barrierLevel}）")
                if (score.materialScore > 0) appendLine("  認證週期：${score.materialScore}")
                if (score.barrierScore > 0) appendLine("  產能壁壘：${score.barrierScore}")
                if (score.coverageScore > 0) appendLine("  下游覆蓋：${score.coverageScore}")
                if (score.irreplaceScore > 0) appendLine("  不可替代性：${score.irreplaceScore}")
                if (score.resonanceBonus > 0) appendLine("  共振加分：${score.resonanceBonus}")
                if (!score.passed) {
                    appendLine("  ⚠️ 打分 < 40，未通過產業鏈篩選")
                }
            }

            // 風控終審
            stock.riskResult?.let { risk ->
                appendLine()
                appendLine("🛡 風控終審")
                appendLine("  風險等級：${risk.riskLevel}")
                appendLine("  調整後分數：${risk.adjustedScore}")
                if (risk.deductions.isNotEmpty()) {
                    appendLine("  扣分項：")
                    risk.deductions.forEach { d ->
                        appendLine("    - ${d.item}：${d.description}（-${d.score}）")
                    }
                }
                if (risk.overseasDeduction > 0) {
                    appendLine("  海外替代風險扣分：${risk.overseasDeduction}")
                }
            }

            // 輿情微調
            stock.sentimentResult?.let { sentiment ->
                appendLine()
                appendLine("📢 輿情微調")
                appendLine("  輿情得分：${sentiment.sentimentScore}")
                appendLine("  倉位調整：${sentiment.positionAdjust}")
                appendLine("  理由：${sentiment.reason}")
            }

            // 交易方案
            stock.tradePlan?.let { plan ->
                appendLine()
                appendLine("📈 交易方案")
                appendLine("  低吸區間：${plan.entryZones.joinToString(" / ")}")
                appendLine("  止損位：${plan.stopLoss}")
                appendLine("  止盈目標：${plan.targets.joinToString(" / ")}")
                appendLine("  最大倉位：${plan.maxPosition}")
                appendLine("  分倉比例：${plan.splitRatio}")
                if (plan.tradeRules.isNotEmpty()) {
                    appendLine("  交易紀律：")
                    plan.tradeRules.forEach { appendLine("    - $it") }
                }
            }

            // 最終建議
            appendLine()
            appendLine("💡 最終建議：${stock.finalPosition}")
            appendLine()
            appendLine("—".repeat(30))
        }
    }

    /**
     * 格式化某步的進度消息
     *
     * @param stepName 步驟名稱（如 "基本面拐點價值選股"）
     * @param current  當前步驟序號（從 0 開始）
     * @param total    總步驟數
     * @param status   "running" / "done" / "error"
     * @param errorMsg 錯誤信息（僅 status=error 時）
     */
    fun formatStepProgress(
        stepName: String,
        current: Int,
        total: Int,
        status: String,
        errorMsg: String? = null
    ): String {
        val icon = when (status) {
            "done" -> "✅"
            "error" -> "❌"
            else -> "⏳"
        }
        val suffix = when (status) {
            "done" -> "完成"
            "error" -> "失敗: $errorMsg"
            else -> "..."
        }
        return "[$current/$total] $icon $stepName $suffix"
    }
}
