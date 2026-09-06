---
name: stock-daemon
description: 启动/停止/检查 StockAnalysis PC 侧 A 股盘段守护后台进程（smalltools/_publish_candidates.py 盘段节奏守护 + _market_scan.py 盘中情报扫描）。当用户说"启动后台进程"、"运行后台进程"、"把守护跑起来"、"守护启动一下"、"后台进程没在跑"、"9:30 帮我启动守护"、"检查守护状态"等时使用。
---

# StockAnalysis 盘段守护（后台进程一键运维）

## 背景

PC 侧两个长驻 Python 守护（候选盘段守护按 v2 时刻表自动唤醒，工作日 09:00 起）：

| 守护 | 脚本 | 节奏 | 产出 |
|---|---|---|---|
| 候选盘段守护（主） | `_publish_candidates.py --daemon` | **v2 时刻表**：09:00 预热(下载K线+情报扫描 dry 暖缓存) → 09:30-11:30 每 15 分钟 9 轮 → 13:00-14:30 每 15 分钟 7 轮 → 15:00 尾盘 K 线拉取(仅下载) → 15:00 尾盘后**选股自检**`_self_review.py`(记忆账本+到期结算) → 15:10 收盘总结；每盘段首刷「下载K线(`_update_cache_inc.py`) → XML DAG 当日选股(`AutoQuant/usecase_screen.py`)」 | 盘中轮每轮选股同时内嵌实仓段（**有变化才展开**，无变化仅一行概要"建议一致无新操作"）→ 上传 COS；15:10 收盘总结含当日选股汇总+自检复盘摘要+持仓回顾+组合纪律+鼓励 |
| 盘中情报扫描 | `_market_scan.py --daemon` | 09:00 预热由主守护 `--once --dry` 触发一次；交易时段每 600 秒收集，与上次快照对比，**有变动才推** | 板块轮动/美股/韩股/研报/快讯情报 |

- **2026-09-04 决策**：codebuddy 不再调用 smalltools 的回溯+拟合（`_full_cycle_backtest.py` 只保留代码不执行），盘段首刷的选股环节改由 exe/APK 共用的 XML DAG 引擎（`AutoQuant/usecase_screen.py` → `usecase_pipeline.py`，同一份 `assets/usecases` XML）提供，与 App/PC DAG 结果同源。
- **2026-09-05 决策（机构化收敛）**：候选展示面收敛为 **短线/中线/长线 三档**——超短引擎保留（walk-forward 样本外 wr51.3%、pf1.70），命中折入"短线⚡(极速档)"（条目带 `flash`/`signal_period` 字段，DAG/exe 对比同步折叠）。推送第③段新增 **⚠️ 组合纪律**：单票占持仓市值>30%、前2大>55%、组合整体浮亏≤-8% 任一触发即提醒（同阈值 App 实仓页卡片）。
- **2026-09-06 决策（守护 v2 / 纠错）**：①守护 09:00 开始预热（此前 09:30 才拉数据，首轮选股被下载拖到 09:3x 之后）；②盘中轮固定 15 分钟且下午 14:30 截止（此前 900 秒滚到 15:00，尾盘还在选股诱导）；③**盘中轮实仓建议改为「有变化才推」并随每轮选股消息内嵌**（评估每轮跑，结论无变化只一行概要、不刷屏；verdict/建议变化才展开逐笔）——不再每轮独立推买卖/做T指令式消息，也不再完全关闭；④收盘总结带鼓励语，不含做T/买卖点指令；⑤一键建仓取消 Toast、改三周期完成后聚合结果窗（App 侧）。
- **2026-09-06 补充决策（个股基本面画像）**：实仓逐笔评估引入 `smalltools/_holding_thesis.json` 画像。东山精密/博迁新材等标记 `soft=true` 的逻辑票：-8%~逻辑止损(risk_pct)区间给「减仓应对/留底仓/企稳加回/做T」柔性建议，**不机械按 -8% 喊清仓**；跌破 risk_pct 才「止损警戒」。逐笔 note 引用画像 tone 给出操作框架。
- **2026-09-06 自检闭环 R1（记忆+自检）**：`smalltools/_self_review.py` 在 15:00 尾盘 K 线定格后由守护自动执行（`_daemon_rhythm.json` 的 `learn` 标志），把当日 XML DAG 主线 + 轮选候选写入只增账本 `_records/_pick_ledger.jsonl`（含当时大盘状态/卖出规则快照/候选 asof 收盘价）→ 结算到期信号（缓存覆盖完整持仓窗口后，用当时规则 `simulate_trade` 确定性回放，`_records/_pick_reviews.json`）→ 与 walk-forward 基线对比输出漂移建议（`_records/_selflearn_state.json`，默认 `mode=dry` 只出建议不生效）。报告/推送内嵌「🧭 盘面应对」：三指数风险分级（L1 正常 / L2 风格·板块切换 / L3 系统性急跌）+ 持有中信号票每日巡诊（破止损→出清、-5%~止损→减半仓、未破位→持有）+ 实仓镜像风控 + T+1 预案（L3 不开新仓等企稳；L2 可继续但只取新主线、试仓≤半仓）。15:10 收盘总结会追加「🧠 自检复盘」段。手动运行：`python _self_review.py --record --evaluate --report` 或 `--status`/`--push`。**注意**：自检失败不阻塞收盘总结。
- **2026-09-06 补充决策（推送附大盘图）**：`smalltools/_index_market_chart.py` 生成「大盘4指数归一化叠加图」（上证/深证/科创50/创业板，窗口首日收盘=100，数据东财优先/腾讯回退 + 本地缓存 `data/_index_market.json`）。守护推送接入：①09:00 预热完成后推「🌅 开盘前大盘速览」（强弱结论文本 + 图，`_daemon_rhythm.json` 新增 `pre_msg` 每日一次标志，失败也标记防重试刷屏、错误留痕 `pre_msg_error`）；②15:10 收盘总结文本推送成功后跟发大盘图（`_push_market_image`，失败仅留痕不影响总结）。图片经 `push_channel.send_image()` 走**企微机器人 image 消息**（≤2MB，pushplus/serverchan 不支持图会自动跳过并打印）。手动：`python _index_market_chart.py`（出图）或 `--text`（强弱文本）。
- 推送渠道（`push_channel.py`，顺序）：企业微信机器人 `notify.wecom_key` > pushplus > serverchan。
- 守护状态持久化在 `smalltools/_daemon_rhythm.json`（当天 pre/am/pm prep、tail、sum、各段轮数、逐轮命中累计），跨日自动重置，**重启守护不会重复 prep**。
- **纠错留痕**：守护/选股过程错误追加写入 `smalltools/_daemon_ops.jsonl`（ts/kind/msg），供后续矫正；人工决策记录见 `docs/守护操作与纠错记录.md`。

