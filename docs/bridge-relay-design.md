# APK ↔ PC(exe/CodeBuddy) 联网实时通讯：设计定稿（纯云端中继，无局域网、不填 IP）

> 目标：**两端只要能上外网，就自动互通。不填 IP、不要求同一 WiFi、不要求 PC 有公网 IP。**
> 状态：方案定稿（2026-09-13 修订：**彻底取消局域网/IP 模式**）。落地优先级：COS 中继 v1 → MQTT v2（按需）。
>
> **本方案不存在 LAN 通道，不存在 `mode=auto`，不存在"填 IP 作为加速项"。**
> 局域网直连（`http://IP:8888`）作为**待拆除的旧链路**，与本方案无关，见第 6 节删除清单。

---

## 1. 现状：旧链路 = 局域网直连（整体废弃）

### 1.1 旧链路长什么样

```
APK                                    PC (AutoQuant exe)
┌────────────────────┐                ┌──────────────────────────────┐
│ PcBridgeClient.kt  │  http://IP:8888│ data_service.py              │
│  host/port/token   │ ──────────────▶│ ThreadingHTTPServer          │
│  SharedPreferences │  X-Token 头鉴权 │ token: data/remote_token.txt │
└────────────────────┘                └──────────────────────────────┘
```

- PC 端：`AutoQuant/autoquant/data_service.py`
  - `DEFAULT_PORT = 8888`，`ThreadingHTTPServer((host, port))`，默认监听 `0.0.0.0`（**对整个局域网开放，安全面大**）
  - 鉴权：请求头 `X-Token` == `remote_control.get_token()`（存 `AutoQuant/data/remote_token.txt`）
  - 启动日志原文：**「远程控制: 同一WiFi下 APK 填本机IP:port + token」**
- APK 端：`app/src/main/java/com/chin/stockanalysis/stock/data/PcBridgeClient.kt`
  - `saveHost/loadHost`（SharedPreferences `pc_bridge` / `exe_host`）、`saveToken/loadToken`
  - 业务方法：`remoteStatus`、`taskTypes`、`submitTask`、`getTask`、`listTasks`、`cancelTask`、`taskLogs`、`sendMsg`、`fetchMsgReplies(after)`、`fetchCandidates`、`fetchRotation`、`fetchEtfLive`、`watch()` 轮询
- APK 内置 IP 预设（**要删的东西**）：`app/src/main/assets/data/app_config.json` → `remote_control.preset_host = "192.168.1.3"`、`preset_port = 8888`、`preset_token`

### 1.2 为什么局域网模式直接淘汰（不是"降级为可选"）

| 问题 | 说明 |
|---|---|
| **必须手工填 IP** | `192.168.x.x` 只在同一网段有效；换 WiFi / PC 重启换 IP / 手机切 4G 立刻失效 |
| **不满足"联网即通"** | 用户在户外、用移动网络、或 PC 在另一地点时，局域网通道**完全不可用** |
| **被迫双通道维护** | 要维护 LAN + 中继两套传输、两套状态展示、两套排障路径，且 `mode=auto` 的判定本身又是一类新 bug |
| **PC 对局域网开端口** | `0.0.0.0:8888` 让同网段任何设备都能扫描/试探，仅靠一个 16 位 token 保护 |

> **决策：局域网通道不保留、不做 fallback，直接下线。** 只保留"联网即通"的云端中继一条路。
> 判断依据：本项目的真实使用场景是"走到哪都能用手机指挥 PC 选股"，局域网**天然不满足**，维护它是负收益。

### 1.3 微信/QQ 是怎么做到的

它们**有一个永远在线的服务端**：
1. 账号体系 → 唯一的 `账号 ID` 取代 `IP`
2. 服务端中转 → 双方各自与服务器保持连接
3. 消息路由 → 服务器把消息投递给目标账号

**结论：只要想"免 IP、只需联网"，就必须有一个双方都能从公网访问的中转。**
本项目**已经有这个中转的现成基础 = 腾讯云 COS**（见第 2 节）。

---

## 2. 已有的现成基础（关键，几乎零新增成本）

