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
| `_market_db.py` | **市场库（`data/market_data.db`）读写核心库**：K线/参数/新闻/公告/大盘状态 + 每日节奏留痕 `intel_report`（08:00/09:00/复盘情报）与 `push_record`（推送账本）。被守护、选股、复盘链路 import，不直接运行。新增 API：`save_intel_report`/`query_intel_report`/`latest_intel`、`save_push_record`/`query_push_records`。 | 不直接运行 |
| `_table_csv.py` | **分段表格 → 单文件 CSV → 统一长截图（2026-09-12 新增核心库，被 `_publish_candidates` / `_daily_intel` import）**：把推送/复盘的各段表（`{"title","rows"}` 或 `{"title","header","body"}`，可混用）先「填充到一份 CSV」（首列『分段』标段、各段保留自己的表头 → Excel 直接按分段筛选），再由这份 CSV 统一渲染成一张长图 —— **图与 CSV 内容严格一致**，原始 CSV 可直接经 `push_channel.send_file` 推送（企微 file 消息）。`export()` 一步出 CSV+PNG；PNG 超 2MB 自动逐档降 dpi（配套 `_table_img.render_multi_table(dpi=)`）。CSV 写盘前统一过 `_table_img._clean`（emoji → 「新·/封板·/流出·」等效文字），保证 CSV 与长图逐格严格一致。 | `python _table_csv.py`（自检样例） |
| `_table_xlsx.py` | **同口径 XLSX 导出（2026-09-12 新增，配套 CSV）**：把推送/复盘的各段表用 `openpyxl` 写出 Excel 工作簿，**所有单元格统一左对齐 + 全部按文本写入**（股票代码 `000960` 前导 0 不会被 Excel 吃掉）；与 `_table_csv.normalize` 同一入参 → 三份产物（PNG 长图 / XLSX 对齐 / CSV 原始）内容严格一致。被 `_publish_candidates.send_wechat_round` 与 `_daily_intel.push_review` 在生成 CSV/PNG 时同步调用。 | `python _table_xlsx.py`（自检样例） |

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
| `_update_cache_inc.py` | **日线缓存增量更新（守护盘段首刷 / 收盘后补当日根，核心数据链路）**：v3 起收盘后日常补「当日根」走**批量实时快路径**（腾讯 qt.gtimg.cn 30只/请求，224只全池 8 请求 ≈20s；对比逐只日K `--force` 1058s）；缺口>1日 / 历史回填 / `--force` 走并发日K（workers=30，多源分组 `_multi_source` 轮换，单源熔断迁移）。同步写 `market_data.db` 保持 exe/smalltools 单源。**v5（2026-09-10）决策：废弃「ETA>5min → T-1 预筛只拉幸存池」降级** —— 全史回放 4514 交易日实测漏杀 41.3%（详见 `_history_dag_run.py`），预筛判死与「低位埋伏/深跌」买点天然冲突；盘段任何时候都拉全池，宁可本轮晚、不可丢票，`_maybe_restrict_codes` 现仅做 ETA 观测告警。**v6（2026-09-12）逐只缺口自愈（危险信号修复）**：旧逻辑只看「全池最大日期」——整体已是最新就直接 `return 无需更新`，个股缺口永远不会自愈。实测 2026-09-12 全池 243 只里 sh600011 停在 9/8、sh600105/sh600111 停在 9/10，每天跑增量都报「已是最新」被静默吞掉。现改为 `_stale_codes()` 逐只比对目标交易日，落后即 `_backfill_stale()` 并发补拉（批量快路径后也追加一次补漏），并打印「仍落后」清单待人工确认。配套修 `_multi_source.fetch_one_qfq` 默认 `max_attempts=None`（遍历全部健康源）——旧默认 4 < 源总数 5，东财全挂时静态分组排末位的腾讯源永远轮不到。 | `python _update_cache_inc.py` / `--force`(修正盘中污染当日K) / `--no-batch` / `--workers N` |
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
| `dip_rebound_stat.py` | **大盘抄底信号统计**：连跌 D 天后反弹概率、下跌中十字星(分歧)→大阴线(恐慌)信号后指数反弹、恐慌日个股回弹（热门前30% vs 冷门 vs 自身连跌），输出 `AutoQuant/backtest_logs/_dip_rebound_report.md`。 | `python dip_rebound_stat.py` |
| `dip_crossstar_stat.py` | **十字星分歧四形态短线统计（2008-01 起）**：把 18 年来每次「星→大阴/小阴/大阳/小阳」（星后 1..7 日最先形态）固化到 market_data.db `star_form_events` 表（每次运行按指数幂等重建），再做选股层统计：收盘持有(对照) + 盘中逃顶速查(核心口径) + 止盈纪律模拟(冲高≥+2%即卖否则第H日收盘)。输出 `AutoQuant/backtest_logs/_dip_crossstar_report.md`。 | `python dip_crossstar_stat.py` |
| `_history_dag_run.py` | **全史逐日真实 XML DAG 跑单 + T-1 单调判死预筛覆盖验证（2026-09-10, 主线口径）**：market_data.db 2008-01-02 起 4543 交易日逐日真实跑 XML DAG 主线(四周期) 出历史每日订单；同日用 T-1 数据跑预筛，全史验证「下载 >5min 降级只拉幸存池」不漏杀（漏杀=0 是硬指标）并统计每日幸存/压缩率。正确性：实测引擎部分节点不按 asof 过滤(喂全史缓存会泄漏未来) → 采用逐日增量生长截断缓存(每票指针推进 append, 内容恒=截至该日真值)，`--verify` 抽样日 vs 独立重建全等 PASS。断点续跑 `AutoQuant/data/history_dag_run.jsonl`+.meta。**❌ 结论（2026-09-10 全史 4514 日跑完实测）**：漏杀票 **20689 / 50126 单 = 41.3%**，漏杀日 2560/4514(57%)，平均压缩仅 59.9% → **「下载>5min 降级只拉幸存池」方案不成立**。TOP 原因全为深跌型（`MA20<MA250*0.95 且收于年线下`×1615、`均线深度空头 ma5<ma20*0.95` 系等），即 T-1 单调判死会杀掉「低位埋伏/深跌」通道本来要买的票；四周期均匀中招（超短3690/短线5692/中线5665/长线5642）。**决策（2026-09-10 用户拍板）：彻底放弃该降级方案** —— `_update_cache_inc.py` 的预筛缩池逻辑已移除，改走「当日根批量快路径全池(≈20s) + 历史缺口留到非盘段」。 | `python _history_dag_run.py --verify` / 挂机直跑 / `--limit 20` / `--summarize` |
| `_stabilize_dip_stat.py` | **连跌后企稳低吸统计（2026-09-08 新增, 正式工具）**：晋控能源型「连跌多天后 3 日不新低+低点连续上浮 但不满均线粘合」候选 → 信号日收盘买入 H=1/2/3/5/10 日胜率/均值；企稳判定 = `usecase_pipeline._is_stabilize` 同口径；粘合 = analyze_snaps ①（短/超短 MA5/10/20≤3% 牛×1.5 熊×0.8 下限1.0，另含 MA60 中长参考口径）；大盘牛/震荡/熊三分、分年度、含数据守卫(剔除>25%跳变/长停牌)。结论: 企稳非粘合相对任意日基准无显著胜率优势(H1 48% vs 48%, H3 49% vs 49%)，不建议放宽粘合硬门槛，低吸应走有资金/板块共振的专用通道。输出 `AutoQuant/backtest_logs/_stabilize_dip_report.md`。 | `python _stabilize_dip_stat.py` |

