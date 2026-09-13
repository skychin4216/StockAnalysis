---
name: adb-stock-data-commands
description: 通过 adb 广播命令控制 StockAnalysis Android 应用的数据链路（检查数据新鲜度 → 增量下载 → 同步腾讯云 COS），并支持查看数据状态、触发全球 Top10 机构研报收集。当用户说"触发数据刷新"、"同步数据到云端"、"检查数据新鲜度"、"数据没更新帮我拉一下"、"上传备份数据"、"查看数据状态"、"更新机构研报"等时使用。适用于连接了已安装 StockAnalysis 应用的 Android 设备/模拟器。
---

# ADB 数据命令（StockAnalysis）

## 背景

StockAnalysis App 内置 `CommandReceiver`（广播接收器），监听 `com.chin.stockanalysis.CMD` 广播。
通过 adb 发送该广播即可在无 UI 环境下触发数据维护闭环，结果输出到 Logcat。

## 使用前提

1. 设备/模拟器已连接：`adb devices` 能看到目标设备
2. 应用已安装：`adb shell pm list packages | findstr stockanalysis`（Windows）/ `grep stockanalysis`（macOS/Linux）
3. 应用进程最好已启动过（首次安装后建议先打开一次 App，确保 DataConfig 已加载）

## 命令清单

### 1. 完整闭环：检查新鲜度 → 增量下载 → 同步 COS

```bash
adb shell am broadcast -a com.chin.stockanalysis.CMD --es command refresh
```

- 检查 `daily_snapshot` 本地最新日期是否 >= 今天
- 非最新 → 按缺口增量下载历史数据
- 上传数据包到腾讯云 COS

### 2. 只同步 COS（上传备份）

```bash
# 普通上传（当日已传过则跳过）
adb shell am broadcast -a com.chin.stockanalysis.CMD --es command sync

# 强制重传
adb shell am broadcast -a com.chin.stockanalysis.CMD --es command sync --ez force true
```

### 3. 只增量下载缺失交易日

```bash
adb shell am broadcast -a com.chin.stockanalysis.CMD --es command download
```

### 4. 查看数据状态

```bash
adb shell am broadcast -a com.chin.stockanalysis.CMD --es command status
```

输出：本地数据最新日期、今天、是否新鲜、COS 是否已配置、上次上传时间。

### 5. 触发全球 Top10 机构研报收集

```bash
adb shell am broadcast -a com.chin.stockanalysis.CMD --es command news
```

抓取高盛/摩根士丹利/摩根大通等 Top10 机构研报，写入新闻因子（每日一次，重复触发自动跳过）。

### 6. 一键数据维护：同步 → 回溯 → 拟合 → 上传

```bash
adb shell am broadcast -a com.chin.stockanalysis.CMD --es command backtest --ez force true
```

- 同步本周数据（增量下载缺失交易日）
- 四周期回溯（超短/短线/中线/长线，增量模式）
- 中线/长线状态矩阵拟合（网格搜索持有天数/止盈/止损）
- 输出四周期成功率对比与长线 vs 短线分析
- 上传数据包到腾讯云 COS

适合每日收盘后一键完成「数据同步 + 策略校验 + 云端备份」。

## 查看执行结果

```bash
adb logcat -s CommandReceiver:* *:S
```

## 常见问题排查

| 现象 | 处理 |
|------|------|
| logcat 无输出 | 确认 action 正确、App 进程已启动、`adb devices` 设备在线 |
| `COS 未配置` | 需先在 App 设置中完成 COS 参数配置（或校验 `app_config.json` 的 `cloud_sync`） |
| `今日已上传过` | 加 `--ez force true` 强制重传 |
| 广播 Permission Denial | 无需额外权限；若被拦截，确保 action 为 `com.chin.stockanalysis.CMD` |
