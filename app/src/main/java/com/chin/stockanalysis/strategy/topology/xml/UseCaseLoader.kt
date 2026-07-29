package com.chin.stockanalysis.strategy.topology.xml

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.strategy.HoldingPeriod
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.topology.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

/**
 * ## UseCase 加載器
 *
 * 統一入口：從 XML 加載 UseCase → 解析多個 Pipeline → 注入策略 → 執行。
 *
 * 支持多 Pipeline 編排：
 * - `parallel=false` 的 Pipeline 串行執行（等待前一組完成後啟動）
 * - `parallel=true` 的 Pipeline 與前一個 Pipeline 並行執行
 * - 所有 Pipeline 共享同一個 [PipelineContext]
 *
 * ### 使用方式
 * ```kotlin
 * // 1. 初始化（App 啟動時）
 * UseCaseLoader.init(appContext, strategies)
 *
 * // 2. 執行（Fragment 中）
 * val result = UseCaseLoader.run("short_term", "2026-07-10")
 * // result 是 MultiPipelineResult，包含所有 Pipeline 的結果
 * ```
 */
object UseCaseLoader {

    private const val TAG = "UseCaseLoader"

    private lateinit var appContext: Context
    private var initialized = false

    // ════════════════════════════════════════════════════
    // 初始化
    // ════════════════════════════════════════════════════

    /**
     * 初始化。必須在使用前調用。
     */
    fun init(context: Context, strategies: List<Strategy>) {
        appContext = context.applicationContext
        NodeRegistry.init(appContext)
        NodeRegistry.registerStrategies(strategies)
        initialized = true
        Log.i(TAG, "初始化完成: ${NodeRegistry.listModules().size} 個 module, ${strategies.size} 個策略")
    }

    // ════════════════════════════════════════════════════
    // 查詢
    // ════════════════════════════════════════════════════

    /**
     * 列出所有可用的 UseCase。
     */
    fun listUseCases(): List<UseCaseInfo> {
        if (!initialized) return emptyList()
        return PipelineXmlParser.listAssets(appContext, "usecases")
            .filter { it.endsWith("_usecase.xml") }
            .mapNotNull { fileName ->
                val config = PipelineXmlParser.loadUseCaseFromAssets(appContext, "usecases/$fileName") ?: return@mapNotNull null
                UseCaseInfo(
                    id = config.id,
                    name = config.name,
                    description = config.description,
                    stepCount = config.steps.size,
                    fileName = fileName
                )
            }
    }

    /**
     * 列出所有可用的 Pipeline。
     */
    fun listPipelines(): List<PipelineInfo> {
        if (!initialized) return emptyList()
        return PipelineXmlParser.listAssets(appContext, "usecases")
            .filter { it.endsWith("_pipeline.xml") }
            .mapNotNull { fileName ->
                val pipeline = PipelineXmlParser.loadPipelineFromAssets(appContext, "usecases/$fileName") ?: return@mapNotNull null
                PipelineInfo(
                    id = pipeline.id,
                    name = pipeline.name,
                    description = pipeline.description,
                    stageCount = pipeline.stages.size,
                    nodeCount = pipeline.stages.flatMap { it.linkLists }.flatMap { it.allNodes() }.distinctBy { it.nodeId }.size,
                    fileName = fileName
                )
            }
    }

    // ════════════════════════════════════════════════════
    // 執行
    // ════════════════════════════════════════════════════

