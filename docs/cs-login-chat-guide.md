# C/S「登录即聊」配置清单（APK ↔ PC AutoQuant）

> **2026-09-13 定稿并已落地：走联网中继（腾讯云 COS）。不需要填 IP、不需要填 Token、不要求同一 WiFi。**
> 设计见 [bridge-relay-design.md](bridge-relay-design.md)，落地状态见该文档 §8。
> 历史上的「局域网 / 公网端口映射 / 手动填 IP」方案**已全部删除**（PC 端 HTTP 现仅监听 `127.0.0.1`）。

> 目标：像微信/QQ 一样，手机端打开 App 就能连上 PC、发消息 → PC 收到并回复 → 手机看到回复。

---

## 0. 原理（先看懂再配，30 秒）

- **两端都主动连腾讯云 COS**，消息以「对象」形式在桶里中转，任一端只要能上网即可，**不需要 PC 有公网 IP、不需要端口映射、不需要同一网段**。
- **PC 侧 HTTP 只监听 `127.0.0.1:8888`**，仅供本机 `relay_worker` 调用，**不对局域网开放**（防火墙无需放行 8888）。
- **为什么什么都不用填**：
  - 不用填 IP —— 走 COS，不直连。
  - 不用填 Token —— `deviceToken = HMAC-SHA256(COS secret_key, "sa-bridge-v1")`，两端由各自都持有的 COS 密钥**自动派生**，天然一致。
  - deviceId 由 APK 首次运行自动生成并持久化，无需人工填写。

```
手机 APK ──┐                                        ┌── relay_worker ──▶ 127.0.0.1:8888 (本机 API)
           ├─▶ 腾讯云 COS   stockanalysis/bridge/    │         ▲
PC  (GUI) ─┘     pc/inbox/{deviceId}  (APK→PC 命令)  │         │ 任务队列 / 消息桥
                 pc/outbox/{deviceId} (PC→APK 应答)  └─────────┘
```

签名只覆盖**信封元数据**（`v|msgId|deviceId|seq|ts|kind|method|replyTo`），不签 `params`
—— 两端 JSON 序列化细节不同，签 params 会跨端对不上。

---

## 1. 前提：两端配好**同一个** COS 桶（唯一必填项）

`deviceToken` 由 `secret_key` 派生，所以**两端必须是同一个桶、同一套密钥**，否则签名对不上、消息互相看不见。

| 端 | 配置位置 | 说明 |
|---|---|---|
| PC | `AutoQuant/cloud_config.json`（推荐，支持 `secret_enc` 密文） | 回退读 `app/src/main/assets/data/app_config.json` 的 `cloud_sync` 区块；也可用环境变量 `COS_BUCKET/COS_REGION/COS_SECRET_ID/COS_SECRET_KEY` 覆盖 |
| APK | App 内「云同步」设置页（存 `cloud_sync.*`） | 安装包内置 `assets/data/app_config.json` 的 `cloud_sync` 里 `secret_id/secret_key` 默认留空，需在 App 内填一次 |

必填四项：`bucket` / `region` / `secret_id` / `secret_key`。

---

## 2. PC 端（3 步）

### ① 启动服务端
- 源码运行：`python run_gui.py`
- exe 运行：双击 `AutoQuant-GUI.exe`

日志应出现（注意已不再是 `0.0.0.0`）：

```
数据服务启动成功: http://127.0.0.1:8888（仅本机，不对局域网开放）
APK↔PC 通道: 联网中继(COS)，APK 无需填 IP/token；请另起 python -m autoquant.relay_worker 作为中继守护
```

> 窗口要**保持打开**，最小化可以；关闭 = PC 端下线，手机会「中继超时」。

### ② 启动中继守护（**新步骤，聊天/远程控制的必需项**）

```powershell
cd AutoQuant
python -m autoquant.relay_worker
```

- 它每 5 秒 `LIST pc/inbox/`，取回**验签通过**的命令 → 调用本机 `127.0.0.1:8888` 执行 → 结果写回 `pc/outbox/`。
- 常驻运行；排障时可单跑一轮看细节：`python -m autoquant.relay_worker --once`
- 手动验证中继链路是否通：`python -m autoquant.cos_relay selftest`
- **不启动它，APK 侧会一直「中继超时」**（这是最常见的漏配）。

### ③ 启动「AI 回复侧」消息消费者（真聊天必需）
GUI 右栏切到 **「💬 消息消费」** 页 → 下拉选 AI Provider（「默认」=自动选择已配 Key 的；未配 Key 先在 **🤖 AI 助手** 页填写，或把 Key 写入 `ai_keys.properties`）→ 点 **「▶ 启动消费」**。

- 消费者每 3 秒轮询一次；消息由 **Agent 模式**处理，可自行调用选股/板块轮动/行情/复盘等工具后给出结论。
- 停止：点「⏹ 停止消费」；关闭 GUI 会自动优雅停止。
- **纯任务控制**（APK 快捷任务按钮）不依赖本页，①+② 即可。

---

## 3. 手机端（2 步）

