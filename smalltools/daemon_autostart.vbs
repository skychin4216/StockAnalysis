' StockAnalysis 守护开机自启（登录时由计划任务触发，无窗口）
' 幂等：daemon_start.ps1 检测 pid 文件 + 进程存活，已在跑则自动跳过
Dim fso, root, ps1
Set fso = CreateObject("Scripting.FileSystemObject")
' 本文件位于 <仓库根>\smalltools\，脚本根为上两级
root = fso.GetParentFolderName(fso.GetParentFolderName(WScript.ScriptFullName))
ps1 = root & "\.codebuddy\skills\stock-daemon\daemon_start.ps1"
CreateObject("WScript.Shell").Run _
    "powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File """ & _
    ps1 & """ -Daemon all", 0, False
