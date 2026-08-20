# smalltools 脚本全量分类索引

> 本文件为 `smalltools/` 下所有 Python 脚本的分类索引。脚本大体分为
> **回测/拟合**、**数据抓取/网络诊断**、**当日分析**、**调试工具**、**可视化** 五类。
>
> 所有回测脚本的选股口径与 `Kotlin StockCheckPipeline.analyzeSnaps` 完全一致，
> 参数（超短/短/中/长）与 Kotlin companion 对齐，K线来自腾讯/东财真实数据。

---

## 1. 核心库（被其他脚本 import，不直接运行）

| 文件 | 说明 |
|------|------|
| `backtest_guangmo.py` | **唯一核心库**。复刻 `analyze_snaps` 均线粘合选股逻辑、四周期 `PARAMS`、指数方向 `get_index_dir`/`triple_vote`、K线抓取 `fetch_tencent`/`fetch_east`/`fetch_kline`。其他回测脚本均以 `from backtest_guangmo import ...` 引入。 |

---

## 2. 回测 / 拟合（策略验证）

| 文件 | 说明 | 典型用法 |
|------|------|---------|
| `_profit_backtest.py` | **四周期盈利回测**：超短/短/中/长线各自独立回测，无未来函数（t 选股 → t+1 开盘买入），含止盈/止损/到期三种退出。 | `python _profit_backtest.py > out_profit.txt` |
| `_year_backtest.py` | **一年回溯 + 参数拟合**：中线（选股日 2025-08-15~2026-05-15）与长线（2025-08-15~2026-02-15）窗口回溯营收率，并对持有天数×止盈×止损做网格拟合。 | `python _year_backtest.py > out_year.txt` |
| `_multiday_backtest.py` | **多日逐日扫描回测**：对核心股票池在最近几个交易日逐日扫描，输出每天每个周期应选到的股票（含大盘方向判断）。 | `python _multiday_backtest.py > out_multiday.txt 2> err_multiday.txt` |
| `_optimize_short.py` | **短线参数拟合**：对短线的持有天数/止盈/止损做网格搜索，找最优参数组合。 | `python _optimize_short.py > out_short.txt` |
| `_portfolio_sim.py` | **组合模拟**：AutoTradePortfolioEngine 原型验证，100 万资金模拟选股→建仓→做T→止盈止损→腾笼换鸟全流程。 | `python _portfolio_sim.py > out_portfolio.txt` |
| `_trend_proto.py` | **趋势跟随原型**：复刻 `trend_follow_scan`（超短/短线在牛市大盘下的趋势跟随选股）。 | `python _trend_proto.py` |
| `_profit_analyze.py` | **回测补充诊断**：信号时间分布、基准对比（超额 alpha）、周期交叉验证。 | `python _profit_analyze.py` |
| `_extend_cache.py` | **缓存扩展**：把 `_kline_cache.json` 的历史K线扩展到 2022-08-01 起（三年 walk-forward 需要 MA250）。腾讯单次 640 根上限，按 2022-08~2024-06 / 2024-06~2026-08 两段拉取拼接；东财接口可用时优先。 | `python _extend_cache.py` |
| `_walk_forward.py` | **三年期 walk-forward 月度滚动回溯+拟合（2023-08-15~2026-08-15，核心架构）**：36 个月度窗口逐月推进，每月「用上月拟合参数回溯本月（样本外）+ 用本月信号拟合下月（样本内）」，无未来函数；四周期均用**全部至今信号**拟合（由 `_refit_experiment.py` 对比实验确定全量最优）；增量续跑（`_records/selected_YYYY-MM.json` 持久化，已跑窗口自动跳过）；`--fit-lookback` 可覆盖拟合窗口。 | `python _walk_forward.py` / `python _walk_forward.py --months 3` |
| `_refit_experiment.py` | **拟合窗口对比实验**：复用 `_records` 已持久化信号，不重跑回溯扫描，对比「当前1/3月 vs 扩展3/6月 vs 全量三年」三种拟合窗口的样本外收益与参数抖动率（结果落盘 `_records/refit_fitted_cache.json`，中断可续跑）。结论：**全量三年拟合最优**（短线 +295% vs +254%、胜率 42.9% vs 39.9%、抖动 36.6% vs 38.4%）。 | `python _refit_experiment.py` / `--months 12` |
| `_factor_ic.py` | **选中信号因子 IC/ICIR 检验（Spearman）**：对四周期已选中信号提取粘合度/量比/跌幅/换手/近5日动量/MA乖离等因子，按月滚动算与未来收益的秩相关 IC → ICIR/IC>0占比，找最优排序因子（输出 `_records/factor_ic.json`）。结论：中线/长线反转效应（动量负 IC、跌幅正 IC），超短追涨（当日涨幅正 IC）。 | `python _factor_ic.py` / `--periods 中线,长线` |
| `_hindsight_report.py` | **事后诸葛亮分析**：每季度对中/长线输出「实际(walk-forward) vs 事后最优」收益差距，并对季度末交易日做全池漏选归因（事后牛股被哪些检查项挡掉），输出 `_records/hindsight_Q*.json`。 | `python _hindsight_report.py` |
| `_export_params.py` | **固化参数导出**：从 36 个月拟合历史提取各周期×各状态规则的**众数**（稳健优先），连同选股参数导出为 `app/src/main/assets/backtest_params.json`，APK 启动即代入，新用户无需导入多年K线；`--fit-cache` 从 `_refit_experiment.py` 全量拟合缓存导出（推荐，免重跑）。 | `python _export_params.py --fit-cache` |
| `cos_utils.py` | **COS 签名库（纯标准库）**：HMAC-SHA1 V5 签名 + 签名 GET/PUT 请求，与 App 端 `CosSigner.kt` 算法一致；读取 `app_config.json` 的 `cloud_sync` 配置。被 cloud_download / cloud_upload_params 复用。 | 不直接运行 |
| `cloud_download.py` | **COS 数据下载**：列出手机会话上传的 `phone_*.zip`，下载最近 N 个（`--all` 全下），解压 `data.json` 到 `_records/cloud/`，打印各表行数摘要；`--merge` 汇总为 `cloud_export.json`；`--params` 顺带把 COS 上的 `backtest_params.json` 拉回 assets。 | `python cloud_download.py --max 7` |
| `cloud_upload_params.py` | **参数回流上传**：把 PC 拟合好的 `backtest_params.json` 签名 PUT 到 COS `params_key`，手机端「设置→云端数据同步→下载最新拟合参数」导入立即生效；`--public-read` 可选设公有读。 | `python cloud_upload_params.py --file xxx.json` |
| `verify_cos_sign.py` | **COS 签名回归校验**：用腾讯云官方文档完整示例值离线校准 HMAC-SHA1 V5 签名，并自检配置读取 / ZIP 解包 / ListObjectsV2 XML 解析。改动签名相关代码后建议重跑。 | `python verify_cos_sign.py` |

