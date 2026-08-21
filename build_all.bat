@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM ============================================================
REM   一键打包脚本：AutoQuant EXE + Android APK
REM   用法：
REM     build_all.bat            只编译 AutoQuant-GUI.exe + APK
REM     build_all.bat all        编译全部 4 个 EXE + APK
REM ============================================================

set "ROOT=%~dp0"
set "AUTODIR=%ROOT%AutoQuant"
set "APK_OUT=%ROOT%app\build\outputs\apk\release\app-release.apk"
set "BUILD_ALL=%1"

echo ============================================
echo   一键打包 AutoQuant EXE + Android APK
echo ============================================
echo.

REM ---------- 1/2 编译 EXE ----------
echo [1/2] 编译 EXE ...
cd /d "%AUTODIR%"

REM 检查 PyInstaller
python -m pip install pyinstaller -q >nul 2>&1

REM 清理旧构建产物
if exist build rmdir /s /q build
if exist dist rmdir /s /q dist

REM ---- 主程序：AutoQuant-GUI.exe（含 Agent 扩展工具）----
echo.
echo   -- 构建 AutoQuant-GUI.exe (含 Agent 工具集) ...
python -m PyInstaller --onefile --noconfirm --noconsole ^
  --exclude-module PySide6 ^
  --hidden-import backtest_selection ^
  --hidden-import charset_normalizer ^
  --hidden-import autoquant.ai_config ^
  --hidden-import autoquant.ai_client ^
  --hidden-import autoquant.agent_tools ^
  --hidden-import autoquant.agent_loop ^
  --hidden-import autoquant.gui.agent_tab ^
  --hidden-import smalltools.cos_utils ^
  --hidden-import smalltools.cloud_download ^
  --hidden-import smalltools.cloud_upload_params ^
  --hidden-import smalltools.build_app_params ^
  --hidden-import smalltools.auto_cloud_pipeline ^
  --hidden-import smalltools.secrets_util ^
  --hidden-import auto_fit_backtest ^
  --paths "%ROOT%smalltools" ^
  --hidden-import _full_cycle_backtest ^
  --hidden-import _walk_forward ^
  --hidden-import backtest_guangmo ^
  --add-data "%ROOT%smalltools\_kline_cache.json;." ^
  --add-data "%ROOT%AutoQuant\cloud_config.json;." ^
  --collect-data akshare ^
  --add-data "data;data" ^
  --name "AutoQuant-GUI" run_gui.py
if exist "dist\AutoQuant-GUI.exe" (
    echo   [OK] AutoQuant-GUI.exe 编译成功
) else (
    echo   [FAIL] AutoQuant-GUI.exe 编译失败
    exit /b 1
)

REM ---- 可选：其余 EXE（build_all.bat all 时启用）----
if /i "%BUILD_ALL%"=="all" (
    echo.
    echo   -- 构建 AutoQuant-Screen.exe ...
    python -m PyInstaller --onefile --noconfirm --exclude-module PySide6 --collect-data akshare --add-data "data;data" --name "AutoQuant-Screen" run_screen_simple.py
    if exist "dist\AutoQuant-Screen.exe" (echo   [OK] AutoQuant-Screen.exe 编译成功) else (echo   [FAIL] AutoQuant-Screen.exe 编译失败)

    echo.
    echo   -- 构建 AutoQuant-Qlib.exe ...
    python -m PyInstaller --onefile --noconfirm --exclude-module PySide6 --name "AutoQuant-Qlib" run_qlib_simple.py
    if exist "dist\AutoQuant-Qlib.exe" (echo   [OK] AutoQuant-Qlib.exe 编译成功) else (echo   [FAIL] AutoQuant-Qlib.exe 编译失败)

    echo.
    echo   -- 构建 AutoQuant-VNPY.exe ...
    python -m PyInstaller --onefile --noconfirm --exclude-module PySide6 --name "AutoQuant-VNPY" run_vnpy_simple.py
    if exist "dist\AutoQuant-VNPY.exe" (echo   [OK] AutoQuant-VNPY.exe 编译成功) else (echo   [FAIL] AutoQuant-VNPY.exe 编译失败)
)

REM ---------- 2/2 编译 APK ----------
echo.
echo [2/2] 编译 Android APK (assembleRelease) ...
cd /d "%ROOT%"

REM 无 keystore.properties 时自动回退 debug 签名，仍可出包
call gradlew.bat :app:assembleRelease
if errorlevel 1 (
    echo   [FAIL] APK 编译失败（查看上方 Gradle 报错）
    exit /b 1
)

if exist "%APK_OUT%" (
    echo   [OK] APK 编译成功: %APK_OUT%
) else (
    echo   [WARN] 未找到 %APK_OUT% ，请检查 app\build\outputs\apk\release\ 目录
)

echo.
echo ============================================
echo   打包完成！
echo.
echo   EXE:
for %%f in ("%AUTODIR%dist\*.exe") do echo     %%~nxf  -^>  %%f
echo.
echo   APK:
if exist "%APK_OUT%" echo     app-release.apk  -^>  %APK_OUT%
echo ============================================
echo.
pause
