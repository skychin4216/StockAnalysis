package com.chin.stockanalysis.strategy.topology.core

/**
 * ## UseCase 抽象基類
 *
 * UseCase = 完整業務流程，可包含多個 Pipeline 和多個 Node。
 *
 * 一個 UseCase 對應一個完整場景（如「短線選股」「中線交易」），
 * 由多個 Pipeline 步驟組成，每個步驟可串行或並行執行。
 *
 * ### 層級
 * ```
 * UseCase (1 個完整場景)
 *   ├─ Pipeline A (節點 1 → 節點 2 → 節點 3)
 *   ├─ Pipeline B (節點 4 → 節點 5)     ← 可選，並行或串行
 *   └─ Pipeline C (節點 6 → 節點 7)
 * ```
 *
 * ### 子類實現
 * ```kotlin
 * class ShortTermUseCase : BaseUseCase("short_term", "短線交易") {
 *     override fun buildSteps() = listOf(
 *           Step("main", dagPipeline)
 *       )
 *     override suspend fun postProcess(context, results) { ... }
 * }
 * ```
 */
abstract class BaseUseCase(
    val useCaseId: String,
    val useCaseName: String
) {

    /**
     * 一個執行步驟，引用一個 Pipeline 節點。
     *
     * @property name 步驟名稱（用於日誌和結果索引）
     * @property pipeline 要執行的 Pipeline（本身也是 PipelineNode）
     * @property parallel 是否與同一步驟組並行執行
     */
    data class Step(
        val name: String,
        val pipeline: PipelineNode<*, *>,
        val parallel: Boolean = false
    )

    /** 配置參數（對應 XML <config><param>） */
    val config: MutableMap<String, String> = mutableMapOf()

    /** 構建執行步驟列表。子類實現以定義包含哪些 Pipeline。 */
    protected abstract fun buildSteps(): List<Step>

    /** 前置校驗。返回 false 可中止執行。 */
    protected open suspend fun validate(context: PipelineContext): Boolean = true

    /** 後處理。在所有步驟完成後調用，用於保存報告、推送通知等。 */
    protected open suspend fun postProcess(
        context: PipelineContext,
        results: Map<String, Any?>
    ) {}

    /**
     * 執行完整 UseCase 流程。
     *
     * @return 步驟名 → 執行結果 的映射
     */
    open suspend fun run(context: PipelineContext): Map<String, Any?> {
        if (!validate(context)) {
            context.log(useCaseId, "UseCase [$useCaseName] 校驗未通過，中止")
            return emptyMap()
        }

        val steps = buildSteps()
        val results = mutableMapOf<String, Any?>()

        context.log(useCaseId, "UseCase [$useCaseName] 開始 (${steps.size} 個步驟)")

        for (step in steps) {
            @Suppress("UNCHECKED_CAST")
            val pipeline = step.pipeline as PipelineNode<Any, Any>
            val result = pipeline.execute(context, Unit)
            results[step.name] = result
        }

        postProcess(context, results)

        context.log(useCaseId, "UseCase [$useCaseName] 完成")
        return results
    }

    // ── 配置便捷方法 ──

    fun getString(key: String): String? = config[key]

    fun getInt(key: String): Int? = config[key]?.toIntOrNull()

    fun getDouble(key: String): Double? = config[key]?.toDoubleOrNull()

    fun getBool(key: String): Boolean = config[key]?.toBooleanStrictOrNull() ?: false
}
