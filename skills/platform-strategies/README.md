# Platform Strategies Skill — 平台策略 / 豆包体系完整闭环

## 模块职责
"量化选股 → 策略 → 平台策略"页面的策略列表、执行与导入。平台策略本质是
`assets/usecases/*.xml` 定义的 UseCase，通过拓扑引擎加载执行。
**豆包体系完整闭环交易系统 = `complete_closed_loop_usecase.xml`**。

## 触发条件
用户提到以下关键词时，优先查阅本 Skill：
- 平台策略 / 策略列表 / 执行策略 / 运行策略没有输出
- 豆包体系 / 豆包 / 完整闭环 / 闭环交易
- 策略导入 / 策略详情 / 量化选股

## 关键文件
| 文件 | 职责 |
|------|------|
| `app/src/main/java/com/chin/stockanalysis/ui/StrategyListFragment.kt` | 平台策略列表 UI（"量化选股→策略→平台策略"） |
| `app/src/main/java/com/chin/stockanalysis/ui/StrategyDetailFragment.kt` | 策略详情 |
| `app/src/main/java/com/chin/stockanalysis/ui/StrategyImportFragment.kt` | 策略导入（XML/文本→useCases） |
| `app/src/main/java/com/chin/stockanalysis/strategy/StrategyEngine.kt` | 策略引擎（执行入口） |
| `app/src/main/java/com/chin/stockanalysis/strategy/StrategyConfig.kt` | 策略配置 |
| `app/src/main/java/com/chin/stockanalysis/strategy/StrategyEngineHolder.kt` | 引擎单例持有 |
| `app/src/main/assets/usecases/common_usecase.xml` | 平台公共 UseCase |
| `app/src/main/assets/usecases/complete_closed_loop_usecase.xml` | **豆包体系完整闭环交易系统** |

## 豆包体系完整闭环（complete_closed_loop_usecase.xml）结构
- **第一层**：市场方向研判（超短/短/中/长四周期 + 大盘）
- **第二层**：AI 选股（四周期 pipeline 选股）
- **第三层**：股票深度分析
- **第四层**：12 个条件 pipeline（超短/短/中/长 × 多空/震荡），带 `if="${n_adaptive}.direction == 'BULLISH'"` 等条件
- **第五层**：合并输出（merge_boost）

> 注意：第四层 pipeline 全部依赖 `n_adaptive.direction` 字段，若前置节点未输出该字段，
> 整个第四层会被跳过 → 表现为"运行豆包体系没有输出"。

## 执行链路
```
StrategyListFragment (列表)
  └─> 点击策略 → StrategyDetailFragment → 执行按钮
        └─> StrategyEngine.execute(usecaseId)
              └─> UseCaseExecution.execute(usecaseId, params)   // 见 topology-engine skill
                    └─> 加载 assets/usecases/complete_closed_loop_usecase.xml
                          └─> 逐 pipeline 执行
```

## 常见任务指引
### 1. 排查"运行豆包体系没有输出"
1. 确认 `StrategyListFragment` 列表里有该策略（搜"豆包"或"完整闭环"）
2. 确认 usecase XML 能被 `UseCaseLoader` 加载（看日志有无报错）
3. 检查第四层 pipeline 的 `if` 条件：`${n_adaptive}.direction` 是否真的输出
4. 检查 `AMarketAnalysisNode`（n_adaptive）在目标周期是否正确执行
5. 在 `UseCaseExecution` 中打印每个 pipeline 的 skipped 原因

### 2. 更新豆包体系选股逻辑
- 修改 `complete_closed_loop_usecase.xml` 中的节点参数（过滤阈值、排序字段等）
- 或更新其引用的 `*_pipeline.xml`（如 `stock_deep_analysis_pipeline.xml`）

### 3. 新增平台策略
- 在 `assets/usecases/` 新建 `xxx_usecase.xml` + 对应 pipeline XML
- `StrategyListFragment` 自动扫描加载 usecases 目录
- 或通过 `StrategyImportFragment` 导入

## 文件清单
- `skills/platform-strategies/README.md` — 本文件（skill 定义）