---

## 3. 数据抓取 / 网络诊断

| 文件 | 说明 |
|------|------|
| `_net_check.py` | 单票多数据源连通性测试（腾讯/东财/新浪）。 |
| `_netdiag.py` | 数据源网络诊断（首版）。 |
| `_netdiag2.py` | 数据源网络诊断：腾讯 kline/proxy 对比。 |
| `_netdiag3.py` | 数据源网络诊断：股票/指数 640 根拉取对比。 |
| `_netdiag4.py` | 数据源网络诊断：批量实时报价解析。 |
| `_multi_source.py` | **多源分组行情抓取层（2026-09-10, 下载核心）**：股票按 secid 哈希分 ABC 组静态绑定健康 qfq 源（东财多 host + 腾讯 ifzq），单源连续失败熔断冷却 + 故障组自动迁移到健康源——单域名全挂只影响该组数秒。口径：历史缺口只准 qfq 源（复权序列一致），未复权实时(qtg批量/sina)仅收盘后兜底当日根（XD/prev_close 校验由 `_update_cache_inc` 落盘侧把关）。被 `_update_cache_inc` v4 与 `usecase_screen.py --snapshot` 复用。 | `python _multi_source.py`（6只自检+源健康报告） |
| `_idx_trend.py` | 主要指数趋势方向（上证/深成/创业板/中证1000 等 MA 排列）。 |
| `_microcap_today.py` | 微盘/小盘指数 + 今日涨跌家数（确认微盘跳水）。 |
| `_intraday_tencent.py` | 腾讯分时(5分钟)K线，确认当日跳水时点。 |
| `_intraday_today.py` | 东财分时(5分钟)K线，确认当日跳水时点。 |
| `_fix_cache_names.py` | 用腾讯实时接口批量解析股票名称，修复 `_kline_cache.json` 的 `name` 字段（无需重拉K线）。 |
| `_overseas_fetch.py` | **外围历史日K采集**（A股跨境 ETF 代理：纳指100/中韩半导体/标普500，腾讯 fqkline 稳定返回 800 根；东财/雅虎/stooq 均被断或反爬故走代理路线），产出 `_overseas_cache.json`，供隔夜外围因子回测/选股。`--check` 仅查状态。 |
| `_overseas_factor.py` | **隔夜外围因子**（韩股/纳指前一日大跌 → A股开盘降权/禁开仓）：双代理加权（纳指0.6+韩股0.4），soft/hard 双阈值，默认只影响超短/短线。回测结论：全局禁开仓误杀长线（-1898pp），仅禁短周期胜率+5.2pp。2026-09-10 增 `live_dual()/check_live_dual()`：日元+费半双定向（实时读 `_macro_sentinel`），历史 asof 口径不变。 |
| `_macro_sentinel.py` | **四根宏观哨兵**（2026-09-10）：①10Y美债>5%或30Y>5.5% → 全球成长降权、A股科技进攻仓位收敛；②USDJPY<148 → carry unwind、当日科技风险仓降配；③WTI 站稳$100 达 5 交易日 → 油链/油服加配、科技减配；④费半隔夜≤-3% → A股第一层供应链(光模块/PCB/半导体设备)开盘不追高、等恐慌低吸。源：FRED DGS10/DGS30 + 东财 119.USDJPY + 新浪 hf_CL + 新浪 gb_$sox；缓存 `_macro_sentinel_cache.json`（含 WTI 每日收盘自积累判「站稳一周」）。内嵌晨报/午间/收盘总结，盘中新越阈随 `_market_scan` 推送一次（持续越阈不刷屏）。`--check` 输出状态 json。 | `python _macro_sentinel.py` |
| `_session_factor.py` | **时段因子**（盘中实时，需当日分时）：早盘 9:30~10:00 下杀≥-1.5% 且 10:00~10:30 企稳回升≥+0.5% → 可介入；14:30 后 14:00 起拉升≥+1.0% → 尾盘勿追高。腾讯当日分时接口，历史分钟线受限故仅实时过滤。 |
| `_daily_active_pool.py` | **每日活跃池发现（开盘前轻量流程，替代每天全市场 56 页快照）**：用 6 个 1 页榜单请求（成交额/换手/涨幅 Top100 + 近20日板块榜）捕获当日市场焦点；产出 `data/_daily_hot.json` + `data/_daily_hot_history.json`（多日上榜累计=市场焦点股）；与核心池对比输出"池外新晋活跃候选"，并标注小市值炒作焦点（≤150亿且高换手，震荡期炒的永远是榜前排）；`--fetch-candidates` 可把候选 K 线补入 market_data.db（每日只几只，轻量）。 | `python _daily_active_pool.py` |
| `_market_snapshot.py` | **全市场市值快照 + 板块分层清单（低频：每周校准/换池时运行）**：抓全市场 4600+ 只（56 页）→ 分层清单按行业板块输出龙头/大票/小票；`--export-small` 导出 20~120亿+低价小市值分析池（轮动规律统计的候选层）。市值几天不变，勿每日运行。 | `python _market_snapshot.py --skip-snapshot --export-small ..\data\_small_pool.json` |
| `_sector_fundflow.py` | **板块资金流实时数据源（东财 push2delay 当日实时）**：全行业板块主力净流入 Top/净流出排行；设计为轮动引擎的"当日候选验证层"——板块动量候选出来后用资金流二次过滤/排序（资金流无法历史回放）。 | `python _sector_fundflow.py --names 半导体,通信设备` |
| `_market_context.py` | **盘中市场上下文聚合（`_publish_candidates.py` 的共振数据层，15 分钟守护用）**：东财实时板块资金流 + ETF 资金走向 + 日/周/月热门榜单 + Android 实仓镜像 + exe screen_report；`resonance_for()` 给候选算多因子共振分(资金/轮动/热度/ETF/连续上榜)，全部 TTL 缓存+容错降级。 | `python _market_context.py` / `python _market_context.py --flow 半导体` |
| `_industry_leader_map.py` | **行业龙头图谱生成器（活跃赛道→核心龙头, 可每日跑）**：精选 45 个赛道目录(半导体产业链/PCB/MLCC/光通信/新能源/有色贵金属/创新药等, 脚本内 TRACK_GROUPS 可增删) → 东财行业板块全量榜(496个)匹配当日行情 → blend 活跃度(近20日动量50%+当日30%+主力资金20%)动态圈定 Top20 → 每赛道成分按总市值取龙头 Top3 + 人气领涨(代码+名称+市值+涨幅)。产出 `data/_industry_leader_map.json` + `.md` 表格。`--mode day/flow/momentum` 换口径, `--all` 全赛道取龙头。 | `python _industry_leader_map.py` |
| `_etf_holdings.py` | **核心 ETF 重仓股跟踪 →「低位埋伏」候选池（2026-09-06 新增, 重仓季报级低频/行情实时）**：27 只核心指数 ETF 白名单(6宽基+21行业, 脚本 THEME_RULES 板块关键词↔ETF 可增删) → 东财基金移动端 F10 前十大重仓(实测可用, fundf10 HTML 版 404 弃用) → 落盘 `data/_etf_holdings.json`(funds 明细 + stocks 覆盖矩阵: 每只股票被哪些 ETF 持有/n/合计暴露 sum_ratio/距60日高点回撤 pos60)。行情源腾讯(东财 push2 被断)。`--low-buy 半导体,券商` 读缓存+实时行情出低吸观察；被 `_publish_candidates` 每轮推送 ③④ 段与 candidates.json 顶层 `etf_holdings` 摘要调用。**2026-09-12 统一表格改版新增三个推送端 API**：`low_buy_picks()` / `fresh_up_picks()` 返回原始 `[(ETF短名, pk)]`（取代原 `low_buy_rows`/`fresh_up_rows` 的「预格式化表行」，口径复用 `_lowbuy_split` 不变，行格式化交推送端统一成 16 列）、`_pk_tech_cells(pk)`（7 格技术列单一实现，文本表与统一表共用）、`_snaps_live(code)`（与 `_meta_live` 同一次拉取的日K，供趋势图谱/星后形态/趋势图/距60日高/今日）、`etf_tag_map()`（`{6位代码: 所属ETF}` 公共列，行业ETF优先/宽基垫底、30 分钟缓存）+ **`industry_to_etf(industry)`**（板块关键词回填：股票不在 ETF 覆盖矩阵时按东财行业名匹配 THEME_RULES 回填对应 ETF）。 | `python _etf_holdings.py` / `python _etf_holdings.py --no-kline` / `python _etf_holdings.py --low-buy 半导体,券商` |
| `_etf_publish.py` | **ETF 低位 usecase 发布 + 手机行情推送（2026-09-07 新增, 正式工具）**：执行 XML 单一源 `app/src/main/assets/usecases/etf_dip_usecase.xml`（Python 引擎 `app/src/main/assets/usecases/usecase_pipeline.py`，XML DAG 主线、与 XML 同居单一事实源，= APK UseCaseLoader 同 XML）→ 发布 `data/_etf_live_picks.json` → adb 把 `_etf_cache.json` 推送手机（App 本地跑同一 usecase，彻底不依赖 PC）。盘段守护 15:12 自动执行一次；`--all` 含行情刷新(腾讯 qfq)。 | `python _etf_publish.py` / `--push`(发布+推手机) / `--all`(刷新行情+发布+推送) / `--fetch`(仅刷新行情) | 
| `_ambush_publish.py` | **板块埋伏 usecase 发布（2026-09-11 新增, 正式工具，仿 ETF 低吸双端对齐）**：执行 XML 单一源 `app/src/main/assets/usecases/sector_ambush_usecase.xml`（Python 引擎同 `_etf_publish.py`，与 APK 工作台「埋伏」Tab / `SectorAmbushFragment` 解析同一份 XML）→ 读 `smalltools/_kline_cache.json` → 发布 `data/_ambush_live_picks.json`（PC/exe 展示）。APK 端本地执行 usecase（本地快照 + `sector_daily_record`），PC 端由本脚本产出同构 JSON。`--fetch` 先增量刷新 `_kline_cache.json`。 | `python _ambush_publish.py` / `--fetch`(刷新行情+发布) | 
| `_etf_buy.py` | **ETF 专买择时算法 v0.3（2015→今 全历史回溯拟合, 2026-09-06）**：拉 2015 前成立的 13 只宽基/行业 ETF 全历史 qfq 日K(腾讯 fqkline 翻页) → `_etf_cache.json`。信号体系(突破/低吸/反转/均线) + **单仓状态机(同标的单笔)+信号冷却30天+大盘结构多头门控(沪深300 MA20>MA60)**。v0.3 dip_buy 低吸 = 回撤-25~-12% + RSI6<30 + 年线上方 + **收阳/RSI6拐头 + 非5日新低(止跌确认, 失败单9/9为下跌中继→排除)**；冷门/热门分层与右侧企稳确认经 v95 系列实验证伪(左侧先手最优)。离场发布默认 止盈+2%/止损-6%/30日。**实测: IS 44笔 81.8%+66.7% / OOS 11笔 100%+37.1% 回撤0 / FULL 55笔 85.5%+128.5%(仅2021抱团瓦解年75%)**。拟合=信号 maxmin(IS前/后段) 稳健选择，OOS 切片+分标的落 `data/_etf_fit_result.json`。**`--live` 输出 `data/_etf_live_picks.json`(今日可低吸/接近观察池/大盘门控)。诚实声明: 规则已达 5~6 个过滤器后的过拟合边缘(v9/v10 实验证伪继续加规则), OOS 100% 样本仍小。**2026-09-07 起发布口径改由 XML 单一源驱动：规则/参数见 `app/src/main/assets/usecases/etf_dip_pipeline.xml`（门控→信号→离场），exe/APK 双端读同一 XML 执行——APK 本地跑（行情用推送的 etf_cache.json），PC 桥仅作回退；本文件退为“规则定义源 + 行情抓取(ensure_data)”。 | `python _etf_buy.py` (自动断点续拉) / `--fetch-only` / `--live` 实盘名单 / `--codes sh510300,sz159915` / `--no-gate` 对照 |
| `_holder_signals.py` | **社保/国家队/北向(中央结算) 持仓信号采集 + 全史披露日历（2026-09-06 新增, 正式工具）**：东财 `RPT_F10_EH_FREEHOLDERS` 十大流通股东历史（可回溯 2008, 季度粒度）→ 按股东名筛 社保/养老、中央汇金/证金、香港中央结算(北向口径；2024-08 后港交所停发北向逐股日频, 故用季度口径保证历史一致)。`--build-hist` 产出 `data/_holder_hist.json`（社保/北向逐季 {end,notice,ratio,state}，按 NOTICE_DATE≤信号日 过滤可做无未来函数共振层拟合；2026-09-06 全窗口定稿见 `data/_etf_top5_fit_resonance.json`，结论: ds∩北向增持 56.7%/PF1.67 为最高但样本仅 411、无层达 80%）。摘要=最新季社保新进/加仓、北向连续两季增持 TOP+行业分布、最早建仓时点。落盘 `data/_holder_signals.json`。 | `python _holder_signals.py`（断点续跑）/ `--build-hist` |
| `_inst_holdings.py` | **机构持续加仓判定数据层（2026-09-12 新增, 正式工具；规则出处《机构持续加仓自动选股池.txt》）**：东财 `RPT_MAIN_ORGHOLD`（机构持股一览表, 含 HOLDCHA 增减仓 + HOLDCHA_RATIO 环比）+ `RPT_HOLDERNUMLATEST`（股东户数）→ 分页全量抓取（gzip 原始缓存 `data/_inst_raw.json.gz` + `--reuse` 免重复联网）→ `judge_series()` 纯函数判定：口径A(连续≥2期环比增持>0.5pp)/口径B(全程净增持>1pp)、ins 机构加仓分 + conc 集中度分 → score=0.6ins+0.4conc → **A 精选**(口径A且≥75) / **B 观察**((A或B)且≥50) / **C 散户票**(其余)。产物 `data/institutional_cache.csv`(code,name,period,hold_ratio,hold_ratio_change,n_funds) + `data/_inst_holdings.json`(全市场 stocks 摘要 + A/B 明细 pool；镜像 `app/src/main/assets/data/_inst_holdings.json`) —— 该 json 是 usecase `inst_holding`（PC `usecase_pipeline.py` / APK `InstHoldingNodes.kt`）判定的**单一事实源**，双端只读不算。★ 机构季报滞后 1-3 月，只做中长线定性、非实时信号。 | `python _inst_holdings.py --periods 3`（真实抓取）/ `--mock`（自检）/ `--reuse`（复用 raw 缓存）/ `--csv-only` / `--update-pool "600519,300308"` |
| `_trade_workbench.py` | **《交易决策工作台》Excel —— 三系统合并（2026-09-12 新增, 正式工具）**：跑 XML 单一源 `app/src/main/assets/usecases/inst_holding_pipeline.xml`（`inst_pool_build` 汇总 三大周期选股 + ETF全行业扫描 + ETF top5 + 实仓 → `inst_holding_judge` 机构定性 → `stop_loss_vote` 六理论投票止损）→ 直接拉 stageOutputs 排版落 Excel，**不另起算法**。趋势规律列复用 `usecase_pipeline._trend_match_3way/_etf_macd_text/_etf_sar_text/_etf_obv_text`（与 APK TrendClassGate 同口径），止损理论列标出六路赢家。产出 `data/交易决策工作台_<asof>.xlsx`（多 sheet：交易决策工作台 / A级精选 / B级观察 / 机构明细AB池 / 汇总统计 / 使用说明）+ `_latest.xlsx`。`inst_pool_build` 无推送落盘时自动兜底读 `AutoQuant/data/dag_screen_latest.json` + `data/_etf_ds_picks.json`。 | `python _trade_workbench.py` / `--asof 2026-09-11` / `--period LONG` / `--grade A` / `--no-holdings` |