### ① 打开远程面板
打开 App → 进入 **AI 对话（ChatTab）** → 点 **「📡 远程」** 按钮 → 弹出全屏远程控制对话框。
（另一入口：首页 **数据→PC参数** 页的「🎛 远程」卡片，compact 版。）

面板顶部不再是输入框，而是中继状态：

```
📡 联网中继（无需填 IP / 无需填 Token）
两端各自联网即可，不要求同一 WiFi；PC 端需运行 python -m autoquant.relay_worker
中继设备 <机型>-<6位随机> · 上次通信 3分钟前
```

### ② 点「🔗 测试中继连接」

- 成功 → 显示 `✅ 中继已连通 AutoQuant Remote Control`
- 失败 → 显示 `❌ 中继连接失败: …`，按下方排错表处理

连接成功后即可：
- **快捷任务**：一键跑「每日选股+发布 / 滚动回测 / 季度复盘」等
- **发消息**：在 CodeBuddy 消息框输入文字 → 轮询到回复/执行结果
- 下方任务列表可看任务状态、点「日志」看执行输出

> 候选清单入口（工作台「📋 PC 候选」）同样优先走中继实时拉取；
> 中继不可用时自动回退到「直接从 COS 下载已发布的候选文件」，保证 PC 离线也能看上次结果。

---

## 4. 排错表

| 现象 | 原因 | 处理 |
|---|---|---|
| `❌ 中继连接失败: 云同步未配置（bucket/密钥缺失），联网中继不可用` | APK 没填 COS 配置 | 到 App「云同步」设置页填 bucket/region/secret_id/secret_key |
| `❌ 中继超时（90s）：PC 端 relay_worker 未运行？` | **②没启动** / PC 离线 / 两端不是同一个桶 | 在 PC 跑 `python -m autoquant.relay_worker`；确认两端 COS 配置一致 |
| 中继一直超时且 PC 日志正常 | 两端 `secret_key` 不同 → deviceToken 不一致 | 对比两端配置，确保完全一致 |
| `COS LIST HTTP 403` | 密钥无权读该桶前缀 | 给密钥授予该桶 `stockanalysis/` 读写权限 |
| 能连通但收不到回复 | PC「💬 消息消费」未启动 / 未配 API Key | 启动该页消费；Key 在 🤖 AI 助手 页配置或写 `ai_keys.properties` |
| 回复是"未配置 API Key" | Provider 没 Key | 到 🤖 AI 助手 页配 Key 后重启消费 |
| 任务提交了没反应 | exe 是 onefile，远程任务经 `--aq-task` 复用 exe | 确认 exe 路径无中文/权限正常，看任务列表日志 |
| 任务日志刷不出来 | `task.logs` 轮询依赖 ② | 确认 relay_worker 常驻 |

> 旧排错项（`Connection refused` / `timeout 因为不在同一网段` / `unauthorized 403 token 不一致` / `防火墙拦截 8888`）
> **均已随局域网方案一并作废** —— 新方案里 APK 根本不直连 PC，也不使用 `X-Token`。

---

## 5. 相关文件速查

| 端 | 文件 | 作用 |
|---|---|---|
| **通道** | `AutoQuant/autoquant/cos_relay.py` | PC 端中继**运输层**（PUT/LIST/GET、信封、deviceToken 派生、HMAC 验签） |
| **通道** | `AutoQuant/autoquant/relay_worker.py` | PC 端中继**执行体**（轮询 inbox → 本机 API → outbox；`METHODS` 方法表） |
| **通道** | `.../stock/data/CosRelayClient.kt` | APK 端中继客户端（签名与 Python 端逐字对齐） |
| **通道** | `.../stock/data/PcBridgeClient.kt` | APK 侧业务 API（全部转发中继；**无任何地址概念**） |
| PC | `AutoQuant/autoquant/data_service.py` | 本机 HTTP 服务（仅 `127.0.0.1`）+ 消息桥实现 |
| PC | `AutoQuant/data/remote_token.txt` | 本机 API 鉴权 token（**仅 relay_worker 与本机使用，APK 不再需要**） |
| PC | `AutoQuant/data/remote_tasks.json` | 远程任务队列 |
| PC | `AutoQuant/data/cb_inbox.json` / `cb_outbox.json` | 消息桥 inbox/outbox |
| PC | `AutoQuant/data/bridge_state.json` | 中继幂等表 + 游标（relay_worker 自动维护） |
| PC | `AutoQuant/autoquant/gui/msg_consumer_tab.py` | 「💬 消息消费」页（消费 inbox→AI→outbox） |
| PC | `AutoQuant/autoquant/agent_loop.py` + `ai_config.py` | AI Agent 回复链路（Provider/工具） |
| PC | `AutoQuant/autoquant/remote_control.py` | 任务队列与子进程执行 |
| APK | `.../strategy/trade/RemoteControlPanel.kt` | 远程面板 UI（中继状态 + 快捷任务 + 消息 + 任务/日志） |
| APK | `.../strategy/trade/PcCandidatesDialog.kt` | PC 候选清单（中继优先，COS 兜底） |
| APK | `assets/data/app_config.json` → `cloud_sync` | COS 桶与密钥（**中继唯一前提**） |
