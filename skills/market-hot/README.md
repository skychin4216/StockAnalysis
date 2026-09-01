# Market Hot Skill — 市场热度 / 板块行情 / 新闻

## 模块职责
市场热度 Tab、热门板块、板块轮动、热股、新闻快讯等市场情报相关功能。

## 触发条件
用户提到以下关键词时，优先查阅本 Skill：
- 市场热度 / 热门板块 / 板块轮动 / 板块行情
- 热股 / 新闻快讯 / 头条
- 板块详情 / 板块趋势图

## 关键文件
| 文件 | 职责 |
|------|------|
| `app/src/main/java/com/chin/stockanalysis/ui/MarketHotFragment.kt` | 市场热度 Tab |
| `app/src/main/java/com/chin/stockanalysis/ui/HotNewsFragment.kt` / `HotNewsDetailFragment.kt` | 新闻快讯 |
| `app/src/main/java/com/chin/stockanalysis/ui/SectorTabFragment.kt` | 板块 Tab |
| `app/src/main/java/com/chin/stockanalysis/ui/SectorDetailFragment.kt` | 板块详情 |
| `app/src/main/java/com/chin/stockanalysis/ui/SectorRotationChartFragment.kt` | 板块轮动图 |
| `app/src/main/java/com/chin/stockanalysis/ui/SectorTrendChartFragment.kt` | 板块趋势图 |
| `app/src/main/java/com/chin/stockanalysis/strategy/sector/` | 板块策略逻辑 |
| `app/src/main/java/com/chin/stockanalysis/strategy/market/` | 市场策略逻辑 |
| `app/src/main/java/com/chin/stockanalysis/stock/data/sources/EastMoneySectorSource.kt` | 板块数据源 |

## 常见任务指引
### 1. 修改板块轮动图
- 改 `SectorRotationChartFragment.kt` / `strategy/sector/`

### 2. 修改新闻列表
- 改 `HotNewsFragment.kt` / `StockNewsFetcher.kt`

## 文件清单
- `skills/market-hot/README.md` — 本文件（skill 定义）