---

## 3. 数据抓取 / 网络诊断

| 文件 | 说明 |
|------|------|
| `_net_check.py` | 单票多数据源连通性测试（腾讯/东财/新浪）。 |
| `_netdiag.py` | 数据源网络诊断（首版）。 |
| `_netdiag2.py` | 数据源网络诊断：腾讯 kline/proxy 对比。 |
| `_netdiag3.py` | 数据源网络诊断：股票/指数 640 根拉取对比。 |
| `_netdiag4.py` | 数据源网络诊断：批量实时报价解析。 |
| `_idx_trend.py` | 主要指数趋势方向（上证/深成/创业板/中证1000 等 MA 排列）。 |
| `_microcap_today.py` | 微盘/小盘指数 + 今日涨跌家数（确认微盘跳水）。 |
| `_intraday_tencent.py` | 腾讯分时(5分钟)K线，确认当日跳水时点。 |
| `_intraday_today.py` | 东财分时(5分钟)K线，确认当日跳水时点。 |
| `_fix_cache_names.py` | 用腾讯实时接口批量解析股票名称，修复 `_kline_cache.json` 的 `name` 字段（无需重拉K线）。 |

---

## 4. 当日盘面分析

| 文件 | 说明 |
|------|------|
| `_analysis_today.py` | 当日大盘/微盘跳水诊断 + 回测验证（指数、涨跌家数、核心票）。 |

---

## 5. 调试工具

| 文件 | 说明 |
|------|------|
| `_debug_verify.py` | 打印个股最近 K 线明细与 MA5/10/20/60，验证复刻逻辑与实盘一致。 |

---

## 6. 可视化（另见 README.md）

| 文件 | 说明 |
|------|------|
| `pipeline_visualizer.py` | DAG Pipeline XML 可视化。 |
| `generate_diagrams.py` | 架构/四周期对比流程图生成。 |

---

## 7. 数据文件（非脚本）

| 文件 | 说明 |
|------|------|
| `_kline_cache.json` | 核心股票池日K缓存（腾讯/东财抓取，2023 年起），回测脚本的数据源。 |

---

## 8. 输出文件（非脚本）

- `out_*.txt` / `err_*.txt` / `o*.txt` / `e*.txt`：各脚本 stdout/stderr 重定向结果，可随时删除。

---

## 快速上手

```bash
# 1. 先确保缓存有足够历史（长线需要 250 根）
python _extend_cache.py

# 2. 四周期盈利回测（近期）
python _profit_backtest.py > out_profit.txt

# 3. 一年中线/长线回溯 + 参数拟合
python _year_backtest.py > out_year.txt
```
