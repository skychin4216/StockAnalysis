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
 * ## UseCase 加载器
 *
 * 统一入口：从 XML 加载 UseCase → 解析多个 Pipeline → 注入策略 → 执行。
 *
 * 支持多 Pipeline 编排：
 * - `parallel=false` 的 Pipeline 串行执行（等待前一组完成后启动）
 * - `parallel=true` 的 Pipeline 与前一个 Pipeline 并行执行
 * - 所有 Pipeline 共享同一个 [PipelineContext]
 *
 * ### 使用方式
 * ```kotlin
 * // 1. 初始化（App 启动时）
 * UseCaseLoader.init(appContext, strategies)
 *
 * // 2. 执行（Fragment 中）
 * val result = UseCaseLoader.run("short_term", "2026-07-10")
 * // result 是 MultiPipelineResult，包含所有 Pipeline 的结果
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
     * 初始化。必须在使用前调用。
     */
    fun init(context: Context, strategies: List<Strategy>) {
        appContext = context.applicationContext
        NodeRegistry.init(appContext)
        NodeRegistry.registerStrategies(strategies)
        initialized = true
        Log.i(TAG, "初始化完成: ${NodeRegistry.listModules().size} 个 module, ${strategies.size} 个策略")
    }

    // ════════════════════════════════════════════════════
    // 查询
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
    // 执行
    // ════════════════════════════════════════════════════

    /**
     * 执行指定 UseCase。
     *
     * 加载 UseCase 中引用的所有 Pipeline，按 `parallel` 标志决定串行或并行执行：
     * - `parallel=false` 的 Pipeline 串行执行
     * - `parallel=true` 的 Pipeline 与前一个并行执行
     * - 所有 Pipeline 共享同一个 [PipelineContext]
     *
     * @param useCaseId UseCase ID（如 "short_term"、"mid_term"、"screening"）
     * @param tradeDate 交易日
     * @param onNodeProgress 节点执行进度回调（可选），参数为 (pipelineName, nodeName)，供 UI 实时显示
     * @return [MultiPipelineResult] 包含所有 Pipeline 的结果
     */
    suspend fun run(
        useCaseId: String,
        tradeDate: String,
        onNodeProgress: ((pipelineName: String, nodeName: String) -> Unit)? = null,
        onNodeDone: ((pipelineName: String, nodeName: String, output: Any?) -> Unit)? = null,
        configOverrides: Map<String, String> = emptyMap(),
        seedStageOutputs: Map<String, Any?> = emptyMap()
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
            // 1. 加载 UseCase XML
            val useCaseConfig = PipelineXmlParser.loadUseCaseFromAssets(appContext, "usecases/${useCaseId}_usecase.xml")
                ?: return@withContext MultiPipelineResult(
                    useCaseId = useCaseId, success = false, pipelineResults = emptyMap(),
                    totalElapsedMs = 0,
                    errors = mapOf("usecase" to "UseCase XML 不存在: $useCaseId")
                )

            Log.i(TAG, "加载 UseCase: ${useCaseConfig.name} → ${useCaseConfig.steps.size} 个步骤")

            // 读取 UseCase 的持仓周期（用于策略注入过滤：只注入匹配周期的策略）
            val useCasePeriod = useCaseConfig.config["holdingPeriod"]

            // 2. 加载所有步骤（Pipeline 引用 + 直接 Node）
            val loadedPipelines = mutableListOf<Pair<String, Pipeline>>()  // (stepName, pipeline)
            val loadedDagPipelines = mutableListOf<Pair<String, DagPipeline>>()  // V2 DAG
            val loadErrors = mutableMapOf<String, String>()

            for (step in useCaseConfig.steps) {
                when (step) {
                    is PipelineXmlParser.StepRef.pipeline -> {
                        val pipelineName = step.name.ifBlank { step.ref.substringAfterLast("/") }

                        // 先尝试读取 XML 原文，检测是否为 V2 DAG 格式
                        val isDag = try {
                            val xml = appContext.assets.open(step.ref).bufferedReader().use { it.readText() }
                            PipelineXmlParser.isDagPipelineXml(xml)
                        } catch (_: Exception) { false }

                        if (isDag) {
                            // V2: DAG Pipeline（高通风格 NodeList + Links）
                            val dagPipeline = PipelineXmlParser.loadDagPipelineFromAssets(appContext, step.ref)
                            if (dagPipeline == null) {
                                loadErrors[pipelineName] = "DAG Pipeline 加载失败: ${step.ref}"
                                Log.e(TAG, "DAG Pipeline 加载失败: ${step.ref}")
                            } else {
                                Log.i(TAG, "DAG Pipeline 加载成功: ${dagPipeline.name} — ${dagPipeline.nodes.size} nodes, ${dagPipeline.edges.size} edges")
                                val enriched = injectStrategiesToDag(dagPipeline, useCasePeriod)
                                loadedDagPipelines.add(pipelineName to enriched)
                            }
                        } else {
                            // V1: Stage-based Pipeline
                            val pipeline = PipelineXmlParser.loadPipelineFromAssets(appContext, step.ref)
                            if (pipeline == null) {
                                loadErrors[pipelineName] = "Pipeline XML 不存在: ${step.ref}"
                                Log.e(TAG, "Pipeline 加载失败: ${step.ref}")
                            } else {
                                Log.i(TAG, "Pipeline 加载成功: ${pipeline.name} — ${pipeline.stages.size} 个 Stage")
                                val enriched = injectStrategies(pipeline, useCasePeriod)
                                loadedPipelines.add(pipelineName to enriched)
                            }
                        }
                    }
                    is PipelineXmlParser.StepRef.node -> {
                        Log.i(TAG, "直接 Node 步骤: ${step.id} (module=${step.module})")
                    }
                }
            }

            if (loadedPipelines.isEmpty() && loadedDagPipelines.isEmpty()) {
                return@withContext MultiPipelineResult(
                    useCaseId = useCaseId, success = false, pipelineResults = emptyMap(),
                    totalElapsedMs = 0,
                    errors = loadErrors.ifEmpty { mapOf("pipeline" to "无可执行的 Pipeline") }
                )
            }

            // 3. 构建共享的 PipelineContext
            val mergedConfig = useCaseConfig.config + configOverrides
            val config = parsePipelineConfig(mergedConfig)
            val context = PipelineContext(
                tradeDate = tradeDate,
                androidContext = appContext,
                smartMoneyCache = com.chin.stockanalysis.strategy.data.SmartMoneyCache,
                config = config
            )
            // 挂上 UI 进度回调（DAG 执行时每个节点开始会回传 pipelineName + nodeName）
            context.onNodeProgress = onNodeProgress
            context.onNodeDone = onNodeDone

            // 3.1 播种外部公共研判结果（一键建仓：市场公共研判 + 选股公共数据准备先统一执行一次，
            //     四周期专属 pipeline 直接读取 n_pool / n_adaptive / n_params 等 stageOutput）
            if (seedStageOutputs.isNotEmpty()) {
                seedStageOutputs.forEach { (k, v) -> context.stageOutputs[k] = v }
            }

            // 4. 将策略列表存入 context，供 DAG Node 使用
            val allStrategies = NodeRegistry.listModules()
                .filter { it.startsWith("strategy:") }
                .mapNotNull { module ->
                    NodeRegistry.createNode(module, emptyMap(), appContext)
                }
                .mapNotNull { node ->
                    // 从 StrategyNode 中提取原始 Strategy
                    (node as? com.chin.stockanalysis.strategy.topology.nodes.StrategyNode)?.strategy
                }
            if (allStrategies.isNotEmpty()) {
                context.stageOutputs["_strategies"] = allStrategies
            }

            // 4.1 收集各 Pipeline 步骤的 if 条件（pipelineName -> ifCondition）
            val stepIfMap = useCaseConfig.steps
                .filterIsInstance<PipelineXmlParser.StepRef.pipeline>()
                .associate { step -> step.name.ifBlank { step.ref.substringAfterLast("/") } to step.ifCondition }

            // 5. 按 parallel 标志分组执行
            val parallelGroups = groupByParallel(useCaseConfig.steps, loadedPipelines)

            val pipelineResults = mutableMapOf<String, PipelineResult>()
            val errors = mutableMapOf<String, String>()
            errors.putAll(loadErrors)
            var finalOutput: Any? = null
            var allSuccess = loadErrors.isEmpty()

            Log.i(TAG, "开始执行: ${useCaseConfig.name} — ${parallelGroups.size} 个执行组")

            val totalElapsed = measureTimeMillis {
                for ((groupIndex, group) in parallelGroups.withIndex()) {
                    if (group.size == 1) {
                        // 串行执行单个 Pipeline
                        val (name, pipeline) = group[0]
                        // if 条件过滤：条件不满足时跳过该 Pipeline
                        if (!evalStepIf(stepIfMap[name].orEmpty(), context)) {
                            Log.i(TAG, "[组 $groupIndex] 跳过 Pipeline（if 条件不满足）: $name  condition=${stepIfMap[name]}")
                            continue
                        }
                        Log.i(TAG, "[组 $groupIndex] 串行执行: $name")
                        val result = try {
                            pipeline.execute(context)
                        } catch (e: Exception) {
                            PipelineResult(
                                pipelineName = name, success = false,
                                stageResults = emptyMap(), timings = emptyMap(),
                                totalElapsedMs = 0,
                                errors = mapOf(name to "Pipeline 执行异常: ${e.message}")
                            )
                        }
                        pipelineResults[name] = result
                        if (!result.success) allSuccess = false
                        if (result.finalOutput != null) finalOutput = result.finalOutput
                        errors.putAll(result.errors)
                    } else {
                        // 并行执行同一组的多个 Pipeline
                        Log.i(TAG, "[组 $groupIndex] 并行执行: ${group.map { it.first }}")
                        coroutineScope {
                            val deferred = group.mapNotNull { (name, pipeline) ->
                                // if 条件过滤：条件不满足时跳过该 Pipeline
                                if (!evalStepIf(stepIfMap[name].orEmpty(), context)) {
                                    Log.i(TAG, "[组 $groupIndex] 跳过 Pipeline（if 条件不满足）: $name  condition=${stepIfMap[name]}")
                                    null
                                } else {
                                    async {
                                        name to try {
                                            pipeline.execute(context)
                                        } catch (e: Exception) {
                                            PipelineResult(
                                                pipelineName = name, success = false,
                                                stageResults = emptyMap(), timings = emptyMap(),
                                                totalElapsedMs = 0,
                                                errors = mapOf(name to "Pipeline 执行异常: ${e.message}")
                                            )
                                        }
                                    }
                                }
                            }
                            // 等待所有并行 Pipeline 完成
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

            // 5. 执行 DAG Pipeline（V2 高通风格）
            for ((name, dagPipeline) in loadedDagPipelines) {
                // if 条件过滤：条件不满足时跳过该 Pipeline
                if (!evalStepIf(stepIfMap[name].orEmpty(), context)) {
                    Log.i(TAG, "跳过 DAG Pipeline（if 条件不满足）: $name  condition=${stepIfMap[name]}")
                    continue
                }
                Log.i(TAG, "▶ 执行 DAG Pipeline: $name")
                val dagResult = try {
                    kotlinx.coroutines.withTimeout(180_000L) {
                        dagPipeline.execute(context)
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    Log.e(TAG, "DAG Pipeline 超时 (180s): $name")
                    DagPipelineResult(
                        pipelineName = name, success = false,
                        nodeResults = emptyMap(), totalElapsedMs = 180_000,
                        errors = mapOf(name to "DAG 执行超时 (180s)")
                    )
                } catch (e: Exception) {
                    DagPipelineResult(
                        pipelineName = name, success = false,
                        nodeResults = emptyMap(), totalElapsedMs = 0,
                        errors = mapOf(name to "DAG 执行异常: ${e.message}")
                    )
                }
                // 将 DagPipelineResult 转为 PipelineResult 存入统一结果
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

            Log.i(TAG, "执行完成: ${if (allSuccess) "成功" else "失败"}, 耗时 ${totalElapsed}ms" +
                    (if (errors.isNotEmpty()) ", 错误: ${errors.keys}" else ""))

            MultiPipelineResult(
                useCaseId = useCaseId,
                success = allSuccess,
                pipelineResults = pipelineResults,
                totalElapsedMs = totalElapsed,
                finalOutput = finalOutput,
                errors = errors,
                stageOutputs = context.stageOutputs.toMap()
            )
        }
    }

    /**
     * 求值 if 条件表达式，如 `${n_style_rotation}.suggestedPeriod == 'ultra_short'`。
     *
     * 支持格式：`${nodeId}.fieldName == 'value'` 或 `${nodeId}.fieldName != 'value'`。
     * 值从 `context.stageOutputs[nodeId]` 反射读取字段 fieldName 后与期望值比较。
     * 表达式为空时返回 true（无条件）。
     *
     * @param expr if 条件表达式，空字符串视为无条件
     * @param context 共享执行上下文（含 stageOutputs）
     */
    private suspend fun evalStepIf(expr: String, context: PipelineContext): Boolean {
        if (expr.isBlank()) return true
        // 解析 ${nodeId}.fieldName —— 兼容两种写法：
        //   1) 点号在花括号外：${n_adaptive}.direction（usecase 现行写法）
        //   2) 点号在花括号内：${n_adaptive.direction}（兼容旧写法）
        val refMatch = Regex("\\$\\{([^}]+)\\}(?:\\.([A-Za-z_][A-Za-z0-9_]*))?").find(expr) ?: run {
            Log.w(TAG, "if 条件无法解析: $expr")
            return false
        }
        var ref = refMatch.groupValues[1]
        var fieldName = refMatch.groupValues[2]
        if (fieldName.isEmpty()) {
            // 括号内没有外置字段名，尝试在括号内容中拆分 ${nodeId.fieldName}
            val dotIdx = ref.indexOf('.')
            if (dotIdx <= 0) {
                Log.w(TAG, "if 条件引用格式错误（缺少 .字段名）: $expr")
                return false
            }
            fieldName = ref.substring(dotIdx + 1)
            ref = ref.substring(0, dotIdx)
        }
        val nodeId = ref

        // 从 stageOutputs 读取输出对象
        val output = context.stageOutputs[nodeId] ?: run {
            Log.w(TAG, "if 条件引用不存在: $expr（stageOutputs 中无 $nodeId）")
            return false
        }

        // 反射读取字段值
        val actual = readFieldValue(output, fieldName)?.toString() ?: run {
            Log.w(TAG, "if 条件读取字段失败: $expr（$nodeId 无字段 $fieldName）")
            return false
        }

        // 解析 == 'value' 或 != 'value'
        val cmpMatch = Regex("(==|!=)\\s*'([^']*)'").find(expr) ?: run {
            Log.w(TAG, "if 条件缺少比较运算符或期望值: $expr")
            return false
        }
        val op = cmpMatch.groupValues[1]
        val expected = cmpMatch.groupValues[2]

        val matched = actual == expected
        return when (op) {
            "==" -> matched
            "!=" -> !matched
            else -> false
        }
    }

    /**
     * 通过反射读取对象字段值（优先 getter，其次直接字段访问）。
     */
    private fun readFieldValue(obj: Any, fieldName: String): Any? {
        val capName = fieldName.replaceFirstChar { it.uppercase() }
        return try {
            // 尝试 getFieldName()
            obj.javaClass.getMethod("get$capName").invoke(obj)
        } catch (_: Exception) {
            try {
                // 尝试 isFieldName()（布尔类型）
                obj.javaClass.getMethod("is$capName").invoke(obj)
            } catch (_: Exception) {
                try {
                    obj.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }.get(obj)
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    /**
     * 按 parallel 标志将 Pipeline 分组。
     *
     * 分组规则：
     * - parallel=false 的 Pipeline 开始一个新组
     * - parallel=true 的 Pipeline 加入当前组（与前一个并行）
     *
     * 例如：[A(false), B(true), C(false), D(true)] → [[A, B], [C, D]]
     *
     * @return 每个内部 List 是一组可并行执行的 Pipeline
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
                    // 单 Node 步骤：创建一个只有一个 Node 的单步 Pipeline
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
                                    // 创建一个 identity link（Unit → Node）
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
     * 将已注册的策略动态注入到 Pipeline 的 "策略筛选" Stage。
     *
     * 找到名为 "策略筛选" 的 Stage，为每个策略创建一条 LinkList。
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
            Log.w(TAG, "无匹配周期[$periodStr]的策略，Pipeline 将跳过策略筛选 Stage")
            return pipeline
        }

        // 需要一个共用的 StockPoolNode（策略筛选 Stage 的第一个节点）
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
            if (stage.name == "策略筛选") {
                stage.copy(linkLists = strategyLinkLists)
            } else {
                stage
            }
        }

        Log.i(TAG, "注入 ${strategyLinkLists.size} 个策略到 Pipeline")
        return pipeline.copy(stages = newStages)
    }

    /**
     * 将已注册的策略动态注入到 DAG Pipeline。
     *
     * 在 DAG 图中找到 `n_merge`（信号合并）节点，在其前面插入策略节点：
     * - n_pool → strategy_1 → n_merge（n_pool 在本 pipeline 内时）
     * - n_pool → strategy_2 → n_merge
     * - ...（每个策略并行）
     *
     * **公共 L0/L1 抽取场景**：n_pool 已上移到公共 pipeline 时，不再添加
     * `n_pool → strategy_i` 边（否则悬空边会导致拓扑死锁），策略节点以根节点运行，
     * 通过 `context.getStageOutput("n_pool")` 从公共 pipeline 的 stageOutputs 兜底读取股票池。
     *
     * **周期过滤**：只注入 `holdingPeriods` 包含 [periodStr] 对应周期的策略，
     * 避免中线 Pipeline 执行超短线策略（反之亦然）。periodStr 为 null 时注入全部。
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
            Log.w(TAG, "无匹配周期[$periodStr]的策略，DAG Pipeline 将跳过策略注入")
            return dag
        }

        // 检查是否有 n_merge 节点
        val hasMerge = dag.nodes.any { it.nodeId == "n_merge" }
        if (!hasMerge) {
            Log.w(TAG, "DAG 中无 n_merge 节点，策略注入跳过")
            return dag
        }

        // 创建策略 DagNode
        val strategyDagNodes = strategies.mapIndexed { index, (module, node) ->
            DagNode(
                nodeId = "strategy_$index",
                nodeName = node.nodeName,
                node = node
            )
        }

        // 判断 n_pool 是否在当前 DAG 中（公共 L0/L1 抽取后，n_pool 可能已上移到公共 pipeline）
        val hasPoolNode = dag.nodes.any { it.nodeId == "n_pool" }

        // 创建边：n_pool → strategy_i（仅当 n_pool 在本 pipeline 内），strategy_i → n_merge
        // 若 n_pool 已不在本 pipeline（公共 L0/L1 抽取场景），策略节点以根节点运行，
        // 通过 context.getStageOutput("n_pool") 从公共 pipeline 的 stageOutputs 兜底读取股票池。
        val newEdges = dag.edges.toMutableList()
        for (strategyNode in strategyDagNodes) {
            if (hasPoolNode) {
                newEdges.add(DagEdge("n_pool", 0, strategyNode.nodeId, 0))
            }
            newEdges.add(DagEdge(strategyNode.nodeId, 0, "n_merge", 0))
        }

        // 需要移除 n_pool → n_merge 的直接连接（如果存在），让策略节点成为中间层
        val filteredEdges = newEdges.filterNot { it.sourceNodeId == "n_pool" && it.targetNodeId == "n_merge" }

        Log.i(TAG, "注入 ${strategyDagNodes.size} 个策略到 DAG Pipeline" +
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
     * UseCase XML 的 holdingPeriod 字串 → [HoldingPeriod] 枚举。
     * 无法识别时返回 null（注入全部策略，向后兼容）。
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
            maxSignalsPerStrategy = config["maxSignalsPerStrategy"]?.toIntOrNull() ?: 15,
            saveAsAiOnly = config["saveAsAiOnly"]?.toBooleanStrictOrNull() ?: false
        )
    }

    // ════════════════════════════════════════════════════
    // 数据类
    // ════════════════════════════════════════════════════

    /**
     * 多 Pipeline 执行结果。
     *
     * @property useCaseId UseCase ID
     * @property success 所有 Pipeline 是否均成功
     * @property pipelineResults 各 Pipeline 的执行结果（pipelineName → result）
     * @property totalElapsedMs 总耗时（毫秒）
     * @property finalOutput 最后一个非空输出
     * @property errors 错误信息（pipelineName/errorKey → message）
     * @property stageOutputs 执行完成后共享上下文的全部 stageOutput（一键建仓公共研判结果播种用）
     */
    data class MultiPipelineResult(
        val useCaseId: String,
        val success: Boolean,
        val pipelineResults: Map<String, PipelineResult>,
        val totalElapsedMs: Long,
        val finalOutput: Any? = null,
        val errors: Map<String, String> = emptyMap(),
        val stageOutputs: Map<String, Any?> = emptyMap()
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
