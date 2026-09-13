# 公共市场数据库（market_data.db）

统一 exe（AutoQuant）与 smalltools（选股引擎）的数据源，解决三端数据割裂：

| 数据 | 改造前 | 改造后 |
|---|---|---|
| K 线（日） | smalltools `_kline_cache.json`（四年） vs exe `data/cache/*.csv`（一年半、字段不全） | 统一存 SQLite `kline` 表 |
| 公告 | 仅 smalltools `_announce_cache.json` | 统一存 SQLite `announce` 表 |
| 新闻 | 仅 smalltools（`_news_cache.json`，未生成） | 统一存 SQLite `news` 表 |
| 拟合参数 | `_records/selected_*.json` + `backtest_params.json` | 追加归档 SQLite `params` 表（JSON 流程保留，APK 兼容） |

## 库位置与结构

```
StockAnalysis/data/market_data.db   （生成物，.gitignore 忽略）
```

表：

- `kline(secid, date, open, high, low, close, volume, change_pct, turnover, src, name)`
  主键 `(secid, date)`；secid 格式 `sh600519` / `sz000338` / 指数 `sh000001`
- `announce(secid, date, title)`
- `news(secid, date, title, summary)`
- `params(scope, version, generated_at, payload_json)` —— 拟合参数归档
- `meta(key, value)` —— 库版本 / 末端日期 / 建库时间

当前规模（2026-08-23 建库）：183 只 / 178,182 行 K 线（2022-08-01 ~ 2026-08-20），
公告 39 只 / 21,409 条（`_announce_cache.json` 另有 87 只标的缓存为空，
需要时可跑 `_announce_fetch.py` 补抓）；exe 龙头池（101 只）覆盖 100/101
（`sz002450` 康得新已退市无数据）。

## 维护流程（smalltools 侧，命令）

```bash
# 一次性建库：导入 _kline_cache.json + _announce_cache.json (+ _news_cache.json 若存在)
python smalltools/_market_db.py --build

# 查看库统计
python smalltools/_market_db.py --stats

# 增量更新 K 线到最新交易日（自动写 JSON + SQLite）
python smalltools/_update_cache_inc.py

# 补齐 exe 龙头池缺失标的（新增股票进库）
python smalltools/_add_leaders.py

# 抓取新闻并落库 news 表
python smalltools/_news_fetch.py

# 三年 walk-forward 拟合（完成后自动归档各窗口参数到 params 表）
python smalltools/_walk_forward.py
```

## exe 侧使用

GUI 数据源下拉框新增 **sqlite**（`autoquant/data.py` 的 `SQLiteDataSource`）：

- 自动定位 `StockAnalysis/data/market_data.db`（无需 data_dir）
- symbol 格式全兼容：`601969.SH` / `000338_SZ` / `sh600519` / `000338`
- 周/月线自动从日线重采样
- 持仓评估等内部功能仍走 CSV，不影响

## 符号映射

| exe / 显示 | smalltools secid |
|---|---|
| `601969.SH` | `sh601969` |
| `000338_SZ` | `sz000338` |
| `sh600519` | `sh600519` |
| `000338`（短代码） | `sz000338` |

## 关键实现文件

- `smalltools/_market_db.py` —— 公共库模块（建表 / 导入 / 查询 / 参数归档 API）
- `smalltools/_update_cache_inc.py` —— 增量更新，同时写 JSON 与 SQLite
- `smalltools/_add_leaders.py` —— 龙头池补池（四年 K 线）
- `smalltools/_news_fetch.py` —— 新闻抓取，落库 news 表
- `smalltools/_walk_forward.py` —— 拟合后归档参数到 params 表
- `AutoQuant/autoquant/data.py` —— `SQLiteDataSource` / `DataFeed(source='sqlite')`
- `AutoQuant/autoquant/gui/app.py` —— 数据源下拉框 + worker 适配
