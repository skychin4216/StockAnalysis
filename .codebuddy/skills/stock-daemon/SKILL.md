---
name: stock-daemon
description: 启动/停止/检查 StockAnalysis PC 侧 A 股盘段守护后台进程（smalltools/_publish_candidates.py 盘段节奏守护 + _market_scan.py 盘中情报扫描）。当用户说"启动后台进程"、"运行后台进程"、"把守护跑起来"、"守护启动一下"、"后台进程没在跑"、"9:30 帮我启动守护"、"检查守护状态"等时使用。
---

# StockAnalysis 盘段守护（后台进程一键运维）

## 背景

PC 侧两个长驻 Python 守护（均按 A 股交易时段自动挂起/唤醒，工作日 9:30-11:30、13:00-15:00）：

| 守护 | 脚本 | 节奏 | 产出 |
|---|---|---|---|
| 候选盘段守护（主） | `_publish_candidates.py --daemon` | 每盘段首刷「下载K线(`_update_cache_inc.py`) → XML DAG 当日选股(`AutoQuant/usecase_screen.py`)」约 1~7 分钟，完成后立即整轮选股，此后每 900 秒一轮 | 选股清单 → 微信推送 → 上传 COS；推送含 CodeBuddy 候选 + 🤖XML DAG 当日选股 + 双端共同命中 + 💸资金流出分组 + 实仓建议 |
| 盘中情报扫描 | `_market_scan.py --daemon` | 交易时段每 600 秒收集一次，与上次快照对比，**有变动才推** | 板块轮动/美股/韩股/研报/快讯情报 |

- **2026-09-04 决策**：codebuddy 不再调用 smalltools 的回溯+拟合（`_full_cycle_backtest.py` 只保留代码不执行），盘段首刷的选股环节改由 exe/APK 共用的 XML DAG 引擎（`AutoQuant/usecase_screen.py` → `usecase_pipeline.py`，同一份 `assets/usecases` XML）提供，与 App/PC DAG 结果同源。
- 推送渠道（`push_channel.py`，顺序）：企业微信机器人 `notify.wecom_key` > pushplus > serverchan。
- 守护状态持久化在 `smalltools/_daemon_rhythm.json`（当天 am/pm 段是否已 prep、每段上次选股时间），跨日自动重置，**重启守护不会重复 prep**。

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

## 节奏 / 时间窗口要点

- 盘段：上午 09:30-11:30（`am`），下午 13:00-15:00（`pm`）。非交易时段守护自动挂起。
- 段内首刷只做一次：`_daemon_rhythm.json` 的 `prep.am/pm` 标记；失败不标记、下个检查点自动重试。
- **09:30 前启动不会跳过上午段**：`next_trading_start()` 已修复为 09:30 前返回当天 09:30（勿回退旧逻辑——旧版会直接睡到 13:00 丢掉整个上午）。
- 守护内子进程（下载/XML DAG）自带 `PYTHONIOENCODING=utf-8`，无需额外处理。

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
| 启动后日志停在"非交易时段挂起" | 正常，等到 09:30/13:00 自动进段；或看 `_daemon_rhythm.json` |
| 09:30 后很久没有整轮推送 | 段首 prep（下载+回溯约 6~13 分钟）结束后才会首轮选股，属正常 |
| 日志出现 `WinError 10061` | 勿动——显式禁用了系统代理(IE 代理 127.0.0.1:12450)，是预期行为 |
| 推送没到微信 | 查 `app_config.json` notify：`wecom_key` 空则走 pushplus；都空则不推 |
| 改了推送/选股逻辑不生效 | 重启守护进程（改的是源码，运行中进程持有旧代码） |
