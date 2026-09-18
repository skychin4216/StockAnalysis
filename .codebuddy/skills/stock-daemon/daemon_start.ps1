param(
    [ValidateSet("publish", "scan", "holdings", "all")]
    [string]$Daemon = "all",
    # 每次重启前把旧日志归档，最多保留多少份历史（2026-09-17 用户需求：追加 + 轮转）
    [int]$KeepLogs = 7
)
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$dir  = Join-Path $root "smalltools"
$py   = "C:\Program Files\Python313\python.exe"
$env:PYTHONIOENCODING = "utf-8"   # 必须：日志含 emoji，cp936 下 print 会崩

function Rotate-Log([string]$path, [int]$keep = 7) {
    # Start-Process -RedirectStandardOutput 是「覆盖」模式：每次启动都会 truncate。
    # 所以启动前把已有日志改名归档（相当于追加效果），并只保留最近 $keep 份。
    if (-not (Test-Path $path)) { return }
    $item = Get-Item $path
    if ($item.Length -eq 0) { Remove-Item $path -Force -ErrorAction SilentlyContinue; return }
    $stamp = Get-Date -Format "yyyyMMdd_HHmmss"
    $arch  = "$path.$stamp.bak"
    Move-Item -Path $path -Destination $arch -Force
    Get-ChildItem "$path.*.bak" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -Skip $keep |
        Remove-Item -Force -ErrorAction SilentlyContinue
    Write-Output "  日志已归档: $(Split-Path $arch -Leaf)"
}

function Start-One([string]$scriptName, [string]$tag) {
    $log    = Join-Path $dir "_daemon_$tag.log"
    $err    = Join-Path $dir "_daemon_$tag.err.log"
    $pidF   = Join-Path $dir "_daemon_$tag.pid"
    if (Test-Path $pidF) {
        $old = [int]([System.IO.File]::ReadAllText($pidF).Trim())
        if (Get-Process -Id $old -ErrorAction SilentlyContinue) {
            Write-Output "[$tag] 已在运行 pid=$old，跳过"
            return
        }
        Write-Output "[$tag] 旧 pid=$old 已失效，重新拉起"
    }
    Rotate-Log $log $KeepLogs
    Rotate-Log $err $KeepLogs
    $p = Start-Process -FilePath $py -ArgumentList $scriptName, "--daemon" `
        -WorkingDirectory $dir -RedirectStandardOutput $log -RedirectStandardError $err `
        -WindowStyle Hidden -PassThru
    [System.IO.File]::WriteAllText($pidF, $p.Id.ToString())
    Write-Output "[$tag] 已启动 pid=$($p.Id)  log=$log"
}

switch ($Daemon) {
    "publish"  { Start-One "_publish_candidates.py" "publish" }
    "scan"     { Start-One "_market_scan.py" "scan" }
    "holdings" { Start-One "_holdings_flow_daemon.py" "holdings_flow" }
    "all"      { Start-One "_publish_candidates.py" "publish"
                 Start-One "_market_scan.py" "scan"
                 Start-One "_holdings_flow_daemon.py" "holdings_flow" }
}