## 关键路径

- Python：`C:\Program Files\Python313\python.exe`
- 工作目录：`<repo>\smalltools`
- 日志：`<repo>\smalltools\_daemon_publish.log(.err.log)` / `_daemon_scan.log(.err.log)`
- PID 文件：`_daemon_publish.pid` / `_daemon_scan.pid`
- 推送配置：`<repo>\app\src\main\assets\data\app_config.json` 的 `notify` 段

## 用法（skill 目录自带脚本，PowerShell 直接跑）

```powershell
# 1. 启动（默认 all = 候选守护 + 情报扫描；也可 publish / scan 二选一）
powershell -ExecutionPolicy Bypass -File .codebuddy\skills\stock-daemon\daemon_start.ps1 -Daemon all

# 2. 状态检查（进程 + 日志尾部 + 当日节奏）
powershell -ExecutionPolicy Bypass -File .codebuddy\skills\stock-daemon\daemon_status.ps1

# 3. 停止
powershell -ExecutionPolicy Bypass -File .codebuddy\skills\stock-daemon\daemon_stop.ps1 -Daemon all
```

## 手动等价命令（不想用脚本时）

启动主守护：

```powershell
$dir = "e:\Android\work\dev\StockAnalysis\smalltools"
$env:PYTHONIOENCODING = "utf-8"   # 必须！日志含 emoji，cp936 下 print 会崩
$p = Start-Process -FilePath "C:\Program Files\Python313\python.exe" `
  -ArgumentList "_publish_candidates.py","--daemon" -WorkingDirectory $dir `
  -RedirectStandardOutput "$dir\_daemon_publish.log" `
  -RedirectStandardError  "$dir\_daemon_publish.err.log" `
  -WindowStyle Hidden -PassThru
[System.IO.File]::WriteAllText("$dir\_daemon_publish.pid", $p.Id.ToString())
```

情报扫描同款：脚本名换 `_market_scan.py --daemon`，日志/PID 换 `_daemon_scan` 前缀。

查看运行状况：

