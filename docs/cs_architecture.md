# APK ⇄ EXE / CodeBuddy 实时交互 C/S 架构设计

> 状态：✅ 服务端（PC）已实现并通过端到端测试；APK 客户端已实现（RemoteControlDialog）
> 目标：在 APK 上直接提交任务给 PC（exe/codebuddy）执行，并实时查看任务状态与日志。

## 1. 总体架构

```
┌──────────────┐   联网(COS 中继)    ┌────────────────────────────┐      ┌──────────────────┐
│    APK       │  ────────────────▶  │   AutoQuant-GUI.exe        │      │ 执行器           │
│  (客户端)     │  ◀────────────────   │   relay_worker.py (守护)    │ ───▶ │  smalltools 脚本 │
│              │   命令/应答信封      │        │ 127.0.0.1:8888    │      │  CodeBuddy CLI   │
│ 量化工作台    │                     │        ▼  data_service.py  │      │  adb 广播        │
│ RemoteControl│                     │   remote_control.py        │      └──────────────────┘
│  Dialog      │                     │   (任务队列/状态/日志)      │
└──────────────┘                     └────────────────────────────┘
      │                              ▲ 文件落盘: data/remote_tasks.json
      │                              └ data/remote_token.txt (仅本机 relay_worker 使用)
      └── 腾讯云 COS: stockanalysis/bridge/pc/{inbox,outbox}/{deviceId}/
```

- **APK 作为客户端**，PC 端 exe 作为服务端；两端**各自联网**经腾讯云 COS 中继互通 —— 不要求同一局域网、不需要 PC 有公网 IP、**APK 无需填 IP / Token**。
- APK 业务统一经 `PcBridgeClient` → `CosRelayClient`；PC 侧 `relay_worker.py` 轮询 COS 并把命令落到本机 API。
- 任务实际执行在 PC 端：smalltools 脚本（选股/回测/拟合/刷新/上传）或 CodeBuddy CLI（AI 任务）。

## 2. 传输与鉴权

| 项 | 方案 |
|----|------|
| 协议 | 信封(JSON) 经 COS 对象箱体中转：`pc/inbox/{deviceId}` 命令、`pc/outbox/{deviceId}` 应答；**PC 本机** HTTP(JSON) 仅供 relay_worker 调用 |
| 地址 | **无**。APK 不持有任何地址；PC 本机 API 仅监听 `127.0.0.1:8888`（不对局域网开放） |
| 鉴权 | 每设备 `deviceToken = HMAC-SHA256(COS secret_key, "sa-bridge-v1")`，两端**自动派生、用户无需填写**；信封带 HMAC 元数据签名 |
| 超时 | 中继单条命令 90s（含内部轮询）；relay_worker 轮询间隔 5s |
| 跨网 | 原生支持（COS 即跨网通道），无需端口映射 / 内网穿透 |

## 3. 任务协议（REST）

### 3.1 提交任务
```
POST /task/submit
{ "task_type": "select.run", "params": {}, "requester": "apk" }
→ { "ok": true, "task_id": "9b03f2342924", "task": {...} }
```

### 3.2 查询 / 列表 / 取消 / 日志
```
GET  /task/get?id=xxx        → { id, state, progress, exit_code, ... }
POST /task/get  {"id":"..."} → 同上
GET  /task/list?state=running→ { tasks:[...] }
POST /task/cancel {"id":"..."} → { ok:true }
GET  /task/logs?id=xxx&cursor=0 → { logs:[{ts,line}...], cursor, state }
GET  /task/types             → 可用任务类型
GET  /remote/status          → 服务状态 + token 回显（用于 APK 校验）
```

### 3.3 状态机
```
queued → running → success
                  → failed
                  → cancelled（支持中途终止子进程）
```

