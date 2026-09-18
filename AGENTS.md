# StockAnalysis Agent 工作约定

> 本文件供 CodeBuddy/Claude/Codex 等 IDE Agent 启动时自动加载，作为本项目 AI 协作的统一上下文。
> 目的：减少重复上下文加载、明确轻量任务路由、固化已确认的设计口径。
> 最近更新：2026-09-17

## 1. 项目一句话

A 股选股引擎（PC + Android APK 双端），数据单一事实源 = `app/src/main/assets/`。
XML DAG 是选股主线（`usecases/*.xml` + `usecase_pipeline.py`），
`smalltools/` 是 PC 侧守护与回溯拟合工具。事件库 = `app/src/main/assets/macro_events/event_library.json`。

## 2. 轻量任务路由（少用 AI，多用脚本）

下列请求**优先走 PowerShell/脚本**，不要先大段读代码再回答。脚本输出已足够用户决策：

| 用户问 | 直接执行（不调 AI） |
|--------|---------------------|
| 后台进程有没有在跑 | `powershell -File .codebuddy/skills/stock-daemon/daemon_status.ps1` |
| 守护日志尾巴 | `Get-Content smalltools\_daemon_publish.log -Tail 80` |
| 收件箱/outbox 清单 | `Get-ChildItem data\_inbox, data\_outbox` |
| 当日推送记录 | `Get-Content smalltools\_records\_pick_ledger.jsonl -Tail 20` |
| 事件库活跃实例 | `python smalltools\_event_kb.py 2026-09-17` |
| 非交易时段新闻池 | `python smalltools\_offhour_watch.py --drain` |
| 六类权威新闻 | `Get-Content smalltools\_news_authoritative.json \| ConvertFrom-Json` |
| 归档新闻网址清单 | `python smalltools\_news_watch.py --links 20`（全源留档 `smalltools/_records/_news_links.jsonl`） |
| 看看某只票 | `python -c "from smalltools._kline_store import load_store; ..."` |

对**已读取过的文件**（见 §4），用记忆中已有的摘要复用，不要重复 read_file。

## 3. 已确认的设计口径（不要质疑，直接遵守）

- **选股主链**：XML DAG = 单一事实源；Kotlin/PC 双端读同一份 XML。
- **⚠️ pipeline 就是函数调用，无 AI/prompt 参与**（2026-09-17 澄清）：
  `usecase_pipeline.py` 是普通 Python 模块，选股走 `run_pipeline(xml)` 之类的**直接函数调用**；
  `_market_scan` / `_offhour_watch` / `_daily_intel` / `_publish_candidates` 全部是**零 LLM 调用的规则脚本**。
  它们**不消耗 CodeBuddy 积分**。真正消耗积分的是 **IDE Agent（我）读文件/日志/分析**。
  → 不要再声称"pipeline 被灌进 system prompt"，那是错的。要省积分 = 降低喂给 Agent 的文本量
  （少读大文件、收敛新闻源、降推送频率、复用摘要）。
- **K 线数据**：唯一数据源 = `data/kline_store.json`（688 只全历史 2008 起），不再使用 smalltools/_kline_cache.json 等其他缓存。
- **推送统一表**：`_TABLE_HEAD` 29 列主力成本后 PE静/PB/营收%/净利%/ROE%/PEG 成组；`_annotate_fundamentals` 导出前回填。
- **买入上限**：单轮 5 只（2026-09-17 调 3→5）。
- **机构保送**：东财十大流通股东披露≤35天 + 增持 + 机构身份（国家队 95/社保 90/大基金 88/公募 78/险资 76/QFII 74/北向 72）；机构最新披露 CHANGE≤0 不保送；最新报告期<票级最新报告期 = 已退出（旧增持作废仅曾增持过才提示）。
- **推送节奏**（2026-09-17 用户口径，降频省上下文）：
  - 盘前 **08:00（pre8）** 一次完整情报推送。
  - **交易时段**：`_market_scan` 每 **1 小时**（`DEFAULT_INTERVAL=3600`）采集+推送一次。
  - **非交易时段**：**不推送**，只采集 + 去重 + 存库（`_offhour_watch` 写 `_records/_offhour_news_pool.jsonl`；
    `_market_scan` 走 `run_once(dry=True, push=False)` 30 分钟采集一次写 `_last_scan.json` 去重基线）。
  - 非交易时段的新闻池由次日 08:00 pre8 / 盘中消费（消费即清空）。
- **新闻源收敛**（2026-09-17 用户口径）：只分析 **美国 1财经+1政治+1机构 / 中国 1财经+1政治+1机构**，
  实现见 `_news_watch.collect_authoritative()`（≤18 条，5 分钟复用缓存，定向源华尔街见闻/美联储RSS，
  不可达时东财+新浪关键词过滤兜底）。`_daily_intel.collect_news()` 默认走它。
- **日志轮转**：`daemon_start.ps1` 启动前把 `_daemon_*.log/.err.log` 归档为 `*.YYYYmmdd_HHMMSS.bak`，保留最近 7 份。
- **运行节奏**：用户任务没有次数/轮次上限；不要因担心 token 提前收尾；不可恢复硬错误才停下报告。

## 4. 本会话已读取的「大文件」摘要（不要重复读）

读取过的文件如需再次引用，按下方摘要复用，**除非明确要求看新内容**：

| 文件 | 大小 | 已读摘要 |
|------|------|----------|
| `smalltools/_offhour_watch.py` | 12KB | fed/cn/eu 分类 + ≥3 命中才 MAJOR；push=False 只采集存池；append/drain_news_pool |
| `smalltools/_daily_intel.py` | 62KB | snapshot/run_slot 加 prefetched；collect_news 走权威源 + drain_pool；其余未读 |
| `smalltools/_news_watch.py` | 14KB | collect_ext（气候/外媒/名人/研报）+ collect_authoritative（六类权威源）；源级 5min 缓存共享（同轮不重复抓同源）；全源留档 `_records/_news_links.jsonl`（url/标题去重，上限 20000），`--links N` 查看 |
| `smalltools/_market_scan.py` | 30KB | DEFAULT_INTERVAL=3600；run_once(push=)；非交易时段只采集 |
| `smalltools/_event_kb.py` | 3KB | mtime cache 已实现；EVENT_JSON=app/.../event_library.json |
| `smalltools/_publish_candidates.py` | ~400KB（分段读） | 2640 行 `_macro_events_lines`；4676 行 `_offhour_watch_until`；其余分段 |
| `.codebuddy/skills/stock-daemon/daemon_start.ps1` | 1.6KB | 启动前 Rotate-Log 归档，保留 7 份 |
| `app/src/main/assets/macro_events/event_library.json` | 12KB | 事件类型 + 实例；magnitude 0~2；窗口 start/end |

## 5. 推送/事件卡已知要点

- 美联储决议日自动追加 `rate_cycle_card`（深度利率卡，pre8/pre9/offhour 触发链），方向判定用关键词计数防多条快讯混合误判。
- 机构保送门禁（`InstBuyRecentNode.kt` / `_inst_buy_recent`）：香港中央结算（代理人）≠ 北向，双端排除。

## 6. 用户工作习惯（用以判断响应方式）

- 中文交流，简洁回答优先；技术结论直接说，不绕弯。
- 用户每次"全部优化"代表**全部推进并验证**，不要只挑部分。
- 用户希望守护进程**始终在跑**——守护停掉后立刻重启。
- 表格化复盘（review slot）= PNG 文件 `data/review_tables/`。