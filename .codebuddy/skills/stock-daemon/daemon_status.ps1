param(
    [ValidateSet("publish", "scan", "all")]
    [string]$Daemon = "all"
)
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$dir  = Join-Path $root "smalltools"

function Show-One([string]$tag, [string]$scriptName) {
    $log  = Join-Path $dir "_daemon_$tag.log"
    $err  = Join-Path $dir "_daemon_$tag.err.log"
    $pidF = Join-Path $dir "_daemon_$tag.pid"
    Write-Output "==== $scriptName ($tag) ===="
    if (Test-Path $pidF) {
        $id = [int]([System.IO.File]::ReadAllText($pidF).Trim())
        $proc = Get-Process -Id $id -ErrorAction SilentlyContinue
        if ($proc) {
            Write-Output ("运行中 pid={0} 启动于 {1}" -f $id, $proc.StartTime)
        } else {
            Write-Output "PID 文件存在但进程已不在（pid=$id）"
        }
    } else {
        Write-Output "未运行（无 pid 文件）"
    }
    if (Test-Path $log) { Write-Output "-- 日志尾部 --"; Get-Content $log -Tail 8 -Encoding UTF8 }
    if ((Test-Path $err) -and (Get-Item $err).Length -gt 0) { Write-Output "-- stderr --"; Get-Content $err -Tail 5 -Encoding UTF8 }
}

switch ($Daemon) {
    "publish" { Show-One "publish" "_publish_candidates.py" }
    "scan"    { Show-One "scan" "_market_scan.py" }
    "all"     { Show-One "publish" "_publish_candidates.py"; Show-One "scan" "_market_scan.py" }
}

$rhythm = Join-Path $dir "_daemon_rhythm.json"
if (Test-Path $rhythm) { Write-Output "==== 当日节奏 ===="; Get-Content $rhythm -Encoding UTF8 }