### 3.4 内置任务类型（TASK_TYPES 可扩展）
| task_type | 名称 | 实际命令 |
|-----------|------|----------|
| `select.run` | 每日选股+发布 | `_publish_candidates.py --once`（选股→微信→COS） |
| `select.dry` | 选股(仅清单) | `--once --no-push` |
| `backtest.walk` | 三年滚动回测 | `_walk_forward.py` |
| `backtest.quarter` | 季度复盘 | `_recent_regime.py` |
| `fit.auto` | 自动拟合参数 | `auto_fit_backtest.py` |
| `data.refresh` | 增量刷新K线 | `_incremental_fetch.py` |
| `cloud.upload` | 上传数据到COS | `cloud_upload_params.py` |
| `adb.apk_refresh` | 触发APK刷新 | `adb shell am broadcast -a ...`（需手机连接PC） |
| `codebuddy.prompt` | CodeBuddy CLI 执行 | `codebuddy run <prompt>`（PC 需安装 CodeBuddy CLI） |
| `shell.cmd` | 自定义命令 | 由 `params.command` 指定（谨慎使用） |

## 4. APK 端实现

### 4.1 网络层：`PcBridgeClient.kt`（已扩展）
```kotlin
// 新增方法（全部 suspend，OkHttp）
remoteStatus()               // 校验中继连通
taskTypes()                  // 任务类型
submitTask(type, params)
getTask(id)
listTasks(state?)
cancelTask(id)
taskLogs(id, cursor)         // 增量日志
```

### 4.2 UI：`RemoteControlDialog.kt` / `RemoteControlPanel.kt`
- 入口：工作台「📋 PC 候选」→ 标题栏「🎛 远程」；AI 对话页「📡 远程」
- 功能：**中继状态展示（已无 host/token 输入项）**、快捷任务按钮、任务列表卡片（状态着色/点击看日志/取消）、日志实时轮询
- 轮询策略：任务运行中每 2s 拉增量日志；结束自动刷新列表

## 5. 如何启用（三步）

1. **两端配好同一个 COS 桶**（`bucket`/`region`/`secret_id`/`secret_key`）—— 中继的唯一前提。
   PC 侧 `AutoQuant/cloud_config.json`；APK 侧 App 内「云同步」设置页。
2. **PC 端**：启动 AutoQuant-GUI.exe（本机 API `127.0.0.1:8888`），并另起中继守护
   `python -m autoquant.relay_worker`。
3. **APK**：📡 远程 → 「🔗 测试中继连接」→ 成功后点快捷任务（如「📊 每日选股+发布」）→ 实时看日志 → 任务结束查看结果。

逐步图解见 [cs-login-chat-guide.md](cs-login-chat-guide.md)。

## 6. 后续演进（Roadmap）

| 阶段 | 内容 | 状态 |
|------|------|------|
| P0 | REST 任务提交/状态/日志/取消 + APK Dialog | ✅ 已实现 |
| P1 | WebSocket 实时日志推送（替代 2s 轮询） | ⏳ 待做 |
| P2 | CodeBuddy CLI 桥接（PC 安装 codebuddy 后自动可用） | ⏳ 依赖安装 |
| P3 | COS 中转通道（跨网：APK ⇄ COS ⇄ relay_worker ⇄ PC） | ✅ 已实现（2026-09-13；局域网/IP 方案已删除） |
| P4 | 任务结果结构化回传（候选 JSON 直接落 APK 工作台） | ✅ 已实现（2026-09-14：`candidates.fetch` 回拉 + `pc/push` 主动推送落 `PcCandidatesDialog`；详见 [bridge-relay-design.md](bridge-relay-design.md)） |

## 7. 安全注意

- 通道为「两端各自主动连 COS」，**PC 不再对外开端口**（本机 API 仅 `127.0.0.1`），因此无需反向代理 / 端口映射。
- 中继通道的完整性依赖 COS 桶写权限：请确认 `AutoQuant/cloud_config.json` 与 `ai_keys.properties` **不在 git 跟踪范围**。
- `shell.cmd` / `codebuddy.prompt` 可执行任意命令，APK 端 UI 不暴露这两个类型（仅 PC 端内部/高级用户可用）。
- Token 落盘为明文，敏感环境建议接入系统 KeyStore。
