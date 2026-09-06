# C/S「登录即聊」配置清单（APK ↔ PC AutoQuant）

> 目标：像微信/QQ 一样，手机端打开 App 就能连上 PC、发消息 → PC 收到并回复 → 手机看到回复。
> 本文给出**可照做**的分步配置，覆盖局域网 + 公网两种场景。

---

## 0. 原理（先看懂再配，30 秒）

- **PC = 服务端**：GUI（`run_gui.py` 或 exe）启动时自动拉起 `DataService`，监听 `0.0.0.0:8888`，提供 HTTP 接口。
- **APK = 客户端**：每 3 秒轮询 PC 接口。发消息走 `POST /remote/msg`（写入 PC 的 inbox），回复写 outbox，APK 轮询拉到回复 → 显示在「📡 远程」面板。
- **AI 回复侧 = PC「💬 消息消费」页**：GUI 内置消息消费者，消费 inbox 待处理消息 → 交给 AI Agent（豆包/DeepSeek 等，可自调选股/行情/复盘工具）生成回复 → 写回 outbox 回传手机。
- **鉴权**：每次请求带 `X-Token`。Token 存在 PC 的 `AutoQuant/data/remote_token.txt`，APK 内置预置同一 token → 开箱即用。

```
手机 APK ──HTTP(3秒轮询)──▶ PC DataService(0.0.0.0:8888)
    ▲                          │ 任务队列 RemoteControl / 消息桥 inbox
    │                          ▼
    └────────── outbox 回复 ◀── PC「💬 消息消费」页 → AI Agent 回复
```

---

## 1. PC 端配置（做一次，共 5 步）

### ① 启动服务端
- 源码运行：`python run_gui.py`（等日志出现 `数据服务启动成功: http://0.0.0.0:8888`）
- exe 运行：双击 `AutoQuant-GUI.exe`，GUI 加载完成即已自动监听 8888。

> 窗口要**保持打开**，最小化可以；关闭 = PC 端下线，手机立即"连接失败"。

### ② 查 PC 局域网 IP
打开 PowerShell 输入：

```powershell
ipconfig
```

找「无线局域网适配器 WLAN / 以太网适配器」的 **IPv4 地址**，形如 `192.168.1.3`。记下来，下一步填入手机。

> 提示：PC 建议在路由器里设「DHCP 静态绑定」，把 IP 固定为 `192.168.1.3`，否则 PC 重启后 IP 可能变。

### ③ 查 Token（一般不用动）
```powershell
Get-Content "AutoQuant\data\remote_token.txt"
```
当前为 `735c3369cb6347fb`，与 APK 预置一致，**两端已配对，无需修改**。
（若文件不存在：首次启动服务时会自动生成；APK 那边要同步填成这个值。）

### ④ 放行防火墙（8888 入站）
首次运行 GUI 弹出 Windows 安全警报时选 **「允许访问」**（专用网络）。
若已错过，手动添加：

```powershell
# 管理员 PowerShell
New-NetFirewallRule -DisplayName "AutoQuant 8888" -Direction Inbound -Action Allow `
  -Protocol TCP -LocalPort 8888 -Profile Private
```

验证：同机浏览器访问 `http://127.0.0.1:8888/status`，应返回 JSON（不带 token 的健康检查）。

### ⑤ 启动「AI 回复侧」消息消费者（真聊天必需）
GUI 右栏切到 **「💬 消息消费」** 页 → 下拉选 AI Provider（「默认」=自动选择已配 Key 的；未配 Key 先在 **🤖 AI 助手** 页填写，或把 Key 写入 `ai_keys.properties`）→ 点 **「▶ 启动消费」**。之后 APK 每条消息都会被消费 → AI 生成回复 → 回传手机显示。
- 消费者每 3 秒轮询一次；消息由 **Agent 模式**处理，可自行调用选股/板块轮动/行情/复盘等工具后给出结论。
- 停止：点「⏹ 停止消费」；关闭 GUI 会自动优雅停止。
- 纯任务控制（APK 快捷任务按钮）不依赖本页，①~④ 即可。

---

## 2. 手机端配置（做一次，共 4 步）

### ① 打开远程面板
打开 App → 进入 **AI 对话（ChatTab）** → 点右下/底部 **「📡 远程」** 按钮 → 弹出全屏远程控制对话框。
（另一入口：首页 **数据→PC参数** 页的「🎛 远程」卡片，compact 版。）