| 能力 | PC 端 | APK 端 |
|---|---|---|
| COS 配置 | `AutoQuant/cloud_config.json`（bucket `stockanalysis-1471852701-1471852701`，region `ap-guangzhou`，`prefix=stockanalysis/phone`，含 `secret_enc` 加密主密钥） | `assets/data/app_config.json` → `cloud_sync`（bucket/region/secret_id/secret_key/prefix/params_key/db_key/candidates_key/focus_key） |
| COS 读写 | `smalltools/cos_utils.py`：自实现 `sign()`(HMAC-SHA1) + `request()` + `load_cloud_config()`，支持 `COS_*` 环境变量覆盖 | `cloud/CloudSyncManager.kt` + `CosSigner.kt`：已能 PUT/GET 对象 |
| 已验证用途 | 参数回流（`cloud_upload_params.py`）、候选推送（`_publish_candidates.upload_candidates`）、数据库备份（`cloud_upload_db.py`） | 参数下载、候选下载、DB 同步 |
| 单向通知 | `smalltools/push_channel.py`（企业微信 webhook，**只出不进**） | 接收企业微信/系统通知 |

> 也就是说：**两端都已经能通过公网读写同一个 COS 桶**。把它当成"消息中转站"，就立刻做到"只要联网就通"。

---

## 3. 方案选型（单一落地路径）

| 方案 | 端到端延迟 | 新增运维 | 复用现有资产 | 适用场景 | 结论 |
|---|---|---|---|---|---|
| **A. COS 中转（轮询）** | 2~10 s | **零** | ✅ 完全复用 | 选股/研报/任务这类分钟级交互 | ✅ **唯一落地路径（v1）** |
| **B. MQTT（自建/云 broker）** | < 0.5 s | 需 broker | ❌ 需新依赖 | 需要秒级/实时推送 | ⭐ v2 升级（同一信封与方法表） |
| **C. WebSocket 云托管/云函数** | < 0.3 s | 需常驻服务 | 部分 | 实时聊天式交互 | 备选 |
| ~~D. 内网穿透（frp/cpolar）~~ | < 0.1 s | 需公网服务器 + 常驻 | ❌ | 同局域网直连一类 | **不做** |
| **E. 企业微信机器人** | 秒级 | 零 | ✅ | **仅单向通知** | 只做通知 |
| ~~F. 局域网直连 HTTP~~ | < 0.05 s | 需同 WiFi + 填 IP | ✅（已有） | — | **废弃并拆除**（第 6 节） |

**选型：A（立刻可用、零运维、只需联网）**，B 作为后续运输层升级。
本项目的交互本质是"**请求-响应**"，秒级延迟完全够用。

---

## 4. 方案 A 详细设计：COS 作为消息中转

### 4.1 键空间（Key Layout）

```
{bridge_prefix}/                      # 建议 = stockanalysis/bridge，与业务数据隔离
├── devices/
│   └── {deviceId}.json               # 设备注册/心跳：{name, lastSeen, pubTokenHint}
├── pc/inbox/{deviceId}/{seq:08d}-{msgId}.json     # APK → PC：命令
├── pc/outbox/{deviceId}/{seq:08d}-{msgId}.json    # PC → APK：命令结果（应答）
├── pc/push/{deviceId}/{seq:08d}-{msgId}.json      # PC → APK：主动推送（选股完成/研报/预警）
└── cursors/{deviceId}.json                        # 本端已消费游标：{inboxSeq, outboxSeq, pushSeq}
```

设计要点：
- **一条消息一个对象**：COS 不支持 append，单对象写避免读-改-写竞争
- **文件名带 `seq`**：`LIST` 按字典序返回即**天然有序**（`%08d` 零填充）
- **按 `deviceId` 分目录**：多设备隔离，不会读到别人的消息

### 4.2 消息格式（统一信封）

```json
{
  "v": 1,
  "msgId": "6f1c9a3e-....-....",      // UUID，幂等键
  "deviceId": "pixel8-9f2c1b",
  "seq": 1024,                         // 单调递增，本端生成
  "ts": 1757654321,                    // epoch 秒
  "kind": "cmd",                       // cmd | resp | push | ping
  "method": "task.submit",             // 见 4.3 方法表
  "params": { "taskType": "sector_scan", "params": {} },
  "replyTo": null,                     // resp 时填请求 msgId
  "ok": true,                          // resp 时有效
  "data": { },                         // resp 时有效（沿用现有 HTTP 返回体结构）
  "err": null,
  "sig": "hex(hmac_sha256(deviceToken, payload_without_sig))"
}
```

