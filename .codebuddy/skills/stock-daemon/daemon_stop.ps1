param(
    [ValidateSet("publish", "scan", "all")]
    [string]$Daemon = "all"
)
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$dir  = Join-Path $root "smalltools"

function Stop-Tree([int]$id) {
    # 先停子进程（守护可能正在跑 下载/回溯 子进程），再停自身
    Get-CimInstance Win32_Process -Filter "ParentProcessId = $id" -ErrorAction SilentlyContinue |
        ForEach-Object { Stop-Tree ([int]$_.ProcessId) }
    Stop-Process -Id $id -Force -ErrorAction SilentlyContinue
}

function Stop-One([string]$tag) {
    $pidF = Join-Path $dir "_daemon_$tag.pid"
    if (-not (Test-Path $pidF)) { Write-Output "[$tag] 未运行（无 pid 文件）"; return }
    $id = [int]([System.IO.File]::ReadAllText($pidF).Trim())
    if (Get-Process -Id $id -ErrorAction SilentlyContinue) {
        Stop-Tree $id
        Write-Output "[$tag] 已停止 pid=$id"
    } else {
        Write-Output "[$tag] 进程已不在（pid=$id）"
    }
    [System.IO.File]::Delete($pidF)
}

switch ($Daemon) {
    "publish" { Stop-One "publish" }
    "scan"    { Stop-One "scan" }
    "all"     { Stop-One "publish"; Stop-One "scan" }
}
