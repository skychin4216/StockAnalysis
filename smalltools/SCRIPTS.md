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
| `cos_utils.py` | **COS 签名库（纯标准库）**：HMAC-SHA1 V5 签名 + 签名 GET/PUT 请求（禁用系统代理直连），与 App 端 `CosSigner.kt` 算法一致；配置加载顺序：AutoQuant `cloud_config.json`（支持 `secret_enc` 解密，主密钥来自 `ai_keys.properties`）→ 环境变量 `COS_*` → `app_config.json` 的 `cloud_sync`。被 cloud_download / cloud_upload_params / cloud_upload_market_db 复用。 | 不直接运行 |
| `cloud_download.py` | **COS 数据下载**：列出手机会话上传的 `phone_*.zip`，下载最近 N 个（`--all` 全下），解压 `data.json` 到 `_records/cloud/`，打印各表行数摘要；`--merge` 汇总为 `cloud_export.json`；`--params` 顺带把 COS 上的 `backtest_params.json` 拉回 assets；`--list-all` 列出桶上全部对象并核对参数/市场库是否已上传。 | `python cloud_download.py --max 7` / `python cloud_download.py --list-all` |
| `cloud_upload_params.py` | **参数回流上传**：把 PC 拟合好的 `backtest_params.json` 签名 PUT 到 COS `params_key`，手机端「设置→云端数据同步→下载最新拟合参数」导入立即生效；`--public-read` 可选设公有读。 | `python cloud_upload_params.py --file xxx.json` |
| `cloud_upload_market_db.py` | **市场库上传**：把 PC 端公共市场库 `data/market_data.db`（K线+公告）签名 PUT 到 COS `stockanalysis/db/market_data.db`，供 APK「设置→下载云端行情库」导入 Room（免逐日联网拉取）；默认保留时间戳副本，`--no-timestamp` 关闭。 | `python cloud_upload_market_db.py` |
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
| `_overseas_fetch.py` | **外围历史日K采集**（A股跨境 ETF 代理：纳指100/中韩半导体/标普500，腾讯 fqkline 稳定返回 800 根；东财/雅虎/stooq 均被断或反爬故走代理路线），产出 `_overseas_cache.json`，供隔夜外围因子回测/选股。`--check` 仅查状态。 |
| `_overseas_factor.py` | **隔夜外围因子**（韩股/纳指前一日大跌 → A股开盘降权/禁开仓）：双代理加权（纳指0.6+韩股0.4），soft/hard 双阈值，默认只影响超短/短线。回测结论：全局禁开仓误杀长线（-1898pp），仅禁短周期胜率+5.2pp。 |
| `_session_factor.py` | **时段因子**（盘中实时，需当日分时）：早盘 9:30~10:00 下杀≥-1.5% 且 10:00~10:30 企稳回升≥+0.5% → 可介入；14:30 后 14:00 起拉升≥+1.0% → 尾盘勿追高。腾讯当日分时接口，历史分钟线受限故仅实时过滤。 |
| `_daily_active_pool.py` | **每日活跃池发现（开盘前轻量流程，替代每天全市场 56 页快照）**：用 6 个 1 页榜单请求（成交额/换手/涨幅 Top100 + 近20日板块榜）捕获当日市场焦点；产出 `data/_daily_hot.json` + `data/_daily_hot_history.json`（多日上榜累计=市场焦点股）；与核心池对比输出"池外新晋活跃候选"，并标注小市值炒作焦点（≤150亿且高换手，震荡期炒的永远是榜前排）；`--fetch-candidates` 可把候选 K 线补入 market_data.db（每日只几只，轻量）。 | `python _daily_active_pool.py` |
| `_market_snapshot.py` | **全市场市值快照 + 板块分层清单（低频：每周校准/换池时运行）**：抓全市场 4600+ 只（56 页）→ 分层清单按行业板块输出龙头/大票/小票；`--export-small` 导出 20~120亿+低价小市值分析池（轮动规律统计的候选层）。市值几天不变，勿每日运行。 | `python _market_snapshot.py --skip-snapshot --export-small ..\data\_small_pool.json` |
| `_sector_fundflow.py` | **板块资金流实时数据源（东财 push2delay 当日实时）**：全行业板块主力净流入 Top/净流出排行；设计为轮动引擎的"当日候选验证层"——板块动量候选出来后用资金流二次过滤/排序（资金流无法历史回放）。 | `python _sector_fundflow.py --names 半导体,通信设备` |
| `_market_context.py` | **盘中市场上下文聚合（`_publish_candidates.py` 的共振数据层，15 分钟守护用）**：东财实时板块资金流 + ETF 资金走向 + 日/周/月热门榜单 + Android 实仓镜像 + exe screen_report；`resonance_for()` 给候选算多因子共振分(资金/轮动/热度/ETF/连续上榜)，全部 TTL 缓存+容错降级。 | `python _market_context.py` / `python _market_context.py --flow 半导体` |
| `_industry_leader_map.py` | **行业龙头图谱生成器（活跃赛道→核心龙头, 可每日跑）**：精选 45 个赛道目录(半导体产业链/PCB/MLCC/光通信/新能源/有色贵金属/创新药等, 脚本内 TRACK_GROUPS 可增删) → 东财行业板块全量榜(496个)匹配当日行情 → blend 活跃度(近20日动量50%+当日30%+主力资金20%)动态圈定 Top20 → 每赛道成分按总市值取龙头 Top3 + 人气领涨(代码+名称+市值+涨幅)。产出 `data/_industry_leader_map.json` + `.md` 表格。`--mode day/flow/momentum` 换口径, `--all` 全赛道取龙头。 | `python _industry_leader_map.py` |
| `_etf_holdings.py` | **核心 ETF 重仓股跟踪 →「低位埋伏」候选池（2026-09-06 新增, 重仓季报级低频/行情实时）**：27 只核心指数 ETF 白名单(6宽基+21行业, 脚本 THEME_RULES 板块关键词↔ETF 可增删) → 东财基金移动端 F10 前十大重仓(实测可用, fundf10 HTML 版 404 弃用) → 落盘 `data/_etf_holdings.json`(funds 明细 + stocks 覆盖矩阵: 每只股票被哪些 ETF 持有/n/合计暴露 sum_ratio/距60日高点回撤 pos60)。行情源腾讯(东财 push2 被断)。`--low-buy 半导体,券商` 读缓存+实时行情出低吸观察；被 `_publish_candidates` 每轮推送 ⑥ 段与 candidates.json 顶层 `etf_holdings` 摘要调用。 | `python _etf_holdings.py` / `python _etf_holdings.py --no-kline` / `python _etf_holdings.py --low-buy 半导体,券商` |
| `_etf_buy.py` | **ETF 专买择时算法 v0.3（2015→今 全历史回溯拟合, 2026-09-06）**：拉 2015 前成立的 13 只宽基/行业 ETF 全历史 qfq 日K(腾讯 fqkline 翻页) → `_etf_cache.json`。信号体系(突破/低吸/反转/均线) + **单仓状态机(同标的单笔)+信号冷却30天+大盘结构多头门控(沪深300 MA20>MA60)**。v0.3 dip_buy 低吸 = 回撤-25~-12% + RSI6<30 + 年线上方 + **收阳/RSI6拐头 + 非5日新低(止跌确认, 失败单9/9为下跌中继→排除)**；冷门/热门分层与右侧企稳确认经 v95 系列实验证伪(左侧先手最优)。离场发布默认 止盈+2%/止损-6%/30日。**实测: IS 44笔 81.8%+66.7% / OOS 11笔 100%+37.1% 回撤0 / FULL 55笔 85.5%+128.5%(仅2021抱团瓦解年75%)**。拟合=信号 maxmin(IS前/后段) 稳健选择，OOS 切片+分标的落 `data/_etf_fit_result.json`。**`--live` 输出 `data/_etf_live_picks.json`(今日可低吸/接近观察池/大盘门控)—— exe「🧲 ETF低位」tab 与 APK 工作台同源消费(APK 走 exe data_service GET /etf_live)。诚实声明: 规则已达 5~6 个过滤器后的过拟合边缘(v9/v10 实验证伪继续加规则), OOS 100% 样本仍小。 | `python _etf_buy.py` (自动断点续拉) / `--fetch-only` / `--live` 实盘名单 / `--codes sh510300,sz159915` / `--no-gate` 对照 |
| `_holder_signals.py` | **社保/国家队/北向(中央结算) 持仓信号采集 + 全史披露日历（2026-09-06 新增, 正式工具）**：东财 `RPT_F10_EH_FREEHOLDERS` 十大流通股东历史（可回溯 2008, 季度粒度）→ 按股东名筛 社保/养老、中央汇金/证金、香港中央结算(北向口径；2024-08 后港交所停发北向逐股日频, 故用季度口径保证历史一致)。`--build-hist` 产出 `data/_holder_hist.json`（社保/北向逐季 {end,notice,ratio,state}，按 NOTICE_DATE≤信号日 过滤可做无未来函数共振层拟合；2026-09-06 全窗口定稿见 `data/_etf_top5_fit_resonance.json`，结论: ds∩北向增持 56.7%/PF1.67 为最高但样本仅 411、无层达 80%）。摘要=最新季社保新进/加仓、北向连续两季增持 TOP+行业分布、最早建仓时点。落盘 `data/_holder_signals.json`。 | `python _holder_signals.py`（断点续跑）/ `--build-hist` |

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
| `inspect_market_db.py` | **市场库结构检查**（由 `_tmp_dbschema.py` 转正）：打印 `data/market_data.db` 的表结构、行数、索引与 K线/公告概览；`--path` 指定其他库，`--full` 打印样例数据。 | `python inspect_market_db.py` |
| `verify_market_db.py` | **市场库端到端验证**（由 `_tmp_dbverify.py` 转正）：K线 vs `_kline_cache.json` 一致性抽检、参数 save/get 往返、新闻导入、公告抽样、库统计。云端同步 DB 前后各跑一遍。 | `python verify_market_db.py` |
| `_verify_9grid.py` | **12 格 usecase 矩阵三端一致性校验（engine-sync 链路正式工具）**：`usecase_matrix.json` 12 格 sell（PC 扫描拟合产物）↔ `backtest_params.json` sell_rules（APK 读取源）↔ `sell_rule_for()`（exe walk-forward 状态路由）必须一致，防矩阵/参数改版漂移。 | `python _verify_9grid.py` |
| `_verify_regex.py` | **模板表达式解析正则镜像校验**：Pipeline XML `if` 条件 `${nodeId}.fieldName` 解析复刻 APK `UseCaseLoader.evalStepIf` 正则（兼容花括号内/外两种写法），改条件解析后重跑。 | `python _verify_regex.py` |
| `_verify_parity.py` | **app(Kotlin) vs PC(python) 推理一致性验证**（KNN `ml_knn_long.json`、icRank 排序、特征口径）——已迁移转正 → `AutoQuant/verify_parity.py`（数据源改 AutoQuant `data/cache` CSV），本文件保留留档。 |（迁移至 AutoQuant/verify_parity.py） |
| `_verify_layers.py` | **一次性验证归档**（2026-09-03 用户确认留档，勿当正式流程运行）：缓存池 vs 全市场快照 vs 分层清单一致性；分层清单正式产出在 `_market_snapshot.py`。 |（归档，勿运行） |

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