### 4.3 方法表（把现有 HTTP 路由 1:1 平移为 method）

| method | 方向 | 对应现有实现 |
|---|---|---|
| `status.get` | APK→PC | `PcBridgeClient.remoteStatus` ↔ `/remote/status` |
| `task.types` | APK→PC | `taskTypes` ↔ `/task/types` |
| `task.submit` | APK→PC | `submitTask` ↔ `/task/submit` |
| `task.get` / `task.list` / `task.cancel` / `task.logs` | APK→PC | 同名方法 |
| `msg.send` | APK→PC | `sendMsg` ↔ `/remote/msg`（交给 AI Agent / 消息消费页） |
| `msg.replies` | APK→PC | `fetchMsgReplies(after)` |
| `candidates.fetch` | APK→PC | `fetchCandidates` ↔ `/candidates` |
| `rotation.fetch` | APK→PC | `fetchRotation` ↔ `/rotation` |
| `etf.live` | APK→PC | `fetchEtfLive` ↔ `/etf_live` |
| `push.*` | PC→APK | 现有企业微信推送内容改为同时写入 `pc/push/` |

> 好处：**协议是现有 HTTP 接口的 1:1 平移**，PC 侧 handler 逻辑零改动，只是把"从 HTTP body 取"换成"从 COS 对象取"。

### 4.4 时序

```
APK                                        COS                                    PC 守护(5s 轮询)
 │ ① PUT devices/{id}.json（心跳）          │                                          │
 │────────────────────────────────────────▶│                                          │
 │ ② PUT pc/inbox/{id}/00000120-{msgId}.json│                                          │
 │────────────────────────────────────────▶│ ③ LIST pc/inbox/{id}/ (前缀)              │
 │                                          │◀─────────────────────────────────────────│
 │                                          │ ④ GET 新对象 → 校验 sig → 查幂等表 → 执行  │
 │                                          │ ⑤ PUT pc/outbox/{id}/resp-{msgId}.json    │
 │                                          │◀─────────────────────────────────────────│
 │ ⑥ LIST pc/outbox/{id}/ + GET             │                                          │
 │◀─────────────────────────────────────────│                                          │
 │ ⑦ 渲染；更新 cursors/{id}.json            │                                          │
```

**PC 主动推送**（选股出结果时）同 ⑤，写 `pc/push/{id}/...`；APK 轮询 `pc/push/` 即可。

> APK 与 PC 不需要任何地址信息，双方只需要：`bucket + region + 密钥 + deviceId`。

### 4.5 幂等 / 顺序 / 可靠性

| 问题 | 处理 |
|---|---|
| 重复投递 | 以 `msgId` 为幂等键，两端各维护已处理 `msgId` 集合（PC 落盘 `bridge_state.json`，APK 用 SharedPreferences 环形缓存 500 条） |
| 顺序 | 同端内按 `seq` 单调；跨端只保证"同 `deviceId` 内有序" |
| 消息丢失 | 不删除已处理对象，改为写 `cursors/{deviceId}.json` 记录游标；保留 N 天后由清理任务删除（避免 APK 没读到就没了） |
| 大载荷 | 单对象 ≤ 512 KB；超出（如长图/DB）走现有"文件对象 + 消息里带 key"的方式 |
| 重试 | 发送失败 → 指数退避 3 次（1s/3s/9s）→ 仍失败落**本地待发队列**，联网后续传 |
| 断网 | 无 LAN fallback（本方案不存在第二通道）；断网期间消息进入本地待发队列，恢复后自动补发 |

### 4.6 轮询节奏（省电 / 省流量）

| 场景 | 间隔 |
|---|---|
| APK 前台且停留在相关页面 | **5 s** |
| APK 前台其他页面 | 15 s |
| APK 后台 | **不轮询**（靠企业微信/系统通知唤醒，或用户打开时按 `cursors` 全量补拉） |
| PC 守护 | 固定 5 s（PC 常电常网） |

> 单次 `LIST` 响应体量级 ~1 KB，5s 一次 ≈ 0.2 KB/s，可忽略。COS 请求费为万次/分钱级，按此节奏每月成本 < 1 元。

