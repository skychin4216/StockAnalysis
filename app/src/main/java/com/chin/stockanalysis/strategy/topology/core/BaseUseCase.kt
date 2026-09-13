package com.chin.stockanalysis.strategy.topology.core

/**
 * ## UseCase 抽象基类
 *
 * UseCase = 完整业务流程，可包含多个 Pipeline 和多个 Node。
 *
 * 一个 UseCase 对应一个完整场景（如「短线选股」「中线交易」），
 * 由多个 Pipeline 步骤组成，每个步骤可串行或并行执行。
 *
 * ### 层级
 * ```
 * UseCase (1 个完整场景)
 *   ├─ Pipeline A (节点 1 → 节点 2 → 节点 3)
 *   ├─ Pipeline B (节点 4 → 节点 5)     ← 可选，并行或串行
 *   └─ Pipeline C (节点 6 → 节点 7)
 * ```
 *
 * ### 子类实现
 * ```kotlin
 * class ShortTermUseCase : BaseUseCase("short_term", "短线交易") {
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
     * 一个执行步骤，引用一个 Pipeline 节点。
     *
     * @property name 步骤名称（用于日志和结果索引）
     * @property pipeline 要执行的 Pipeline（本身也是 PipelineNode）
     * @property parallel 是否与同一步骤组并行执行
     */
    data class Step(
        val name: String,
        val pipeline: PipelineNode<*, *>,
        val parallel: Boolean = false
    )

    /** 配置参数（对应 XML <config><param>） */
    val config: MutableMap<String, String> = mutableMapOf()

    /** 构建执行步骤列表。子类实现以定义包含哪些 Pipeline。 */
    protected abstract fun buildSteps(): List<Step>

    /** 前置校验。返回 false 可中止执行。 */
    protected open suspend fun validate(context: PipelineContext): Boolean = true

    /** 后处理。在所有步骤完成后调用，用于保存报告、推送通知等。 */
    protected open suspend fun postProcess(
        context: PipelineContext,
        results: Map<String, Any?>
    ) {}

    /**
     * 执行完整 UseCase 流程。
     *
     * @return 步骤名 → 执行结果 的映射
     */
    open suspend fun run(context: PipelineContext): Map<String, Any?> {
        if (!validate(context)) {
            context.log(useCaseId, "UseCase [$useCaseName] 校验未通过，中止")
            return emptyMap()
        }

        val steps = buildSteps()
        val results = mutableMapOf<String, Any?>()

        context.log(useCaseId, "UseCase [$useCaseName] 开始 (${steps.size} 个步骤)")

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
