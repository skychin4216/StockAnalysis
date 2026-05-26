# theme/ — 主题/板块识别 + 用户偏好

## 文件

| 文件 | 说明 |
|------|------|
| `ThemeStockLibrary.kt` | 手工维护的精品股票池（9个板块 120+ 只股票） |
| `ThemeStockService.kt` | 主题查询整合层（方案A: 内置库 + 方案B: 东方财富API） |
| `UserPreferenceManager.kt` | 用户偏好记忆（市值/板块/价格偏好，跨会话持久化） |

## 查询流程

```
用户输入 "商业航天"
  └─ ThemeStockService.processThemeQuery()
       ├─ [方案B 优先] EastMoneySectorSource.fetchByName()  → 东方财富板块 API
       └─ [方案A 兜底] ThemeStockLibrary.findTheme()         → 内置股票库
            └─ MultiSourceStockRepository.getRealtime()      → 拉实时行情
```

## ThemeStockLibrary 维护指南

要新增/修改板块股票，编辑 `ThemeStockLibrary.kt`：
- `THEME_ALIASES`：关键词 → 板块 key 映射
- `THEME_MAP`：板块 key → 股票列表（代码/名称/业务/依据）

下次 App 启动（超过 24h）时，`StockDatabaseManager` 自动同步到 Room 数据库。