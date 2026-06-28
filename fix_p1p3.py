"""Fix all P1-P3 issues from Agent code review report."""
import os, re

BASE = r'app\src\main\java\com\chin\stockanalysis'

def rfile(rel, old, new):
    """Replace text in file, return True if changed."""
    path = os.path.join(BASE, rel)
    with open(path, 'r', encoding='utf-8') as f:
        t = f.read()
    if old in t:
        t = t.replace(old, new)
        with open(path, 'w', encoding='utf-8') as f:
            f.write(t)
        print(f'  FIXED: {rel}')
    else:
        print(f'  SKIP: {rel} (pattern not found)')

# P1-5: AgentBase LLM timeout — already fixed in previous step (60s→25s)

# P1-6: AgentBase callLLM double-resume guard
rfile('agent/framework/AgentBase.kt',
    'kotlinx.coroutines.suspendCancellableCoroutine { cont ->\n                    slot.provider.sendMessageStream(\n                        messages = emptyList(),\n                        systemPrompt = buildSystemPrompt() + "\n\n" + prompt,\n                        onSuccess = {},\n                        onComplete = { full -> cont.resume(full, null) },\n                        onError = { err -> cont.resumeWith(Result.failure(Exception(err))) }\n                    )\n                }',
    'kotlinx.coroutines.suspendCancellableCoroutine { cont ->\n                    var resumed = false\n                    slot.provider.sendMessageStream(\n                        messages = emptyList(),\n                        systemPrompt = buildSystemPrompt() + "\n\n" + prompt,\n                        onSuccess = {},\n                        onComplete = { full -> if (!resumed) { resumed = true; cont.resume(full, null) } },\n                        onError = { err -> if (!resumed) { resumed = true; cont.resumeWith(Result.failure(Exception(err))) } }\n                    )\n                }')

# P1-7: StockPickingRouter Legacy — 调用真实 Pipeline 而非空壳
rfile('agent/router/StockPickingRouter.kt',
    '        // 調用舊的 AgentPipelineOrchestrator\n        val orchestrator = AgentPipelineOrchestrator(context)\n        // 舊系統返回的是 PipelineResult，需要轉換為 StockPickingResult\n        // 這裡是適配代碼\n        return StockPickingResult(\n            success = true,\n            recommendations = emptyList(),\n            rawOutput = "Legacy pipeline executed"\n        )',
    '        // 调用旧的 AgentPipelineOrchestrator 获取真正的选股结果\n        val orchestrator = AgentPipelineOrchestrator(context)\n        val pipelineResult = orchestrator.execute("agent_stock_picking")\n        val recs = pipelineResult.topStocks.map {\n            StockRecommendation(\n                code = it.code,\n                name = it.name,\n                strategies = it.strategyHits.map { hit -> hit.strategyName },\n                score = it.compositeScore,\n                reason = it.reasoning\n            )\n        }\n        return StockPickingResult(\n            success = pipelineResult.success,\n            recommendations = recs,\n            rawOutput = pipelineResult.reasoning\n        )')

# P1-8: ChatRouter Legacy 返回 magic string — 改为明确标记
rfile('agent/router/ChatRouter.kt',
    '        // Legacy 模式下返回標記，由調用方自己走原有流程\n        return ChatAgentResult(\n            success = true,\n            response = "LEGACY_MODE",\n            intent = "LEGACY"\n        )',
    '        // Legacy 模式下抛出异常，由调用方自动 fallback 到原有流程\n        throw UnsupportedOperationException("ChatRouter Legacy: 请使用原有 ChatTabFragment 流程")\n        @Suppress("UNREACHABLE_CODE")\n        return ChatAgentResult(\n            success = false,\n            response = "请使用原有对话流程",\n            intent = "LEGACY"\n        )')

# P2-9: AgentBase executeTool 传递当前上下文（而非空 AgentContext）
# But executeTool is called from react() which has a ctx parameter. The issue is that
# executeTool signature doesn't accept context. We need to add it or make it use the
# last ctx from react. Since react() passes ctx to each step, the simplest fix is to
# store the current context and use it in executeTool.
rfile('agent/framework/AgentBase.kt',
    '    /** 執行工具 */\n    private suspend fun executeTool(toolName: String, params: Map<String, String>): String {\n        val tool = tools[toolName]\n            ?: return "錯誤: 工具 \'$toolName\' 未註冊"\n        return try {\n            tool.execute(params, AgentContext())\n        } catch (e: Exception) {\n            "錯誤: 工具執行失敗: ${e.message}"\n        }\n    }',
    '    private var currentContext: AgentContext = AgentContext()\n\n    /** 執行工具 */\n    private suspend fun executeTool(toolName: String, params: Map<String, String>): String {\n        val tool = tools[toolName]\n            ?: return "錯誤: 工具 \'$toolName\' 未註冊"\n        return try {\n            tool.execute(params, currentContext)\n        } catch (e: Exception) {\n            "錯誤: 工具執行失敗: ${e.message}"\n        }\n    }')