    /**
     * 執行指定 UseCase。
     *
     * 加載 UseCase 中引用的所有 Pipeline，按 `parallel` 標誌決定串行或並行執行：
     * - `parallel=false` 的 Pipeline 串行執行
     * - `parallel=true` 的 Pipeline 與前一個並行執行
     * - 所有 Pipeline 共享同一個 [PipelineContext]
     *
     * @param useCaseId UseCase ID（如 "short_term"、"mid_term"、"screening"）
     * @param tradeDate 交易日
     * @param onNodeProgress 節點執行進度回調（可選），參數為 (pipelineName, nodeName)，供 UI 實時顯示
     * @return [MultiPipelineResult] 包含所有 Pipeline 的結果
     */
    suspend fun run(
        useCaseId: String,
        tradeDate: String,
        onNodeProgress: ((pipelineName: String, nodeName: String) -> Unit)? = null
    ): MultiPipelineResult {
        if (!initialized) {
            return MultiPipelineResult(
                useCaseId = useCaseId,
                success = false,
                pipelineResults = emptyMap(),
                totalElapsedMs = 0,
                errors = mapOf("init" to "UseCaseLoader 未初始化")
            )
        }

        return withContext(Dispatchers.IO) {
            // 1. 加載 UseCase XML
            val useCaseConfig = PipelineXmlParser.loadUseCaseFromAssets(appContext, "usecases/${useCaseId}_usecase.xml")
                ?: return@withContext MultiPipelineResult(
                    useCaseId = useCaseId, success = false, pipelineResults = emptyMap(),
                    totalElapsedMs = 0,
                    errors = mapOf("usecase" to "UseCase XML 不存在: $useCaseId")
                )

            Log.i(TAG, "加載 UseCase: ${useCaseConfig.name} → ${useCaseConfig.steps.size} 個步驟")

            // 讀取 UseCase 的持倉周期（用於策略注入過濾：只注入匹配周期的策略）
            val useCasePeriod = useCaseConfig.config["holdingPeriod"]

            // 2. 加載所有步驟（Pipeline 引用 + 直接 Node）
            val loadedPipelines = mutableListOf<Pair<String, Pipeline>>()  // (stepName, pipeline)
            val loadedDagPipelines = mutableListOf<Pair<String, DagPipeline>>()  // V2 DAG
            val loadErrors = mutableMapOf<String, String>()

            for (step in useCaseConfig.steps) {
                when (step) {
                    is PipelineXmlParser.StepRef.pipeline -> {
                        val pipelineName = step.name.ifBlank { step.ref.substringAfterLast("/") }

                        // 先嘗試讀取 XML 原文，檢測是否為 V2 DAG 格式
                        val isDag = try {
                            val xml = appContext.assets.open(step.ref).bufferedReader().use { it.readText() }
                            PipelineXmlParser.isDagPipelineXml(xml)
                        } catch (_: Exception) { false }

                        if (isDag) {
                            // V2: DAG Pipeline（高通風格 NodeList + Links）
                            val dagPipeline = PipelineXmlParser.loadDagPipelineFromAssets(appContext, step.ref)
                            if (dagPipeline == null) {
                                loadErrors[pipelineName] = "DAG Pipeline 加載失敗: ${step.ref}"
                                Log.e(TAG, "DAG Pipeline 加載失敗: ${step.ref}")
                            } else {
                                Log.i(TAG, "DAG Pipeline 加載成功: ${dagPipeline.name} — ${dagPipeline.nodes.size} nodes, ${dagPipeline.edges.size} edges")
                                val enriched = injectStrategiesToDag(dagPipeline, useCasePeriod)
                                loadedDagPipelines.add(pipelineName to enriched)
                            }
                        } else {
                            // V1: Stage-based Pipeline
                            val pipeline = PipelineXmlParser.loadPipelineFromAssets(appContext, step.ref)
                            if (pipeline == null) {
                                loadErrors[pipelineName] = "Pipeline XML 不存在: ${step.ref}"
                                Log.e(TAG, "Pipeline 加載失敗: ${step.ref}")
                            } else {
                                Log.i(TAG, "Pipeline 加載成功: ${pipeline.name} — ${pipeline.stages.size} 個 Stage")
                                val enriched = injectStrategies(pipeline, useCasePeriod)
                                loadedPipelines.add(pipelineName to enriched)
                            }
                        }
                    }
                    is PipelineXmlParser.StepRef.node -> {
                        Log.i(TAG, "直接 Node 步驟: ${step.id} (module=${step.module})")
                    }
                }
            }

            if (loadedPipelines.isEmpty() && loadedDagPipelines.isEmpty()) {
                return@withContext MultiPipelineResult(
                    useCaseId = useCaseId, success = false, pipelineResults = emptyMap(),
                    totalElapsedMs = 0,
                    errors = loadErrors.ifEmpty { mapOf("pipeline" to "無可執行的 Pipeline") }
                )
            }

            // 3. 構建共享的 PipelineContext
            val config = parsePipelineConfig(useCaseConfig.config)
            val context = PipelineContext(
                tradeDate = tradeDate,
                androidContext = appContext,
                smartMoneyCache = com.chin.stockanalysis.strategy.data.SmartMoneyCache,
                config = config
            )
            // 掛上 UI 進度回調（DAG 執行時每個節點開始會回傳 pipelineName + nodeName）
            context.onNodeProgress = onNodeProgress

            // 4. 將策略列表存入 context，供 DAG Node 使用
            val allStrategies = NodeRegistry.listModules()
                .filter { it.startsWith("strategy:") }
                .mapNotNull { module ->
                    NodeRegistry.createNode(module, emptyMap(), appContext)
                }
                .mapNotNull { node ->
                    // 從 StrategyNode 中提取原始 Strategy
                    (node as? com.chin.stockanalysis.strategy.topology.nodes.StrategyNode)?.strategy
                }
            if (allStrategies.isNotEmpty()) {
                context.stageOutputs["_strategies"] = allStrategies
            }

            // 5. 按 parallel 標誌分組執行
            val parallelGroups = groupByParallel(useCaseConfig.steps, loadedPipelines)

            val pipelineResults = mutableMapOf<String, PipelineResult>()
            val errors = mutableMapOf<String, String>()
            errors.putAll(loadErrors)
            var finalOutput: Any? = null
            var allSuccess = loadErrors.isEmpty()

            Log.i(TAG, "開始執行: ${useCaseConfig.name} — ${parallelGroups.size} 個執行組")

            val totalElapsed = measureTimeMillis {
                for ((groupIndex, group) in parallelGroups.withIndex()) {
                    if (group.size == 1) {
                        // 串行執行單個 Pipeline
                        val (name, pipeline) = group[0]
                        Log.i(TAG, "[組 $groupIndex] 串行執行: $name")
                        val result = try {
                            pipeline.execute(context)
                        } catch (e: Exception) {
                            PipelineResult(
                                pipelineName = name, success = false,
                                stageResults = emptyMap(), timings = emptyMap(),
                                totalElapsedMs = 0,
                                errors = mapOf(name to "Pipeline 執行異常: ${e.message}")
                            )
                        }
                        pipelineResults[name] = result
                        if (!result.success) allSuccess = false
                        if (result.finalOutput != null) finalOutput = result.finalOutput
                        errors.putAll(result.errors)
                    } else {
                        // 並行執行同一組的多個 Pipeline
                        Log.i(TAG, "[組 $groupIndex] 並行執行: ${group.map { it.first }}")
                        coroutineScope {
                            val deferred = group.map { (name, pipeline) ->
                                async {
                                    name to try {
                                        pipeline.execute(context)
                                    } catch (e: Exception) {
                                        PipelineResult(
                                            pipelineName = name, success = false,
                                            stageResults = emptyMap(), timings = emptyMap(),
                                            totalElapsedMs = 0,
                                            errors = mapOf(name to "Pipeline 執行異常: ${e.message}")
                                        )
                                    }
                                }
                            }
                            // 等待所有並行 Pipeline 完成
                            for (d in deferred) {
                                val (name, result) = d.await()
                                pipelineResults[name] = result
                                if (!result.success) allSuccess = false
                                if (result.finalOutput != null) finalOutput = result.finalOutput
                                errors.putAll(result.errors)
                            }
                        }
                    }
                }
            }

            // 5. 執行 DAG Pipeline（V2 高通風格）
            for ((name, dagPipeline) in loadedDagPipelines) {
                Log.i(TAG, "▶ 執行 DAG Pipeline: $name")
                val dagResult = try {
                    kotlinx.coroutines.withTimeout(180_000L) {
                        dagPipeline.execute(context)
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    Log.e(TAG, "DAG Pipeline 超時 (180s): $name")
                    DagPipelineResult(
                        pipelineName = name, success = false,
                        nodeResults = emptyMap(), totalElapsedMs = 180_000,
                        errors = mapOf(name to "DAG 執行超時 (180s)")
                    )
                } catch (e: Exception) {
                    DagPipelineResult(
                        pipelineName = name, success = false,
                        nodeResults = emptyMap(), totalElapsedMs = 0,
                        errors = mapOf(name to "DAG 執行異常: ${e.message}")
                    )
                }
                // 將 DagPipelineResult 轉為 PipelineResult 存入統一結果
                pipelineResults[name] = PipelineResult(
                    pipelineName = dagResult.pipelineName,
                    success = dagResult.success,
                    stageResults = dagResult.nodeResults.map { (nodeId, nodeResult) ->
                        nodeId to LinkListResult(
                            linkListName = nodeId,
                            success = nodeResult.success,
                            output = nodeResult.output,
                            stepTimings = listOf(nodeId to nodeResult.elapsedMs)
                        )
                    }.toMap(),
                    timings = mapOf(name to dagResult.totalElapsedMs),
                    totalElapsedMs = dagResult.totalElapsedMs,
                    finalOutput = dagResult.finalOutput,
                    errors = dagResult.errors,
                    stockFlowLogs = dagResult.stockFlowLogs
                )
                if (!dagResult.success) allSuccess = false
                if (dagResult.finalOutput != null) finalOutput = dagResult.finalOutput
                errors.putAll(dagResult.errors)
            }

            Log.i(TAG, "執行完成: ${if (allSuccess) "成功" else "失敗"}, 耗時 ${totalElapsed}ms" +
                    (if (errors.isNotEmpty()) ", 錯誤: ${errors.keys}" else ""))

            MultiPipelineResult(
                useCaseId = useCaseId,
                success = allSuccess,
                pipelineResults = pipelineResults,
                totalElapsedMs = totalElapsed,
                finalOutput = finalOutput,
                errors = errors
            )
        }
    }

    /**
     * 按 parallel 標誌將 Pipeline 分組。
     *
     * 分組規則：
     * - parallel=false 的 Pipeline 開始一個新組
     * - parallel=true 的 Pipeline 加入當前組（與前一個並行）
     *
     * 例如：[A(false), B(true), C(false), D(true)] → [[A, B], [C, D]]
     *
     * @return 每個內部 List 是一組可並行執行的 Pipeline
     */
    private fun groupByParallel(
        steps: List<PipelineXmlParser.StepRef>,
        loadedPipelines: List<Pair<String, Pipeline>>
    ): List<List<Pair<String, Pipeline>>> {
        val groups = mutableListOf<MutableList<Pair<String, Pipeline>>>()
        val pipelineMap = loadedPipelines.toMap()

        for (step in steps) {
            when (step) {
                is PipelineXmlParser.StepRef.pipeline -> {
                    val name = step.name.ifBlank { step.ref.substringAfterLast("/") }
                    val pipeline = pipelineMap[name] ?: continue
                    if (!step.parallel || groups.isEmpty()) {
                        groups.add(mutableListOf(name to pipeline))
                    } else {
                        groups.last().add(name to pipeline)
                    }
                }
                is PipelineXmlParser.StepRef.node -> {
                    // 單 Node 步驟：創建一個只有一個 Node 的單步 Pipeline
                    val node = NodeRegistry.createNode(step.module, step.config, appContext)
                    if (node == null) continue
                    val singlePipeline = Pipeline(
                        id = step.id,
                        name = step.id,
                        stages = listOf(Pipeline.Stage(
                            name = step.id,
                            parallel = false,
                            linkLists = listOf(LinkList(
                                name = step.id,
                                links = listOf(
                                    // 創建一個 identity link（Unit → Node）
                                    Link(from = object : com.chin.stockanalysis.strategy.topology.core.PipelineNode<Unit, Any?> {
                                        override val nodeId = "start_${step.id}"
                                        override val nodeName = "start"
                                        override val nodeType = com.chin.stockanalysis.strategy.topology.core.NodeType.DATA_SOURCE
                                        override suspend fun execute(context: PipelineContext, input: Unit): Any? = null
                                    }, to = node, label = "→ ${step.id}")
                                )
                            ))
                        ))
                    )
                    val name = step.id
                    if (!step.parallel || groups.isEmpty()) {
                        groups.add(mutableListOf(name to singlePipeline))
                    } else {
                        groups.last().add(name to singlePipeline)
                    }
                }
            }
        }

        return groups
    }

    /**
     * 將已註冊的策略動態注入到 Pipeline 的 "策略篩選" Stage。
     *
     * 找到名為 "策略篩選" 的 Stage，為每個策略創建一條 LinkList。
     */
    private fun injectStrategies(pipeline: Pipeline, periodStr: String? = null): Pipeline {
        val targetPeriod = periodFromString(periodStr)
        val strategies = NodeRegistry.listModules()
            .filter { it.startsWith("strategy:") }
            .mapNotNull { module ->
                NodeRegistry.createNode(module, emptyMap(), appContext)
            }
            .filter { node ->
                val strategy = (node as? com.chin.stockanalysis.strategy.topology.nodes.StrategyNode)?.strategy
                targetPeriod == null || strategy == null || targetPeriod in strategy.holdingPeriods
            }

        if (strategies.isEmpty()) {
            Log.w(TAG, "無匹配周期[$periodStr]的策略，Pipeline 將跳過策略篩選 Stage")
            return pipeline
        }

        // 需要一個共用的 StockPoolNode（策略篩選 Stage 的第一個節點）
        val stockPoolNode = NodeRegistry.createNode("stock_pool", emptyMap(), appContext)!!

        val strategyLinkLists = strategies.map { node ->
            LinkList(
                name = node.nodeName.removePrefix("策略: "),
                links = listOf(
                    Link(from = stockPoolNode, to = node, label = "stockPool → ${node.nodeId}")
                )
            )
        }

        val newStages = pipeline.stages.map { stage ->
            if (stage.name == "策略篩選") {
                stage.copy(linkLists = strategyLinkLists)
            } else {
                stage
            }
        }

        Log.i(TAG, "注入 ${strategyLinkLists.size} 個策略到 Pipeline")
        return pipeline.copy(stages = newStages)
    }

    /**
     * 將已註冊的策略動態注入到 DAG Pipeline。
     *
     * 在 DAG 圖中找到 `n_merge`（信號合併）節點，在其前面插入策略節點：
     * - n_pool → strategy_1 → n_merge
     * - n_pool → strategy_2 → n_merge
     * - ...（每個策略並行）
     *
     * 這樣策略節點會與 n_pool 同層或下一層，拓撲排序會自動推導正確的並行度。
     *
     * **周期過濾**：只注入 `holdingPeriods` 包含 [periodStr] 對應周期的策略，
     * 避免中線 Pipeline 執行超短線策略（反之亦然）。periodStr 為 null 時注入全部。
     */
    private fun injectStrategiesToDag(dag: DagPipeline, periodStr: String? = null): DagPipeline {
        val targetPeriod = periodFromString(periodStr)
        val strategies = NodeRegistry.listModules()
            .filter { it.startsWith("strategy:") }
            .mapNotNull { module ->
                val node = NodeRegistry.createNode(module, emptyMap(), appContext)
                if (node != null) module to node else null
            }
            .filter { (_, node) ->
                val strategy = (node as? com.chin.stockanalysis.strategy.topology.nodes.StrategyNode)?.strategy
                targetPeriod == null || strategy == null || targetPeriod in strategy.holdingPeriods
            }

        if (strategies.isEmpty()) {
            Log.w(TAG, "無匹配周期[$periodStr]的策略，DAG Pipeline 將跳過策略注入")
            return dag
        }

        // 檢查是否有 n_merge 節點
        val hasMerge = dag.nodes.any { it.nodeId == "n_merge" }
        if (!hasMerge) {
            Log.w(TAG, "DAG 中無 n_merge 節點，策略注入跳過")
            return dag
        }

        // 創建策略 DagNode
        val strategyDagNodes = strategies.mapIndexed { index, (module, node) ->
            DagNode(
                nodeId = "strategy_$index",
                nodeName = node.nodeName,
                node = node
            )
        }

        // 創建邊：n_pool → strategy_i，strategy_i → n_merge
        val newEdges = dag.edges.toMutableList()
        for (strategyNode in strategyDagNodes) {
            newEdges.add(DagEdge("n_pool", 0, strategyNode.nodeId, 0))
            newEdges.add(DagEdge(strategyNode.nodeId, 0, "n_merge", 0))
        }

        // 需要移除 n_pool → n_merge 的直接連接（如果存在），讓策略節點成為中間層
        val filteredEdges = newEdges.filterNot { it.sourceNodeId == "n_pool" && it.targetNodeId == "n_merge" }

        Log.i(TAG, "注入 ${strategyDagNodes.size} 個策略到 DAG Pipeline" +
                (if (targetPeriod != null) "（周期: $periodStr）" else "（未指定周期，注入全部）"))

        return DagPipeline(
            id = dag.id,
            name = dag.name,
            description = dag.description,
            nodes = dag.nodes + strategyDagNodes,
            edges = filteredEdges
        )
    }

    /**
     * UseCase XML 的 holdingPeriod 字串 → [HoldingPeriod] 枚舉。
     * 無法識別時返回 null（注入全部策略，向後兼容）。
     */
    private fun periodFromString(period: String?): HoldingPeriod? = when (period) {
        "ultra_short" -> HoldingPeriod.ULTRA_SHORT
        "short" -> HoldingPeriod.SHORT
        "mid" -> HoldingPeriod.MID
        "long" -> HoldingPeriod.LONG
        else -> null
    }

    private fun parsePipelineConfig(config: Map<String, String>): PipelineConfig {
        return PipelineConfig(
            onlyMainBoard = config["onlyMainBoard"]?.toBooleanStrictOrNull() ?: true,
            holdingPeriod = config["holdingPeriod"] ?: "short",
            orderType = config["orderType"] ?: "Screening",
            maxHoldings = config["maxHoldings"]?.toIntOrNull() ?: 0,
            maxSignalsPerStrategy = config["maxSignalsPerStrategy"]?.toIntOrNull() ?: 15
        )
    }

    // ════════════════════════════════════════════════════
    // 數據類
    // ════════════════════════════════════════════════════

    /**
     * 多 Pipeline 執行結果。
     *
     * @property useCaseId UseCase ID
     * @property success 所有 Pipeline 是否均成功
     * @property pipelineResults 各 Pipeline 的執行結果（pipelineName → result）
     * @property totalElapsedMs 總耗時（毫秒）
     * @property finalOutput 最後一個非空輸出
     * @property errors 錯誤信息（pipelineName/errorKey → message）
     */
    data class MultiPipelineResult(
        val useCaseId: String,
        val success: Boolean,
        val pipelineResults: Map<String, PipelineResult>,
        val totalElapsedMs: Long,
        val finalOutput: Any? = null,
        val errors: Map<String, String> = emptyMap()
    )

    data class UseCaseInfo(
        val id: String,
        val name: String,
        val description: String,
        val stepCount: Int,
        val fileName: String
    )

    data class PipelineInfo(
        val id: String,
        val name: String,
        val description: String,
        val stageCount: Int,
        val nodeCount: Int,
        val fileName: String
    )
}
