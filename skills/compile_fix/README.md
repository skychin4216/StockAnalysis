# Compile Fix Skill — Agent 编译错误修复工具集

## 用途

当 Cline 生成的 Agent 代码存在编译错误时，这些脚本提供批量修复能力。

## 核心原则

**如果编译错误，需要先把函数找到，然后判断要不要重写：**
1. 先查阅实际 Entity/Dao 的字段名和方法签名
2. 如果错误是**字段名/方法名不匹配**（如 `stockName` → `name`），用脚本批量替换
3. 如果错误是**整个方法不存在**（如 `getTopSectorsByDate`），直接重写那段逻辑

## 脚本说明

| 脚本 | 用途 | 使用时机 |
|------|------|---------|
| `fix_all_agents.py` | 全面修复所有 Agent 文件的 ctx shadowing、字段名、DAO 方法 | 首次运行，批量修复 |
| `fix_safe.py` | 安全版本 — 避免破坏 try/catch 块 | 替换有风险的方法时使用 |
| `fix_remaining.py` | 修复剩余少量错误 | 编译后只剩几个错误时 |
| `fix_agent_batch.py` | 初始批量修复（第一版） | 已弃用，保留参考 |
| `fix_agent_errors.py` | 早期尝试 | 已弃用 |
| `fix_agent_safe.py` | 早期安全版本 | 已弃用 |
| `fix_agents_final.py` | 综合修复（含危险替换） | 已弃用，保留参考 |
| `fix_agents_v2.py` | 早期版本 | 已弃用 |

## 已知问题模式

### 1. ctx shadowing
```kotlin
// ❌ 错误
class MyTool(private val ctx: Context) : AgentTool {
    override suspend fun execute(params: Map<String, String>, ctx: AgentContext): String {
        val db = StockDatabase.getInstance(ctx) // 这里 ctx 是 AgentContext！
    }
}

// ✅ 修复
class MyTool(private val ctx: Context) : AgentTool {
    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val c = ctx
        return withContext(Dispatchers.IO) {
            val db = StockDatabase.getInstance(c) // 使用构造函数参数
        }
    }
}
```

### 2. Entity 字段名映射
| AI 生成 | 实际 | Entity |
|---------|------|--------|
| `stockName` | `name` | DailySnapshotEntity, StockBasicEntity |
| `stockCode` | `code` | DailySnapshotEntity |
| `mainBusiness` | `business` | StockBasicEntity |
| `chainLogic` | `chainRationale` | StockBasicEntity |
| `turnover` | `turnoverRate` | DailySnapshotEntity |
| `mainForceNetInflow` | `mainNetInflow` | DailySnapshotEntity |
| `strength` (news) | `sentiment` | NewsFactorEntity |

### 3. DAO 方法映射
| AI 生成 | 实际可用 |
|---------|---------|
| `getRecentByCode(code, days)` | `getByCode(code, days)` |
| `getLatestByCode(code)` | `getByDateAndCode(today, code)` |
| `getActiveOrders()` | `getRecent(200).filter { it.status in listOf("BUYING", "PENDING") }` |
| `getSectorsByStockCode(code)` | `getSectorNamesByStockCode(code)` |
| `searchByKeyword(keyword)` | 不存在 → 用 `getActiveBySector("", limit)` 代替 |
| `getTopSectorsByDate(date, n)` | 不存在 → 直接重写那段逻辑 |
| `getByDateAndSector(date, sector)` | 不存在 → 直接重写那段逻辑 |
| `getLatestActive(limit)` | 不存在 → 用 `getActiveBySector("", limit)` 代替 |
| `updateStatusByCode(code, status)` | 不存在 → 直接重写那段逻辑 |

### 4. listOf() 泛型推断
```kotlin
// ❌ 错误
override val parameters = listOf()

// ✅ 修复
override val parameters = listOf<String>()
```

### 5. AgentBase.kt parsePlan 作用域
```kotlin
// ❌ parsePlan() 是独立方法，无法访问 planAndExecute 的 goal 参数
private fun parsePlan(raw: String): AgentPlan {
    AgentPlan(steps = listOf(PlanStep(..., description = goal))) // 错误！
}

// ✅ 添加 fallbackGoal 参数
private fun parsePlan(raw: String, fallbackGoal: String = ""): AgentPlan {
    AgentPlan(steps = listOf(PlanStep(..., description = fallbackGoal)))
}
```

### 6. StrategyTradeOrderEntity 构造函数
需要传递 `buyTime` 和 `scoreAtBuy` 参数：
```kotlin
StrategyTradeOrderEntity(
    ..., // 其他参数
    scoreAtBuy = 0,
    buyTime = java.time.LocalTime.now().toString().take(8)
)
```

## 快速使用

```bash
# 1. 首次修复
python skills/compile_fix/fix_all_agents.py

# 2. 编译检查
./gradlew :app:compileDebugKotlin

# 3. 如有剩余错误，手动修复后运行
python skills/compile_fix/fix_remaining.py