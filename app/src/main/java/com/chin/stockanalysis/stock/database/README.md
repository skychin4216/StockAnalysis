# database/ — Room 本地数据库

## 文件

| 文件 | 说明 |
|------|------|
| `StockDatabase.kt` | Room Database + Entity + DAO 定义 |
| `StockDatabaseManager.kt` | 单例管理器：初始化、同步、查询接口 |

## 数据库表

### stock_basics — 股票基本信息

| 字段 | 类型 | 说明 |
|------|------|------|
| code | TEXT PK | sh600519 / sz000858 |
| name | TEXT | 贵州茅台 |
| business | TEXT | 核心业务描述 |
| chain_rationale | TEXT | 产业链核心依据 |

### sector_stocks — 板块与股票映射

| 字段 | 类型 | 说明 |
|------|------|------|
| id | INTEGER PK | 自增 |
| sector_key | TEXT | commercial_space |
| sector_name | TEXT | 商业航天 |
| stock_code | TEXT | 关联 sh600457 |

## 同步策略

- **首次启动**：从 `ThemeStockLibrary` 全量导入
- **每 24 小时**：对比 ThemeStockLibrary → DB，自动新增/更新/删除
- `ThemeStockLibrary` 人工维护 → 下次同步自动反映到 DB

## 使用

```kotlin
val dbManager = StockDatabaseManager.getInstance(context)
dbManager.ensureInitialized()                          // 异步，首次迁移
val stocks = dbManager.getStocksBySector("ai_tech")    // 按板块查询
val stock = dbManager.getStockByCode("sh600519")        // 按代码查询
val results = dbManager.searchStocksByName("茅台")      // 模糊搜索