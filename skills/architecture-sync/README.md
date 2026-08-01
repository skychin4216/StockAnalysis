# Architecture Sync Skill — 代碼架構變更後自動更新流程圖

## 觸發條件

當以下類型的代碼變更發生時，必須執行架構同步：

### 必須觸發更新的變更
| 變更類型 | 監控目錄 | 更新目標視圖 |
|---------|---------|------------|
| 新增/刪除/重命名 Fragment | `app/src/main/java/com/chin/stockanalysis/ui/` | UI 層視圖 |
| 新增/刪除/重命名 Agent | `app/src/main/java/com/chin/stockanalysis/agent/` | Agent 層視圖 |
| 新增/刪除/重命名 Strategy | `app/src/main/java/com/chin/stockanalysis/strategy/strategies/` | Strategy 層視圖 |
| 新增/刪除/重命名 Pipeline Node | `app/src/main/java/com/chin/stockanalysis/strategy/topology/nodes/` | Strategy 層視圖 |
| 新增/刪除/重命名 DataSource | `app/src/main/java/com/chin/stockanalysis/stock/data/sources/` | Data 層視圖 |
| 新增/刪除/重命名數據庫表 | `app/src/main/java/com/chin/stockanalysis/stock/database/StockDatabase.kt` | Database 層視圖 |
| 新增/刪除/重命名 Entity | `app/src/main/java/com/chin/stockanalysis/` (所有 Entity) | Database 層視圖 |
| 數據庫版本遷移 | `StockDatabase.kt` 中的 `MIGRATION_*` | Database 層視圖 |
| 新增/刪除 XML Pipeline 配置 | `app/src/main/assets/usecases/` | Strategy 層視圖 |
| 新增/刪除 Agent 角色 | `agent/core/AgentRole.kt` | Agent 層視圖 |
| 新增/刪除 V2 決策組件 | `agent/v2/` | Agent 層視圖 |
| IntentProcessorChain 變更 | `stock/intent/` | Data 層視圖 |
| Fragment 導航關係變更 | `ui/StockDetailNavigator.kt`, `ui/CrossTabBus.kt` | UI 層視圖 |

### 不需要觸發的變更
- 單個函數內部邏輯修改（不改變類/接口結構）
- 註釋修改
- import 語句調整
- 資源文件修改（drawable, layout XML 等）

## 執行流程

### Step 1: 掃描變更
```bash
python skills/architecture-sync/scan_architecture.py
```
腳本會掃描代碼目錄，與上次快照對比，輸出變更摘要。

### Step 2: 更新對應視圖
根據變更摘要，更新 `docs/architecture/index.html` 中對應的 SVG 圖：

1. **UI 層變更** → 更新 `<!-- VIEW: ui-layer -->` 區塊的 SVG
   - Fragment 導航樹（新增/刪除 Fragment 節點）
   - StockDetailNavigator 連線（新增導航路徑）
   - CrossTabBus 事件（新增跨 Tab 事件）

2. **Agent 層變更** → 更新 `<!-- VIEW: agent-layer -->` 區塊的 SVG
   - IntentRouter 意圖列表（新增/修改意圖類型）
   - AgentOrchestrator 執行模式（新增模式）
   - DeepAnalystEngine 子 Agent（新增/刪除子 Agent）
   - V2 決策矩陣組件（新增/刪除組件）
   - Agent 角色卡片（新增/刪除角色）

3. **Strategy 層變更** → 更新 `<!-- VIEW: strategy-layer -->` 區塊的 SVG
   - 19 策略列表（新增/刪除策略，更新策略 ID）
   - Pipeline 節點流轉圖（新增/刪除節點，修改 Tier 層級）
   - NodeType 圖例（新增類型）
   - XML 配置列表（新增/刪除配置文件）
   - 回測引擎組件（新增/刪除組件）

4. **Data 層變更** → 更新 `<!-- VIEW: data-layer -->` 區塊的 SVG
   - 數據源列表（新增/刪除 Source）
   - IntentProcessorChain handler 鏈（新增/刪除 handler）
   - 緩存/處理器組件（新增/刪除組件）

5. **Database 層變更** → 更新 `<!-- VIEW: database-layer -->` 區塊的 SVG
   - 表列表（新增/刪除表，更新字段描述）
   - 域分組（新增/移動表到不同域）
   - 遷移歷史（新增遷移步驟）
   - 版本號更新

6. **總覽變更** → 更新 `<!-- VIEW: overview -->` 區塊的 SVG
   - 層級摘要數據（策略數、表數、Agent 數等）
   - 層間依賴關係

### Step 3: 更新快照
```bash
python skills/architecture-sync/scan_architecture.py --save-snapshot
```
保存新的架構快照，供下次對比使用。

## SVG 更新規則

### 顏色方案（必須遵守）
| 層級 | 邊框色 | 用途 |
|------|-------|------|
| UI 層 | `#3b82f6` (藍) | Fragment、導航 |
| Agent 層 | `#8b5cf6` (紫) | Agent、編排、決策 |
| Strategy 層 | `#f59e0b` (橙) | 策略、Pipeline、回測 |
| Data 層 | `#10b981` (綠) | 數據源、緩存、處理 |
| Database 層 | `#ef4444` (紅) | 表、Entity、遷移 |

### 卡片格式
每個模塊卡片包含：
- 標題（類名或表名），13px 粗體
- 副標題（職責描述），11px
- 可選第三行（關鍵方法或字段），10px

### 可點擊模塊
- 跨視圖跳轉的模塊必須有 `cursor: pointer` 和 `onclick="showView('xxx')"`
- hover 時亮度提升 `filter: brightness(1.15)`

### 並行標記
- 並行執行的模塊用虛線框 `stroke-dasharray="6 3"` 分組
- 框上方標註「並行」文字

## 文件清單
- `skills/architecture-sync/README.md` — 本文件（skill 定義）
- `skills/architecture-sync/scan_architecture.py` — 架構掃描腳本
- `docs/architecture/index.html` — 架構流程圖（更新目標）
- `docs/architecture/.arch_snapshot.json` — 上次架構快照（自動生成）
