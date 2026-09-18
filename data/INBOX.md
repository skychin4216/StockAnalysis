# 📥 Inbox 工作流（CS 网页 ↔ PC 后台进程互通）

> 2026-09-15 新增：用户上传文本 / 日志 / CSV，**PC 后台守护自动获取分析 + codebuddy agent 在 chat 里可读取深读**。

## 路径约定

| 目录 | 用途 | 谁来写 |
|---|---|---|
| `data/_inbox/` | **上传入口**：把要分析的素材丢到这里 | 用户（你） |
| `data/_outbox/` | **分析结果**：JSON 结构化 + MD 可读报告 | PC 后台 daemon 自动写 |
| `data/_processed/` | **归档**：处理过的原文件，按日期分目录 | PC 后台 daemon 自动移 |

> CS 沙箱与 PC 本地共享同一仓库根目录（`/workspace/StockAnalysis/`），所以**两边看到的路径完全一样**，互相同步。

## 工作流（端到端）

### 1. 上传素材
CS 网页里：**右键 → Upload Files → 选文件**，拖进 `data/_inbox/`
或直接在 CS 终端粘贴文本保存：
```bash
cat > data/_inbox/选股问题.txt << 'EOF'
今天中际旭创为什么没入选？是不是美元加息影响光模块？
EOF
```
PC 本地：直接拖文件到 `E:\Android\work\dev\StockAnalysis\data\_inbox\`

### 2. 自动分析（无需你手动触发）
PC 后台 daemon **每 60 秒**扫一次 `_inbox/`，自动：
- 按扩展名分类（`.txt/.md/.log/.csv/...`）→ 启发式分析
- 写两份产物到 `_outbox/`：
  - `原文件名.json` —— 结构化指标（行数/字数/数字/关键词/堆栈）
  - `原文件名.md` —— 可读报告
- 原文件归档到 `_processed/YYYY-MM-DD/`
- **微信群推送概要**：「📥 inbox 已处理 X」

### 3. 你在 CS chat 里深读 / 追问
最自然的姿势——直接跟 codebuddy agent 对话：

```
你: 看看 inbox 里最近分析的报告
   (agent 会用 search_file 扫 _outbox/*.md，列出最近 3 份)

你: 分析一下 outbox/选股问题.md.json，告诉我是否真的因为美元加息
   (agent 会读 json 提数字 + 关键词命中)

你: 帮我把今天 inbox 处理的所有文件汇总到一份日报
   (agent 会遍历 _outbox/ + _processed/，交叉引用)
```

## 启发式分析覆盖什么？

| 文件类型 | 提取字段 |
|---|---|
| 文本（.txt/.md/.json/.py 等） | 行数、字数、数字命中、价格/金额、关键词命中（涨停/跌停/破位/营收/Q1-Q4/AI/PCB/医药/错误等）、堆栈首条、时间戳 |
| 日志（.log/.err/.out） | 总行数、ERROR/WARN/INFO 计数、时间范围、首条错误上下文、错误行样本 |
| 表格（.csv/.tsv） | 列头、前 3 行样本、各列类型推断 + 数字列 min/max/mean |

## 局限性

- **不依赖 LLM**：分析是秒级正则启发式，**不会真懂语义**。要深度解读 → 在 CS chat 里让 agent 读 `_outbox/*.md` 二次处理。
- **不处理图片/二进制**：上传 `.png/.exe/.zip` 会被归档但不分析。如需处理图片请用 codebuddy 的 read_file 直接喂给 agent。
- **不重复处理**：文件一旦进 `_processed/` 就不再分析，需要复盘就把它移回 `_inbox/`。

## 故障排查

| 现象 | 排查 |
|---|---|
| 微信没收到 inbox 推送 | 查 `data/_inbox/` 是否有文件 + `_daemon_publish.log` 最后几行 + `smalltools/_records/_push_queue.jsonl` 队列 |
| agent 找不到 outbox 报告 | `_inbox` 还没处理（daemon 还没扫到）；agent 应等待或主动 `python _inbox_analyze.py` 手动触发 |
| 文件一直留在 `_inbox` | 看 daemon 进程是否在跑（`daemon_status.ps1`）；文件名以 `.` 开头或 `.tmp` 结尾会被跳过 |
| 想重新分析某个文件 | `python smalltools\_inbox_analyze.py 选股问题.txt`（手动触发，无需等守护） |