---

## 4. 当日盘面分析

| 文件 | 说明 | 典型用法 |
|------|------|----------|
| `_analysis_today.py` | 当日大盘/微盘跳水诊断 + 回测验证（指数、涨跌家数、核心票）。 | `python _analysis_today.py` |
| `publish_close_round.py` | **收盘选股整轮推送（强制完整版，2026-09-08 由 `_probe_push_round.py` 转正）**：读取最新候选清单，强制推送候选表格（含趋势图谱/星后形态/趋势图三列）+ 可选实仓段；用于收盘后手动补推或盘外静态数据复核。**2026-09-12 起候选表经 `_table_csv` 先落一份 CSV 再出统一长图，并同步产出一份全左对齐 XLSX，推送「长图 + XLSX + 原始 CSV」三份**（`imgs` 按 .csv/.xlsx/.png 后缀分派 `send_file`/`send_image`）。 | `python publish_close_round.py` / `--dry` / `--no-pos` |
| `_push_text_cli.py` | **微信文本推送 CLI（2026-09-09 新增，供 CodeBuddy automation/每日三次板块挖掘调用）**：把 UTF-8 文本（--file）或 --content 经 `push_channel`（企微机器人>pushplus>serverchan）发送，规避命令行转义地狱。返回码 0=成功/2=失败/3=参数错。 | `python _push_text_cli.py --title 板块晨报 --file data/_sector_push.txt` |
| `_sector_quote.py` | **板块候选行情扫描（2026-09-09 由 `_tmp_probe_sector.py` 转正，正式工具）**：默认厄尔尼诺农业主线池，腾讯 `q=` 批量实时估值字段(总市值/静态PE/PB/换手/量比) + `fetch_tencent` 日K(5/10/20日涨幅/MA20/60形态/距60日高)。被 sector-hunter/sector-picks skill 引用；东财 push2 被断时的最稳链路。 | `python _sector_quote.py` / `--codes sz000998,sh600141` / `--kw 化肥` |
| `_daily_intel.py` | **每日节奏情报引擎（2026-09-11 新增，正式工具，守护 v4 核心）**：三时段 `--slot pre8`(08:00 宏观+美股收盘) / `pre9`(09:00 亚太:日经/KOSPI/恒生/台湾/新加坡/澳洲+韩国权重股) / `review`(15:20 表格化复盘)。链路：全球快照(东财 push2delay 重试3次+新浪兜底) → 规则化「利好/利空板块」(强度+逻辑+触发+证伪) → 六维过滤+追高闸门选候选 → 企微推送 + 落库 `market_data.db` 的 `intel_report`/`push_record`。复盘四组表（**2026-09-12 改版**，与盘中同口径、经 `_table_csv` 统一「填充一份 CSV → 一张长图」并**同步产出一份全左对齐 XLSX**（`_table_xlsx.py`），推送「正文 + 长图 + XLSX + 原始 CSV」）：a 当日选股（复用 `_publish_candidates._build_candidate_sections` 的 5 段 / 17 列统一表） / b 实仓镜像（同 17 列，建议/盈亏并入备注格） / c 板块判定对错核对 / d 近5日信号票巡诊。**规则表/候选池与 APK `DailyRhythmEngine.kt` 同口径**。 | `python _daily_intel.py --slot pre8` / `--slot pre9` / `--slot review` / 加 `--dry` 只渲染不推送 |

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
| `_nodes_exec_report.py` | **节点执行核对 / bypass 审计（内部自检，2026-09-12 转正）**：自动扫描全部 `*_usecase.xml`（新增 usecase 免改脚本）→ 逐个 usecase 汇总每个 node 是「执行 / 透传 / bypass / 静默未跑」，并按 `未实现 module / 降级为透传 / 执行失败` 分类计数。**定位：exe / apk / codebuddy 跑完一轮后自我检测、自我调试用，绝不推客户、不进企微群（原 `_tmp_push_911.py` 推送变体已删除）。** `--selfcheck` 走基线回归判定（`data/_nodes_exec_baseline.json`，`--update-baseline` 落基线）：退出码 `0` 无回归 / `1` 相比基线新增 bypass / `3` 有用例抛异常，并打印单行 `SELFCHECK code=… new=… fixed=…` 供程序抓取。 | `python _nodes_exec_report.py --selfcheck` |

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
