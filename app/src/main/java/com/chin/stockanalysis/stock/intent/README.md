# intent/ — 意图识别（链式处理器）

## 文件

| 文件 | 说明 |
|------|------|
| `StockIntent.kt` | 意图数据类 |
| `IntentProcessorChain.kt` | 链式处理器调度 |
| `handlers/` | 处理器实现 |

## 处理器

| 处理器 | 说明 |
|------|------|
| `IntentHandler.kt` | 处理器接口 |
| `StockCodeHandler.kt` | 识别股票代码（600519 等） |
| `StockNameHandler.kt` | 识别精确股票名称（贵州茅台） |
| `FuzzyStockNameHandler.kt` | 模糊识别股票名称 |
| `GeneralStockHandler.kt` | 通用股票查询 |
| `IndexHandler.kt` | 指数查询（沪深300 等） |
| `AiIntentHandler.kt` | AI 意图处理 |