```powershell
Get-Content e:\Android\work\dev\StockAnalysis\smalltools\_daemon_publish.log -Tail 15 -Encoding UTF8
Get-Content e:\Android\work\dev\StockAnalysis\smalltools\_daemon_rhythm.json -Encoding UTF8
```

## 节奏 / 时间窗口要点（v2，2026-09-06）

- 档位：`pre` 09:00-09:29（预热：K线增量下载 + 情报扫描 `--once --dry` 暖缓存）→ `am` 09:30-11:30（每 15 分钟 9 轮）→ `pm` 13:00-14:30（每 15 分钟 7 轮）→ 14:31 后不再盘中选股 → 15:00 尾盘 K 线拉取（仅下载）→ 15:10 收盘总结。
- 轮次触发：段首 prep（下载+XML DAG）完成后先立即整轮，之后只在 `分钟%15==0` 的时刻整轮，避免漂移。
- 标记持久化在 `_daemon_rhythm.json`：`prep.am/pm`（首刷）、`pre`（预热）、`tail`（尾盘拉取）、`learn`（尾盘后选股自检）、`sum`（收盘总结）、`rounds`（当日轮数）、`hit`（逐轮命中累计）；失败不标记、自动重试。
- **守护需在 09:00 前（或 09:00-09:29 之间）启动**才能吃到预热段；09:30 后启动则直接进 am 首刷兜底（首轮选股会延后到下载完成）。
- `--interval` 参数仅兼容旧调用，v2 固定 15 分钟整点轮。
- 守护内子进程（下载/XML DAG/情报扫描）自带 `PYTHONIOENCODING=utf-8`，无需额外处理。

## 踩坑清单（务必遵守）

1. 重定向输出前必须 `$env:PYTHONIOENCODING = "utf-8"`，否则日志含 emoji（⏰🛑✓）时 Python 抛 `UnicodeEncodeError`。
2. `Start-Process` 的 stdout/stderr 重定向必须指向**两个不同文件**。
3. PID 落盘用 `[System.IO.File]::WriteAllText`，**不要用 `Set-Content`**（本环境会被安全层拦截）。
4. 日志文件会被 Start-Process 直接覆盖重写，**无需（也不要）先 `Remove-Item`**——对不存在的文件执行会被安全层拦截并中止整条命令。
5. 读日志用 `Get-Content -Encoding UTF8`。
6. 拉起的进程是隐藏窗口长驻进程，改代码后需**重启守护**才生效（`daemon_stop.ps1` → `daemon_start.ps1`）。
7. exe(AutoQuant-GUI) 打包时 `--hidden-import _publish_candidates`，其 `_resolve_app_config()` 在 frozen 下会回退到工程绝对路径，勿改动该回退链。

## 常见问题

| 现象 | 处理 |
|---|---|
| 启动后日志停在"空档" | 正常：开盘前/午休/14:30 后都算空档，守护会睡到下一动作点；看 `_daemon_rhythm.json` |
| 09:30 后很久没有整轮推送 | 段首 prep（下载+XML DAG）结束后首轮选股；若 09:00 预热已下载过则通常 1 分钟内出首轮 |
| 09:00 预热失败反复重试 | 2 分钟间隔自动重试至 09:30；仍失败则 09:30 am 首刷兜底，不影响当日选股 |
| 盘中"实仓"段为何只有一行 | 正常：每轮都评估，但与上轮建议一致时只显示"建议与上轮一致，无新操作"，有变化(verdict/建议变化)才展开逐笔 |
| 想强制完整实仓建议 | 盘中某票 verdict/note 变化即展开；或删除 `_daemon_rhythm.json` 的 `pos_sig` 字段，下轮首条即完整展开 |
| 日志出现 `WinError 10061` | 勿动——显式禁用了系统代理(IE 代理 127.0.0.1:12450)，是预期行为 |
| 推送没到微信 | 查 `app_config.json` notify：`wecom_key` 空则走 pushplus；都空则不推 |
| 想排查守护/选股错误 | 看 `smalltools/_daemon_ops.jsonl`（错误留痕）与日志 |
| 改了推送/选股逻辑不生效 | 重启守护进程（改的是源码，运行中进程持有旧代码） |
| 收盘总结没有「🧠 自检复盘」段 | 尾盘自检失败或 `_selfreview_report.json` 的 asof 与当日不一致；手动跑 `python _self_review.py` 看报错，查 `_records/_self_review.log` |
| 想看选股是否真的有效 | `python _self_review.py --status`（账本/已结算笔数/各周期 extra_pass 档位） |
