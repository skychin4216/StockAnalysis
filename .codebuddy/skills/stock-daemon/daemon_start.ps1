param(
    [ValidateSet("publish", "scan", "all")]
    [string]$Daemon = "all"
)
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$dir  = Join-Path $root "smalltools"
$py   = "C:\Program Files\Python313\python.exe"
$env:PYTHONIOENCODING = "utf-8"   # 必须：日志含 emoji，cp936 下 print 会崩

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
    $p = Start-Process -FilePath $py -ArgumentList $scriptName, "--daemon" `
        -WorkingDirectory $dir -RedirectStandardOutput $log -RedirectStandardError $err `
        -WindowStyle Hidden -PassThru
    [System.IO.File]::WriteAllText($pidF, $p.Id.ToString())
    Write-Output "[$tag] 已启动 pid=$($p.Id)  log=$log"
}

switch ($Daemon) {
    "publish" { Start-One "_publish_candidates.py" "publish" }
    "scan"    { Start-One "_market_scan.py" "scan" }
    "all"     { Start-One "_publish_candidates.py" "publish"; Start-One "_market_scan.py" "scan" }
}
