# APK ⇄ EXE / CodeBuddy 实时交互 C/S 架构设计

> 状态：✅ 服务端（PC）已实现并通过端到端测试；APK 客户端已实现（RemoteControlDialog）
> 目标：在 APK 上直接提交任务给 PC（exe/codebuddy）执行，并实时查看任务状态与日志。

## 1. 总体架构

```
┌──────────────┐    局域网 HTTP      ┌────────────────────────────┐      ┌──────────────────┐
│    APK       │  ────────────────▶  │   AutoQuant-GUI.exe        │      │ 执行器           │
│  (客户端)     │  ◀────────────────   │   data_service.py :8888    │ ───▶ │  smalltools 脚本 │
│              │    JSON + 长轮询     │   remote_control.py        │      │  CodeBuddy CLI   │
│ 量化工作台    │                    │   (任务队列/状态/日志)      │      │  adb 广播        │
│ RemoteControl│                    └────────────────────────────┘      └──────────────────┘
│  Dialog      │                        ▲ 文件落盘: data/remote_tasks.json
└──────────────┘                        └ data/remote_token.txt (鉴权)
```

- **APK 作为客户端**，PC 端 exe 作为服务端（同局域网直连，不依赖公网）。
- APK 已有的 `PcBridgeClient`（候选清单拉取）扩展出完整远程控制 API。
- 任务实际执行在 PC 端：smalltools 脚本（选股/回测/拟合/刷新/上传）或 CodeBuddy CLI（AI 任务）。

## 2. 传输与鉴权

| 项 | 方案 |
|----|------|
| 协议 | HTTP + JSON（GET 只读 / POST 动作），后续可升级 WebSocket 推送 |
| 地址 | `http://<PC局域网IP>:8888`（PC 端启动 data_service 时提示） |
| 鉴权 | 请求头 `X-Token`；Token 由 PC 端首启生成，落盘 `AutoQuant/data/remote_token.txt`，APK 手动填写一次 |
| 超时 | 提交/查询 10s；日志轮询 2s 间隔 |
| 跨网兜底 | 未来可选：COS 中转通道（APK 上传任务指令 → COS → PC 定时拉取执行） |

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
remoteStatus(host, token)   // 校验连接
taskTypes(host, token)      // 任务类型
submitTask(host, token, type, params)
getTask(host, token, id)
listTasks(host, token, state?)
cancelTask(host, token, id)
taskLogs(host, token, id, cursor)  // 增量日志
```

### 4.2 UI：`RemoteControlDialog.kt`（新增）
- 入口：工作台「📋 PC 候选」→ 标题栏「🎛 远程」
- 功能：连接配置（host/token 保存）、快捷任务按钮、任务列表卡片（状态着色/点击看日志/取消）、日志实时轮询
- 轮询策略：任务运行中每 2s 拉增量日志；结束自动刷新列表

## 5. 如何启用（三步）

1. **PC 端**：启动 AutoQuant-GUI.exe（或 `python autoquant/data_service.py`），记录端口 `8888` 和 token。
   ```
   AutoQuant/data/remote_token.txt  ← 首次启动自动生成
   ```
2. **APK**：工作台 → PC 候选 → 🎛 远程 → 填 PC 地址 + Token → 「保存并测试」。
3. **使用**：点快捷任务（如「📊 每日选股+发布」）→ 提交 → 实时看日志 → 任务结束查看结果。

## 6. 后续演进（Roadmap）

| 阶段 | 内容 | 状态 |
|------|------|------|
| P0 | REST 任务提交/状态/日志/取消 + APK Dialog | ✅ 已实现 |
| P1 | WebSocket 实时日志推送（替代 2s 轮询） | ⏳ 待做 |
| P2 | CodeBuddy CLI 桥接（PC 安装 codebuddy 后自动可用） | ⏳ 依赖安装 |
| P3 | COS 中转通道（跨局域网：APK→COS→PC→COS→APK） | ⏳ 待做 |
| P4 | 任务结果结构化回传（候选 JSON 直接落 APK 工作台） | ⏳ 待做 |

## 7. 安全注意

- 仅建议局域网使用；若需公网暴露，前置反向代理 + HTTPS + 强制 token。
- `shell.cmd` / `codebuddy.prompt` 可执行任意命令，APK 端 UI 不暴露这两个类型（仅 PC 端内部/高级用户可用）。
- Token 落盘为明文，敏感环境建议接入系统 KeyStore。
