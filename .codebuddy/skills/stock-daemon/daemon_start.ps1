<#
  stock-daemon skill —— 启动后台守护【2026-09-19 改为薄封装】

  实现已收敛到 `smalltools/_daemon_ctl.py`（exe 守护 Tab / codebuddy / 命令行**共用同一个实现**，
  避免三套逻辑漂移）。本脚本保留旧入口与旧参数（-Daemon）兼容，实际逻辑全部转发。

  用法（与旧版一致）：
    powershell -File daemon_start.ps1 -Daemon all
    powershell -File daemon_start.ps1 -Daemon publish
    可用值：all | publish | scan | holdings | bridge

  等价新用法（推荐）：
    python smalltools/_daemon_ctl.py start all
    python smalltools/_daemon_ctl.py status --json      # 机器可读（含心跳/假死判定）
    python smalltools/_daemon_ctl.py logs publish 50    # 日志尾部
    python smalltools/_daemon_ctl.py send publish "..."  # 给守护发指令
#>
param(
  [string]$Daemon = "all",
  [switch]$Quiet
)
$ErrorActionPreference = "Stop"
# $PSScriptRoot = <root>/.codebuddy/skills/stock-daemon  →  上溯 3 层到项目根
$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$ctl = Join-Path $root "smalltools\_daemon_ctl.py"
if (-not (Test-Path $ctl)) { Write-Error "未找到统一控制层: $ctl"; exit 1 }
python $ctl start $Daemon
exit $LASTEXITCODE
