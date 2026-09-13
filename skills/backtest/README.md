# 量化回测 Skill (Quant Backtest)

## 用途

用真实行情（腾讯/东财日K）对选股策略做**盈利回测**与**参数拟合**，
验证超短/短/中/长四个周期在历史上的营收率表现，为调参提供数据依据。

**口径对齐**：所有脚本复刻 `Kotlin StockCheckPipeline.analyzeSnaps` 与四周期
`PARAMS`（超短/短/中/长），大盘方向用三指数（上证/深成/创业板）MA 排列 tripleVote。

## 目录

- 核心库：`smalltools/backtest_guangmo.py`（analyze_snaps / PARAMS / get_index_dir / fetch_kline）
- 完整分类索引：`smalltools/SCRIPTS.md`

## 脚本分类

### 1. 回测（营收率统计）

| 脚本 | 功能 | 用法 |
|------|------|------|
| `_profit_backtest.py` | 四周期独立回测，t选股→t+1开盘买入，止盈/止损/到期退出，无未来函数 | `python _profit_backtest.py > out_profit.txt` |
| `_year_backtest.py` | **一年窗口回溯**：中线选股日∈[1年前,3个月前]，长线∈[1年前,6个月前]，避免持仓跨到未来；含参数网格拟合 | `python _year_backtest.py > out_year.txt` |
| `_multiday_backtest.py` | 核心股票池多日逐日扫描，输出每日各周期应选到的股票 | `python _multiday_backtest.py > out_multiday.txt` |

### 2. 参数拟合

| 脚本 | 功能 | 用法 |
|------|------|------|
| `_optimize_short.py` | 短线持有天数/止盈/止损网格搜索最优组合 | `python _optimize_short.py > out_short.txt` |
| `_year_backtest.py --fit` | 中线/长线参数网格拟合（持有×止盈×止损） | `python _year_backtest.py > out_year.txt` |

### 3. 组合模拟 / 策略原型

| 脚本 | 功能 |
|------|------|
| `_portfolio_sim.py` | AutoTradePortfolioEngine 原型：100万资金模拟选股→建仓→做T→止盈止损→腾笼换鸟 |
| `_trend_proto.py` | 趋势跟随选股原型（牛市超短/短线） |

### 4. 数据抓取 / 网络诊断

| 脚本 | 功能 |
|------|------|
| `_extend_cache.py` | 扩展 `_kline_cache.json` 到 2023-01-01（长线回溯需要250日MA） |
| `_fix_cache_names.py` | 修复缓存中股票名称（is_cyclical_industry 依赖） |
| `_net_check.py` / `_netdiag*.py` | 腾讯/东财/新浪数据源连通性诊断 |
| `_idx_trend.py` / `_microcap_today.py` | 指数趋势 / 微盘跳水确认 |
| `_intraday_tencent.py` / `_intraday_today.py` | 分时5分钟K线，确认跳水时点 |

### 5. 当日分析 / 调试

| 脚本 | 功能 |
|------|------|
| `_analysis_today.py` | 当日大盘/微盘跳水诊断 |
| `_debug_verify.py` | 打印个股K线+MA，验证复刻逻辑与实盘一致 |

### 6. 可视化（另见 smalltools/README.md）

| 脚本 | 功能 |
|------|------|
| `pipeline_visualizer.py` / `generate_diagrams.py` | DAG Pipeline / 架构流程图 |

## 数据源

- 腾讯日K：`web.ifzq.gtimg.cn/appstock/app/fqkline/get`
- 东财日K：`push2his.eastmoney.com/api/qt/stock/kline/get`
- 缓存文件：`smalltools/_kline_cache.json`（默认 640 根/只）

## 回测口径（重要）

1. **无未来函数**：选股日 t 只用截至 t 收盘的历史K线；买入在 t+1 开盘价。
2. **退出规则**：持有期内最高价≥买价×(1+止盈%) → 止盈；最低价≤买价×(1+止损%) → 止损；否则持有到期收盘。
3. **周期窗口限制**（防止持仓跨越"现在"）：
   - 中线（持10天）：选股日 ∈ [1年前, 3个月前]
   - 长线（持20天）：选股日 ∈ [1年前, 6个月前]
4. **指标口径**：平均收益、胜率、累计（等权复利）、盈亏因子、最大回撤。

## 快速开始

```bash
cd smalltools
# 1. 扩展缓存历史（一次性，约2分钟）
python _extend_cache.py
# 2. 一年中线/长线回溯 + 拟合
python _year_backtest.py > out_year.txt
# 3. 四周期近期回测
python _profit_backtest.py > out_profit.txt
```