### 4.7 安全

| 层面 | 措施 |
|---|---|
| 传输 | COS 走 HTTPS（`use_https=True` 已具备） |
| 鉴权 | 每设备独立 `deviceToken`（32 字节随机），PC 端存 `bridge_devices.json`；可一键吊销 |
| 完整性 | 消息体 `HMAC-SHA256(deviceToken, payload)` 签名，两端校验 `sig` |
| 密钥 | 优先 **STS 临时密钥**（最小权限：仅该 `bridge/` 前缀的 Get/Put/List/Delete）；退路用现有 `secret_enc` 解出的主密钥 |
| 隐私 | 载荷可选 AES-GCM 加密（密钥由 deviceToken 派生），COS 侧只见密文 |
| 越权 | 桶策略限制每个 device 前缀；`deviceId` 白名单校验 |
| **攻击面** | 因为**不再开任何监听端口**（第 6 节拆除 8888），局域网内其他设备无法扫描到 PC（旧模式 `0.0.0.0:8888` 即消除了） |

---

## 5. 升级路径 v2：MQTT（要秒级时再上）

如果需要"像微信一样点开就发、对面秒回"：

- **Broker**：自建 EMQX / 腾讯云 IoT / 公共测试 broker（`broker.emqx.io`）
- **拓扑**：`sa/{userId}/pc` 与 `sa/{userId}/{deviceId}` 两个 topic，两端各订阅对方
- **PC 端**：`paho-mqtt` 常驻线程
- **APK 端**：`org.eclipse.paho.client.mqttv3` + 前台服务保活
- **保留 COS 作为离线消息盒**：MQTT 掉线期间的消息写 COS，上线后补拉

> 关键：MQTT 只是把"运输层"从轮询换成推送，**消息信封（4.2）与方法表（4.3）完全复用**，所以是平滑升级，不推翻 v1。

---

## 6. 改造点清单（按文件）—— 含**局域网/IP 残留拆除清单**

### 6.1 新增（联网中继本体）

**PC（AutoQuant）**
| 文件 | 改动 |
|---|---|
| `AutoQuant/autoquant/cos_relay.py` | **新增**：COS `LIST/PUT/GET/DELETE` + 信封编解码 + `sig` 校验 + 幂等表 |
| `AutoQuant/autoquant/relay_worker.py` | **新增**：守护线程轮询 `pc/inbox/` → 调用现有 handler → 写 `pc/outbox/`、`pc/push/` |

**APK**
| 文件 | 改动 |
|---|---|
| `stock/data/CosRelayClient.kt` | **新增**：基于现有 `CloudSyncManager`/`CosSigner` 实现 `LIST/PUT/GET` + 信封编解码 + `sig` 签名 + 轮询循环 |

### 6.2 改造（去掉 LAN 分支，不是"并存"）

**PC（AutoQuant）**
| 文件 | 改动 |
|---|---|
| `AutoQuant/autoquant/data_service.py` | 路由 handler 抽成**纯业务函数**（不依赖 HTTP request 对象），由 relay worker 直接调用；HTTP 监听**降为 `127.0.0.1` 仅本机**（供本机调试），**不再监听 `0.0.0.0`** |
| `AutoQuant/autoquant/data_service.py` | 推送落点增加：企业微信推送的同时写 `pc/push/{deviceId}/` |
| `AutoQuant/autoquant/remote_control.py` | `remote_token.txt`（单 token）退役 → 改为 device 注册/吊销/心跳超时（`bridge_devices.json`） |
| 启动日志 | 删掉「同一WiFi下 APK 填本机IP:port + token」，改为「联网中继已启动：device=xxx, prefix=xxx」 |
| `smalltools/cos_utils.py` | 抽 `sign/request/load_cloud_config` 供 relay 复用（或直接 import） |