### ② 填写 PC 地址（Host）
顶部输入框：
- 同一 Wi-Fi/局域网：填 PC 的 IP **带端口**，如 `192.168.1.3:8888`
- 公网访问：填 `域名:8888` 或 `公网IP:8888`（需先做第 3 节端口映射）

> 面板会先显示「📡 本机IP: 192.168.x.x」，可据此确认 APK 与 PC 在同一网段。

### ③ 填写 Token
第二个输入框：填 PC 端 `remote_token.txt` 的内容（当前两端一致 `735c3369cb6347fb`，已自动带出，无需改）。

### ④ 保存并测试
点 **「🔗 保存并测试」**：
- 成功 → 状态显示 `✅ 已连接 AutoQuant Remote Control | token=735c3369cb6347fb`
- 失败 → 显示 `❌ 连接失败: …`，按下方排错表处理

连接成功后即可：
- **快捷任务**：一键跑「每日选股+发布 / 模拟建仓 / 季度复盘」等
- **发消息**：在 CodeBuddy 消息框输入文字 → 3 秒内轮询到回复/执行结果
- 下方任务列表可看任务状态、点「日志」看执行输出

---

## 3. 公网访问（可选：不在同一 Wi-Fi 时）

让手机用 4G/异地连 PC，任选其一：

| 方案 | 做法 | 适合 |
|---|---|---|
| 路由器端口映射 | 路由器后台「端口转发」8888 → PC 局域网IP:8888，再填公网IP | 家庭宽带（有公网IP） |
| frp / cpolar / ngrok | 内网穿透到 PC 的 8888，得到公网域名，APK 填该域名 | 无公网IP |
| 云服务器转发 | 云服务器 `socat`/Nginx 转发 8888 | 已有云主机 |

公网下 Token 是唯一口令，切勿泄露；建议后续加 HTTPS + 定期换 Token。

---

## 4. 排错表

| 现象 | 原因 | 处理 |
|---|---|---|
| `❌ Connection refused` | PC 服务没启动 | 重开 `run_gui.py`/exe，确认日志有 `数据服务启动成功` |
| `❌ timeout/超时` | 手机与 PC 不在同一网段 | 对比「本机IP」与 PC `ipconfig`；改连同一路由器 |
| `❌ unauthorized (403)` | Token 不一致 | 手机 token 改成 PC `data/remote_token.txt` 内容后重测 |
| PC 能连、手机不能 | Windows 防火墙拦截 | 按 ①④ 重放行 8888 入站（专用网络） |
| 连接正常但收不到回复 | PC「💬 消息消费」未启动 / 未配 API Key | 启动该页消费；Key 在 🤖 AI 助手 页配置或写 `ai_keys.properties` |
| 回复是"未配置 API Key" | Provider 没 Key | 到 🤖 AI 助手 页配 Key 后重启消费 |
| 任务提交了没反应 | exe 是 onefile，远程任务经 `--aq-task` 复用 exe | 确认 exe 路径无中文/权限正常，看任务列表日志 |

---

## 5. 相关文件速查

| 端 | 文件 | 作用 |
|---|---|---|
| PC | `AutoQuant/data/remote_token.txt` | Token（服务端鉴权） |
| PC | `AutoQuant/data/remote_tasks.json` | 远程任务队列 |
| PC | `AutoQuant/data/cb_inbox.json` / `cb_outbox.json` | 消息桥 inbox/outbox |
| PC | `AutoQuant/autoquant/data_service.py` | HTTP 服务 + 消息桥实现 |
| PC | `AutoQuant/autoquant/gui/msg_consumer_tab.py` | 「💬 消息消费」页（消费 inbox→AI→outbox） |
| PC | `AutoQuant/autoquant/agent_loop.py` + `ai_config.py` | AI Agent 回复链路（Provider/工具） |
| PC | `AutoQuant/autoquant/remote_control.py` | 任务队列与子进程执行 |
| APK | `assets/data/app_config.json` → `remote_control` | APK 内置预置 host/port/token |
| APK | `.../stock/data/RemoteConfig.kt` | 读取/保存连接配置（JSON 优先） |
| APK | `.../strategy/trade/RemoteControlPanel.kt` | 远程面板 UI + 3 秒轮询 |
| APK | `.../stock/data/PcBridgeClient.kt` | HTTP 客户端封装 |
