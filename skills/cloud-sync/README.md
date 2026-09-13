# Cloud Sync Skill — 云端同步 / COS / adb 数据链路

## 模块职责
与腾讯云 COS 的数据同步：选股结果/研报/缓存数据上传下载、adb 广播触发数据刷新、
云配置同步。Android 端由 `cloud/` 负责，PC 端由 `smalltools/` 的 Python 脚本负责。

## 触发条件
用户提到以下关键词时，优先查阅本 Skill：
- 云端同步 / COS / 上传备份 / 同步数据到云端
- 数据没更新帮我拉一下 / 触发数据刷新 / 查看数据状态
- adb 广播 / 更新机构研报
- 云配置 / 配置同步

## 关键文件
| 端 | 文件 | 职责 |
|----|------|------|
| Android | `app/src/main/java/com/chin/stockanalysis/cloud/CloudSyncManager.kt` | 云同步管理器 |
| Android | `app/src/main/java/com/chin/stockanalysis/cloud/CosSigner.kt` | COS 签名 |
| Android | `app/src/main/java/com/chin/stockanalysis/cloud/CommandReceiver.kt` | adb 广播接收 |
| Android | `app/src/main/java/com/chin/stockanalysis/stock/database/AppBackgroundRunner.kt` | 后台任务（含监测启动） |
| PC | `smalltools/_update_cache_inc.py` | 增量缓存更新 |
| PC | `smalltools/_publish_candidates.py` | 发布候选股 |
| PC | `smalltools/_apply_fit_results.py` | 应用拟合结果 |
| PC | `smalltools/_dag_pool_report.json` / `_last_publish.json` | 运行报告 |

## 工作流程
```
PC 脚本 (smalltools/*.py)  ⇄  COS（腾讯云对象存储）  ⇄  Android 端 (cloud/)
adb shell am broadcast ... (触发 Android 端数据刷新)  → CommandReceiver → CloudSyncManager
```

## 常见任务指引
### 1. 同步数据到云端
- Android: `CloudSyncManager` 手动触发，或 PC 端跑 `smalltools/_update_cache_inc.py`
- adb 方式：见项目 `adb-stock-data-commands` skill（安装的 skill）

### 2. 查看数据状态
- 运行 `adb shell am broadcast` 查询广播，或查看 `smalltools/_dag_pool_report.json`

### 3. 修改云同步逻辑
- Android 改 `cloud/CloudSyncManager.kt`；PC 改 `smalltools/` 对应脚本

## 文件清单
- `skills/cloud-sync/README.md` — 本文件（skill 定义）
