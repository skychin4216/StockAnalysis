# 股票 TAB 架构

> 最后更新：2026-08-13 | 代码位置：`ui/StockTabFragment.kt`、`ui/MarketHotFragment.kt`、`ui/WatchlistUnifiedFragment.kt`、`ui/HotNewsFragment.kt`

---

## 一、入口层级

```
MainActivity Tab[2] 股票（v11.0）
└── StockTabFragment
    ├── Tab 0: 热门行情 MarketHotFragment（全球指数 + A股热门板块）
    ├── Tab 1: 自选/AI精选 WatchlistUnifiedFragment（按钮切换两种模式）
    └── Tab 2: 热点新闻 HotNewsFragment（板块新闻分析）
```

## 二、页面结构

### 2.1 StockTabFragment（外壳）

- ViewPager2 + TabLayout（指示色 `#E65100`，offScreenPageLimit=1）。
- 对外方法：
  - `switchToWatchlist()` → 切到自选 sub-tab
  - `switchToInstitutional()` → 切到「机构推荐」模式
  - `getWatchlistFragment()` → 遍历 childFragmentManager 获取实例

### 2.2 MarketHotFragment（热门行情）

- 全球指数行情 + A 股热门板块。

### 2.3 WatchlistUnifiedFragment（自选 / AI精选 / 机构推荐）

- 按钮切换：自选列表 ↔ AI 精选。
- 机构推荐模式：AI 分析结论可一键保存为机构推荐（来自 ChatTabFragment 分享/分析）。

### 2.4 HotNewsFragment（热点新闻）

- 板块新闻分析列表，数据由 `HotSectorNewsUpdater` 后台拉取 + 本地 `sector_news` 表提供。

## 三、数据来源

- 行情：`StockService` + 多数据源仓库（`StockDataSourceFactory.createDefaultRepository`）。
- 股票词典：`StockNameTrie`（MainActivity 启动时构建，供意图解析/搜索）。
- 板块成分股预热：`prefetchSectorStocks()` 后台拉取热门板块成分股 → `sector_stocks` 表。

## 四、相关文档

- [AI 对话框架](../agent/ai-conversation-framework.md)（分享 → 机构推荐链路）
- [后台服务架构](../background/background-services.md)（HotSectorNewsUpdater）
- [策略架构（实仓）](../strategy/strategy-real-position.md)
