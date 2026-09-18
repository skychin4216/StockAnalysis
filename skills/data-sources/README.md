# Data Sources Skill — 行情数据源

## 模块职责
所有行情/交易数据的获取层，封装东方财富、腾讯、新浪、AKShare、聚宽等外部接口，
向上层（板块、选股、监测、UI）提供统一数据。

## 触发条件
用户提到以下关键词时，优先查阅本 Skill：
- 行情数据 / 数据源 / 数据没更新 / 拉不到数据
- 东方财富 / 腾讯 / 新浪接口 / 板块数据
- 换数据源 / 加新数据源

## 关键文件（stock/data/sources/）
| 文件 | 数据内容 |
|------|---------|
| `EastMoneyStockSource.kt` | 个股行情（东方财富 push2 接口） |
| `EastMoneySectorSource.kt` | 板块行情/板块成分 |
| `EastMoneyHotSectorSource.kt` | 热门板块 + 板块龙头（龙头异动用，详见 leader-monitor skill） |
| `EastMoneyBidAskSource.kt` | 买卖五档盘口 |
| `TencentStockSource.kt` | 腾讯行情 |
| `SinaStockSource.kt` | 新浪行情 |
| `AKShareSource.kt` | AKShare（Python 数据） |
| `JoinQuantsSource.kt` | 聚宽数据 |
| `EftMarketDataSource.kt` | ETF 市场数据 |
| `SectorSubDivision.kt` | 板块细分 |
| `StockNewsFetcher.kt` | 个股新闻 |

## 数据流
```
数据源 (sources/*) → StockDataCenter (缓存/分发) → 上层调用方
                          ├─> SectorLeaderMonitor（板块龙头）
                          ├─> QuantTradingPipeline（选股）
                          └─> UI Fragment（行情展示）
```

## 常见任务指引
### 1. 数据没更新
- 检查对应数据源 URL/接口是否正常（网络请求日志）
- 检查 `StockDataCenter` 缓存是否过期（TTL）
- 行情接口高峰期可能限流，**自动重试**（见 [NETWORK_RETRY.md](../NETWORK_RETRY.md)：重试3次+多源回退腾讯→东财→新浪）

### 2. 新增数据源
- 在 `sources/` 新建 `XxxSource.kt`，实现统一接口
- 在 `StockDataCenter` 注册/路由

### 3. 调整龙头监测数据源
- 改 `EastMoneyHotSectorSource.fetchSectorLeaders()` 的 `pz`（每板块龙头数量）、`fid`（排序字段，f3=涨幅）

## 文件清单
- `skills/data-sources/README.md` — 本文件（skill 定义）
