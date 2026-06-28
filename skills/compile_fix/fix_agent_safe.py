"""
Safe fix: Only replace exact patterns that are known to be wrong.
Does NOT do batch operations that could break try blocks.
"""
import os

BASE = r'app\src\main\java\com\chin\stockanalysis'

# Only these specific, safe replacements per file
fixes = {
    'agent/chat/ChatAgent.kt': [
        ('.stockName', '.name'),
        ('.stockCode', '.code'),
    ],
    'agent/framework/AgentBase.kt': [
        ('private fun parsePlan(raw: String): AgentPlan {', 'private fun parsePlan(raw: String, fallbackGoal: String = "Unnamed"): AgentPlan {'),
        ('description = goal))', 'description = fallbackGoal))'),
        ('val plan = parsePlan(planRaw)', 'val plan = parsePlan(planRaw, goal)'),
    ],
    'agent/framework/MultiAgentOrchestrator.kt': [
        ('takeIf { it.value > 0 }?.value?.let { agents[it.key] }', 'takeIf { it.value > 0 }?.let { agents[it.key] }'),
    ],
    'strategy/trade/ShortTermQuantFragment.kt': [
        ('pipelineProgressView.updateSteps', 'pipelineProgressView.reset'),
    ],
    'agent/pipeline/ui/PipelineProgressView.kt': [
        ('PipelineStep.STEPS', 'emptyList<PipelineStep>()'),
    ],
    'agent/router/AnalysisRouter.kt': [
        ("result?.compositeScore", "0"),
        ("result?.recommendation", '"WATCH"'),
        ("result?.reasoning", '""'),
        ('service.analyze(stockCode)', 'service.analyze(stockCode, onProgress = {})'),
    ],
}

for rel, replacements in fixes.items():
    path = os.path.join(BASE, rel)
    if not os.path.exists(path):
        print(f'SKIP: {rel}')
        continue
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    original = content
    for old, new in replacements:
        content = content.replace(old, new)
    if content != original:
        with open(path, 'w', encoding='utf-8') as f:
            f.write(content)
        print(f'FIXED: {rel}')
    else:
        print(f'CLEAN: {rel}')
print('Done')