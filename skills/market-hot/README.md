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
| `AutoQuant/autoquant/gui/market_hot_tab.py` | **exe 侧板块热度页签**（2026-09）：东财 clist 板块实时榜（行业/概念，push2delay/push2 多域名回退）+ 点击板块看成分龙头 Top + 双击开本地 K 线 |

## 常见任务指引
### 1. 修改板块轮动图
- 改 `SectorRotationChartFragment.kt` / `strategy/sector/`

### 2. 修改新闻列表
- 改 `HotNewsFragment.kt` / `StockNewsFetcher.kt`

### 3. exe 板块热度页
- 数据入口：`autoquant.gui.market_hot_tab` 的 `fetch_board_list(concept)` / `fetch_board_stocks(code)`
- 双端同源：exe 走东财 push2（直连不走系统代理）；APK 走 `EastMoneySectorSource`

## 文件清单
- `skills/market-hot/README.md` — 本文件（skill 定义）