**APK**
| 文件 | 改动 |
|---|---|
| `stock/data/PcBridgeClient.kt` | **删除** HTTP 实现与 `PREFS="pc_bridge"` / `KEY_HOST="exe_host"` / `saveHost` / `loadHost` / `saveToken` / `loadToken`；方法签名保留（改为转发到 `CosRelayClient`），调用方无感 |
| `stock/data/RemoteConfig.kt` | **删除** `host` / `port` / `token` 三个字段及 `preset_host` / `preset_port` / `preset_token` 的读取（第 73-75 行）；只保留 `relay_prefix` 等中继配置 |
| `strategy/trade/RemoteControlPanel.kt` | **删除**「填 IP / 端口 / token」输入项与"连接测试(HTTP)"按钮；改为只显示：中继连接状态、设备名、deviceId、最后心跳时间、"重新注册设备" |
| `ui/`（设置页） | 同上：只展示中继状态，不出现任何地址输入 |
| `assets/data/app_config.json` | **删除** `remote_control.preset_host` / `preset_port` / `preset_token`；`remote_control` 只留中继字段（`relay_prefix`、`device_id` 等） |
| 其它引用点 | 全仓库 `Select-String preset_host|preset_port|preset_token|exe_host` 必须**归零** |

### 6.3 删除顺序（为什么不能先删 IP）

APK 删掉 IP/HTTP 后会**只认中继**。若中继尚未落地，删完立刻断连。
因此顺序固定为：

1. 先落地 6.1（relay 本体），跑通 `status.get` / `candidates.fetch`
2. 再把 APK 的 `PcBridgeClient` 切到 `CosRelayClient`（方法签名不变，调用方无感）
3. 确认中继可用后，执行 6.2 的**删除动作**（IP 输入 UI、`preset_*`、`exe_host`、8888 对外监听）
4. 收尾核对：`preset_host|preset_port|preset_token|exe_host` 全仓库零命中

> 也就是说：**"填 IP"被删掉是既定的，不是可选项**；只是必须与 relay 同批上线，避免中间态断连。

---

## 7. 风险与限制

| 风险 | 说明 | 缓解 |
|---|---|---|
| 延迟 2~10 s | 轮询固有 | 选股/研报本就分钟级；要秒级上 MQTT（第 5 节） |
| PC 必须在线 | 中转只解决"找人"，执行仍在 PC | PC 侧已有 `_publish_candidates.py` 盘段守护/服务常驻 |
| COS 请求配额 | LIST 频率过高会被限流 | 5s 节奏 + 前缀 LIST + 必要时上 MQTT |
| 密钥泄漏 | `cloud_config.json` 含 `secret_enc` | 确认其**不在 git 跟踪范围**；改用 STS 临时密钥 + 最小权限策略 |
| 离线补拉 | APK 后台不轮询期间的消息 | 结合企业微信通知唤醒；上线后按 `cursors` 全量补拉 |
| **无第二通道** | 本方案已无 LAN fallback，COS 故障即不可用 | 接受（局域网本就不满足"联网即通"）；靠本地待发队列 + 重试 + 后续 MQTT 双运输层 |

---

## 8. 落地顺序

| 阶段 | 内容 | 产出 |
|---|---|---|
| **P0** | PC 端 `cos_relay.py` + `relay_worker.py`；APK 端 `CosRelayClient.kt`；跑通 `status.get` / `candidates.fetch` | 证明"不填 IP、只要联网就能取到选股结果" |
| **P1** | APK 切通道（`PcBridgeClient` → 转发中继）+ 全量方法表平移 + 幂等/游标 | 日常功能全部走中继 |
| **P2** | **拆除局域网/IP 残留**（6.2 全部删除项 + 8888 降为仅本机） | 仓库内 `preset_host|preset_port|preset_token|exe_host` 零命中 |
| **P3** | PC 主动推送落 `pc/push/`（与现有企业微信推送并联） | 选股完成自动到达 APK |
| **P4** | MQTT 替换运输层（信封/方法表不变） | 秒级双向 |

---

## 附录 A：与现有文档的关系

- `docs/cs_architecture.md`、`docs/cs-login-chat-guide.md` 中"P3 COS 中转"的 TODO —— **本文档即该 TODO 的设计定稿**。
- `smalltools/push_channel.py` 的企业微信通道**保留**：它是"出站通知"的最优解，与本文"双向控制通道"互补。
- 参数回流 / 候选下载等既有 COS 用途**不受影响**（使用 `stockanalysis/` 下不同前缀）。
- 本文档 2026-09-13 修订：**删除全部局域网（LAN）方案内容**，不做 `mode=auto`、不做 IP 加速项。
