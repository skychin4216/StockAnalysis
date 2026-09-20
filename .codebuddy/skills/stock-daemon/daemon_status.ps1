<#
  stock-daemon skill —— 查看后台守护状态【2026-09-19 改为薄封装】

  实现收敛到 `smalltools/_daemon_ctl.py`。相比旧版新增：
    · 状态区分 running / **stale（心跳超时＝疑假死）** / stopped
    · 心跳按**交易时段动态**判定（盘中 20 分钟、盘后 3 小时、非交易日 4 小时），不再误报
    · 顺带打印每个守护的日志尾部一行

  用法：
    powershell -File daemon_status.ps1              # 表格
    powershell -File daemon_status.ps1 -Json        # 机器可读（exe / 自动化）

  等价新用法：
    python smalltools/_daemon_ctl.py status
    python smalltools/_daemon_ctl.py status --json
    python smalltools/_daemon_ctl.py logs scan 50
#>
param(
  [switch]$Json,
  [double]$StaleMin = 0
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$ctl = Join-Path $root "smalltools\_daemon_ctl.py"
if (-not (Test-Path $ctl)) { Write-Error "未找到统一控制层: $ctl"; exit 1 }
$args = @($ctl, "status")
if ($Json) { $args += "--json" }
if ($StaleMin -gt 0) { $args += @("--stale-min", "$StaleMin") }
python @args
exit $LASTEXITCODE
