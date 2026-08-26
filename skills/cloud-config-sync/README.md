# Cloud Config Sync Skill — COS 密钥来源与云端数据获取

## 触发条件

当需要以下任一操作时，按本 Skill 的固定链路执行（**密钥来源是固定的，勿再四处寻找**）：
- 从腾讯云 COS 下载手机上传的数据包（当日 DB + 日志）做选股/订单/持仓回溯分析
- 排查「上传到 COS 崩溃 / 下载失败 / 签名错误」类问题
- 向手机端注入/修改云端密钥（COS / AI Provider）
- 分析板块排名、订单生成、选股质量等历史问题

## 密钥来源（三处，全部本地，不入 git）

| 文件 | 用途 | 说明 |
|------|------|------|
| `keystore.properties`（项目根，被 gitignore） | **COS 密钥**：`tencent.secretId` / `tencent.secretKey` | PC 端脚本直读；**APK 端不直接用它** |
| `keystore.properties` → `SECRET_MASTER_KEY` | APK 端解密主密钥（hex，BuildConfig 注入） | 用于解密 `secrets.enc` |
| `AutoQuant/ai_keys.properties` | `secret_master_key`（PC 端 AES 主密钥，hex） | 供 `secrets_util.py` 加解密 `secrets.enc` 使用 |

## 配置链路（手机端）

```
assets/data/app_config.json   ← 明文配置：COS bucket/region/prefix，secret 字段留空占位
assets/data/secrets.enc       ← AES-256-GCM 密文 base64(nonce(12B)||ciphertext-with-tag)
        │  由 BuildConfig.SECRET_MASTER_KEY 解密（keystore.properties 注入）
        ▼
DataConfig.load() → loadSecrets()  注入 cloud_sync.secret_id / secret_key 覆盖空占位
        ▼
CloudSyncManager  用 secretId/secretKey 对 COS 做签名上传
```

- APK 端解密实现：`config/DataConfig.kt` 的 `loadSecrets()`（AES/GCM/NoPadding）
- PC 端对应工具：`smalltools/secrets_util.py`（`decrypt_payload()` / `encrypt_payload()`）

## COS 参数（固定）

```
bucket  : stockanalysis-1471852701-1471852701
region  : ap-guangzhou
prefix  : stockanalysis/phone/
数据包  : phone_yyyymmdd_HHMMSS.zip（zip 内为 data.json + 当日 log_yyyyMMdd.txt）
data.json 内容：strategy_trade_orders / daily_period_result / real_positions /
             strategy_trade_backtests / strategy_trade_fitting_params /
             t_trade_recommendations / t_buy_sell_history / news_* 等表导出
```

## 常用命令

```powershell
# 1) 下载最近 N 个数据包（PC 端，密钥来自 keystore.properties）
cd <repo root>
python smalltools/cloud_download.py --max 5 --secret-id "<tencent.secretId>" --secret-key "<tencent.secretKey>"

# 2) 查看数据包表结构与字段（snake_case）
python -c "import json,os; d=json.load(open(os.path.join('smalltools','_records','cloud','phone_YYYYMMDD_HHMMSS','data.json'),encoding='utf-8')); [print(k, len(v)) for k,v in d.items()]"
```

> `cloud_download.py` 下载到 `smalltools/_records/cloud/<phone_xxx>/`，支持 `--max/--date` 过滤。

## 已知注意点（避免踩坑）

- **`app_config.json` 里 `cloud_sync.secret_id/secret_key` 是空占位**，不要误以为没配置；真正密钥在 `secrets.enc`（APK）或 `keystore.properties`（PC）。
- **板块排名表 `sector_daily_record` 已存在**（板块每日 change_pct/净流入/hot_score/composite_score/rank/is_hot/consecutive_hot_days），由 `SectorRotationEngine.saveDailySectorData` 写入；**但上传数据包目前不含它**，回溯板块排名需先补 DataExportImport 导出（2026-08-25 待办）。
- 手机上传失败日志在 `filesDir/logs/log_yyyyMMdd.txt`（`FileLogger` 实时抓 logcat，每次启动清空上次日志），PC 侧排查用 `adb logcat --pid=<pid>`。
- 密钥属敏感信息，**在文档/对话中不要输出明文值**。

## 文件清单

| 文件 | 作用 |
|------|------|
| `app/.../cloud/CloudSyncManager.kt` | 手机端上传（打包 DB+日志→COS） |
| `app/.../config/DataConfig.kt` | 配置加载 + `secrets.enc` 解密注入 |
| `app/src/main/assets/data/app_config.json` | 明文配置（bucket/region/prefix） |
| `app/src/main/assets/data/secrets.enc` | 加密密钥密文（APK 端） |
| `keystore.properties`（gitignore） | COS 密钥 + SECRET_MASTER_KEY（PC/构建） |
| `AutoQuant/ai_keys.properties`（gitignore） | PC 端 AES 主密钥 |
| `smalltools/secrets_util.py` | 密文加解密工具 |
| `smalltools/cloud_download.py` | PC 端 COS 下载工具 |
| `AutoQuant/cloudsync/` | exe 云同步包（下载手机数据→自动拟合→参数回流，勿删） |
