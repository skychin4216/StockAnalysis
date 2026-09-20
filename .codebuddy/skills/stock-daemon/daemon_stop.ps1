<#
  stock-daemon skill —— 停止后台守护【2026-09-19 改为薄封装】

  实现收敛到 `smalltools/_daemon_ctl.py`（与 exe 守护 Tab、命令行共用）。
  保留旧入口/旧参数（-Daemon）兼容。

  用法：
    powershell -File daemon_stop.ps1 -Daemon all
    powershell -File daemon_stop.ps1 -Daemon scan
    可用值：all | publish | scan | holdings | bridge

  等价新用法：python smalltools/_daemon_ctl.py stop all
#>
param(
  [string]$Daemon = "all"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$ctl = Join-Path $root "smalltools\_daemon_ctl.py"
if (-not (Test-Path $ctl)) { Write-Error "未找到统一控制层: $ctl"; exit 1 }
python $ctl stop $Daemon
exit $LASTEXITCODE
