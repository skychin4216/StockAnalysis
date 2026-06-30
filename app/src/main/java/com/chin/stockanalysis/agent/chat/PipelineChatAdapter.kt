package com.chin.stockanalysis.agent.chat

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.agent.pipeline.AgentPipelineOrchestrator
import com.chin.stockanalysis.agent.pipeline.PipelineResult
import com.chin.stockanalysis.agent.pipeline.PipelineResultFormatter
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pipeline 對話適配器
 *
 * 封裝 AgentPipelineOrchestrator 的調用，提供：
 * 1. 進度回調（每步開始/完成時通知對話 UI 更新）
 * 2. 超時控制（總超時 90s）
 * 3. 結果格式化（PipelineResult → 自然語言）
 */
class PipelineChatAdapter(
    private val context: Context
) {
    companion object {
        private const val TAG = "PipelineChatAdapter"
        /** Pipeline 總超時（7 步，每步 30-60s，給 5 分鐘） */
        private const val TOTAL_TIMEOUT_MS = 300_000L
    }

    /**
     * 執行 Pipeline 分析並回傳進度
     *
     * @param target 股票名稱或代碼（如 "兆易創新"）
     * @param onProgress 進度回調（每步開始/完成時觸發，用於更新對話消息）
     * @return PipelineResult 或 null（超時/取消）
     */
    suspend fun analyze(
        target: String,
        onProgress: ((String) -> Unit)? = null
    ): PipelineResult? {
        onProgress?.invoke("🤖 正在啟動專家模式流水線（預計 30-60 秒）...")

        return withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            val orchestrator = AgentPipelineOrchestrator(context)

            // 設置進度回調
            orchestrator.onModeSelected = { mode, reason ->
                Log.d(TAG, "模式選定: ${mode.label} — $reason")
                onProgress?.invoke("🧠 ${mode.label}模式（${reason}）\n")
            }

            var totalSteps = 6

            orchestrator.onStepStart = { index, step ->
                if (!step.name.contains("Agent D")) {
                    totalSteps = (index + 2).coerceAtLeast(totalSteps)
                }
                onProgress?.invoke(
                    PipelineResultFormatter.formatStepProgress(
                        stepName = step.name,
                        current = index + 1,
                        total = totalSteps,
                        status = "running"
                    ) + "\n"
                )
            }

            orchestrator.onStepComplete = { index, step, _ ->
                onProgress?.invoke(
                    PipelineResultFormatter.formatStepProgress(
                        stepName = step.name,
                        current = index + 1,
                        total = totalSteps,
                        status = "done"
                    ) + "\n"
                )
            }

            orchestrator.onError = { failedIdx, err ->
                onProgress?.invoke(
                    PipelineResultFormatter.formatStepProgress(
                        stepName = "第 ${failedIdx + 1} 步",
                        current = failedIdx + 1,
                        total = totalSteps,
                        status = "error",
                        errorMsg = err
                    ) + "\n"
                )
            }

            // 執行 Pipeline
            orchestrator.execute(target)
        }
    }

    /**
     * 格式化 Pipeline 結果為對話文本
     */
    fun formatResult(result: PipelineResult): String {
        return PipelineResultFormatter.format(result)
    }
}