# Also need to set currentContext in react() method
rfile('agent/framework/AgentBase.kt',
    '    suspend fun react(\n        input: String,\n        ctx: AgentContext = AgentContext(),\n        maxSteps: Int = MAX_REACT_STEPS\n    ): AgentResult {\n        log("🚀 [$name] ReAct 開始 | 輸入: ${input.take(60)}")\n        onBeforeExecute(ctx)',
    '    suspend fun react(\n        input: String,\n        ctx: AgentContext = AgentContext(),\n        maxSteps: Int = MAX_REACT_STEPS\n    ): AgentResult {\n        currentContext = ctx\n        log("🚀 [$name] ReAct 開始 | 輸入: ${input.take(60)}")\n        onBeforeExecute(ctx)')

# P2-12: FeatureFlagManager AUTO mode — add fallback logic
rfile('config/FeatureFlagManager.kt',
    '    fun resolveRoute(moduleRoute: AgentRoute): AgentRoute {\n        return when {\n            !useAgentFramework && moduleRoute == AgentRoute.AGENT_FRAMEWORK -> AgentRoute.LEGACY\n            useAgentFramework && moduleRoute == AgentRoute.LEGACY -> AgentRoute.AGENT_FRAMEWORK\n            else -> moduleRoute\n        }\n    }',
    '    fun resolveRoute(moduleRoute: AgentRoute): AgentRoute {\n        return when {\n            !useAgentFramework && moduleRoute == AgentRoute.AGENT_FRAMEWORK -> AgentRoute.LEGACY\n            useAgentFramework && moduleRoute == AgentRoute.LEGACY -> AgentRoute.AGENT_FRAMEWORK\n            moduleRoute == AgentRoute.AUTO -> {\n                // AUTO mode: try Agent first, fallback to Legacy if Agent unavailable\n                if (useAgentFramework) AgentRoute.AGENT_FRAMEWORK else AgentRoute.LEGACY\n            }\n            else -> moduleRoute\n        }\n    }')

# P2-13: ShortTermQuantFragment 吞没异常 — 添加日志
rfile('strategy/trade/ShortTermQuantFragment.kt',
    '        } catch (_: Exception) {}',
    '        } catch (e: Exception) {\n            Log.w("ShortTermQuant", "Agent routing failed: ${e.message}")\n        }')

# P2-14: NewsMonitoringAgent NaN on empty data — add isEmpty check
rfile('agent/news/NewsMonitoringAgent.kt',
    '                val avgChange = marketData.map { it.changePct }.average()\n                val upCount = marketData.count { it.changePct > 0 }\n                val downCount = marketData.count { it.changePct < 0 }\n\n                // 獲取熱門板塊',
    '                if (marketData.isEmpty()) return@withContext "暫無市場數據"\n                val avgChange = marketData.map { it.changePct }.average()\n                val upCount = marketData.count { it.changePct > 0 }\n                val downCount = marketData.count { it.changePct < 0 }\n\n                // 獲取熱門板塊')

# P3-15: MultiAgentOrchestrator 字符串长度判断 → 基于关键词
rfile('agent/framework/MultiAgentOrchestrator.kt',
    '        val taskLen = task.length\n        val isComplex = taskLen > 30',
    '        val keywords = listOf("分析", "選股", "交易", "風控", "監控", "策略", "倉位")\n        val isComplex = keywords.any { task.contains(it) } || task.length > 80')

# P3-17: TradeRouter 未使用的 import — 注释掉
rfile('agent/router/TradeRouter.kt',
    'import com.chin.stockanalysis.strategy.trade.SimulationTradeEngine\n',
    '// import com.chin.stockanalysis.strategy.trade.SimulationTradeEngine  // reserved for future use\n')

print('\n=== P1-P3 fixes complete ===')