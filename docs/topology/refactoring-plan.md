# 策略 Tab 重構計劃

> 創建日期: 2026-07-12
> 狀態: 進行中

---

## 一、背景

當前策略 Tab 包含 3 個子 Tab：量化選股 / 中線量化 / 短線量化。
經過 DAG Pipeline 重構後，需要統一架構並優化 UI 布局。

### 已完成的基礎工作

- DagPipeline 執行引擎（拓撲排序 + 分層並行）
- PipelineXmlParser V1/V2 解析器
- NodeRegistry（19 個 module 註冊）
- MidTermPipelineNodes（12 個 Node，含股票日誌）
- 臨時開關（FeatureFlagManager.useDagPipelineMidTerm）
- TopologyEditorActivity（986 行，功能完整的編輯器原型）

### 當前問題

1. UseCaseLoader 路徑 bug（`"topologies/"` → `"usecases/"`）— **已修復**
2. 無 Pipeline UI 入口（TopologyEditorActivity 是孤島）
3. 量化選股不走 UseCaseLoader（直接用 StrategyEngine）
4. 策略管理分散在各 Fragment 中
5. 按鈕行功能重疊（回溯/擬合/數據 獨立按鈕）

---

## 二、7 項需求清單

### 需求 1: 修復 DAG Pipeline 執行失敗（已完成）

- **問題**: UseCaseLoader 在 `assets/topologies/` 找 XML，實際在 `assets/usecases/`
- **修復**: `UseCaseLoader.kt` 中 `"topologies"` → `"usecases"`
- **狀態**: ✅ 已完成

### 需求 2: 建倉右側加 Pipeline 按鈕

- **位置**: `QuantFragmentBase.createButtonRow()` — 「建倉」右側
- **行為**: 點擊 → 啟動 `TopologyEditorActivity`，傳入當前 pipeline 名稱
- **適用**: 中線/短線共用（同一個基類）
- **UI**: 按鈕顏色 `#6A1B9A`，文字 `🔧 Pipeline`
- **依賴**: 需要給 TopologyEditorActivity 添加 Intent 參數支持

### 需求 3: 策略 Tab 加懸浮策略管理控件

- **位置**: 策略 Tab 右下角 FAB（FloatingActionButton）
- **行為**: 點擊 → 展開策略列表面板（底部 Dialog 或 BottomSheet）
- **功能**:
  - 查看當前支持的策略（列表 + 啟用/禁用開關）
  - 添加自定義策略（名稱 + 描述）
  - 刪除自定義策略
- **目的**: 每個量化（中線/短線/長線）公用同一個策略池
- **用戶可以在 Pipeline 裡修改 pipeline，添加支持的 pipeline 到策略裡**

### 需求 4: 回溯/擬合/數據管理合併到「數據」按鈕菜單

- **位置**: `QuantFragmentBase.createButtonRow()` — 「數據」按鈕
- **修改**: 將「數據」按鈕改為帶下拉菜單的按鈕（類似「賣出 ▾」）
- **菜單項目**:
  1. 數據管理（原有的數據功能）
  2. 回溯測試（原「回溯」按鈕功能）
  3. 擬合調優（原「擬合」按鈕功能）
  4. 導入歷史數據
  5. 導出報告
- **按鈕行變化**: 移除「回溯」和「擬合」獨立按鈕，保留 建倉 / 持倉 / Pipeline / 賣出 / 數據 ▾

### 需求 5: 整理量化選股現有實現文檔

- **目標**: 將量化選股的完整實現記錄到 `docs/topology/screening-implementation.md`
- **內容**: 執行流程、數據源、策略引擎、結果展示、Pipeline 架構、+策略邏輯、擬合功能
- **目的**: 後續長線選股改造參考（相關代碼暫時保留）

### 需求 6: 量化選股改為長線選股

- **UI 創建**: 與中線/短線公用（繼承 QuantFragmentBase）
- **Tab 順序**: 短線 / 中線 / 長線（需求 7）
- **具體實現**: 後續討論，先保留現有代碼
- **步驟**:
  1. 創建 `LongTermQuantFragment`（繼承 `QuantFragmentBase`）
  2. 將量化選股的核心邏輯（StrategyEngine 篩選 + AI 預測）遷移到新 Fragment
  3. 使用 UseCaseLoader（走 Pipeline 模式）
  4. 替換子 Tab

### 需求 7: 策略 Tab UI 順序調整

- **當前**: 量化選股 / 中線量化 / 短線量化
- **目標**: 短線量化 / 中線量化 / 長線選股
- **修改**: `StrategyFragment.kt` 中的子 Tab 順序

---

## 三、實施順序

### Phase 1: UI 基礎改動（不影響現有功能）

| 步驟 | 任務 | 文件 | 依賴 |
|------|------|------|------|
| 1.1 | 按鈕行加「Pipeline」按鈕 | QuantFragmentBase.kt | 無 |
| 1.2 | 回溯/擬合合併到「數據 ▾」菜單 | QuantFragmentBase.kt | 無 |
| 1.3 | TopologyEditorActivity 添加 Intent 參數 | TopologyEditorActivity.kt | 1.1 |
| 1.4 | Tab 順序改為 短線/中線/量化選股 | StrategyFragment.kt | 無 |

### Phase 2: 策略管理統一

| 步驟 | 任務 | 文件 | 依賴 |
|------|------|------|------|
| 2.1 | 策略 Tab 加 FAB 懸浮按鈕 | StrategyFragment.kt | 無 |
| 2.2 | 創建 StrategyManagerBottomSheet | 新文件 | 2.1 |
| 2.3 | 策略啟用/禁用/添加/刪除邏輯 | StrategyManagerBottomSheet.kt | 2.2 |

### Phase 3: 文檔整理

| 步驟 | 任務 | 文件 | 依賴 |
|------|------|------|------|
| 3.1 | 量化選股實現文檔 | docs/topology/screening-implementation.md | 無 |
| 3.2 | Pipeline 架構文檔 | docs/topology/pipeline-architecture.md | 無 |

### Phase 4: 長線選股（後續）

| 步驟 | 任務 | 文件 | 依賴 |
|------|------|------|------|
| 4.1 | 創建 LongTermQuantFragment | 新文件 | Phase 1 |
| 4.2 | 遷移量化選股邏輯到 LongTerm | LongTermQuantFragment.kt | 4.1 |
| 4.3 | 替換子 Tab | StrategyFragment.kt | 4.2 |

---

## 四、按鈕行變化

### 修改前（6 按鈕）

```
[建倉] [持倉] [回溯] [擬合] [賣出 ▾] [數據]
```

### 修改後（5 按鈕）

```
[建倉] [Pipeline] [持倉] [賣出 ▾] [數據 ▾]
                                  ↑
                              展開菜單:
                              - 數據管理
                              - 回溯測試
                              - 擬合調優
                              - 導入歷史數據
                              - 導出報告
```

---

## 五、文件修改清單

| 文件 | 修改內容 |
|------|---------|
| `QuantFragmentBase.kt` | 按鈕行重構：加 Pipeline 按鈕、回溯/擬合移入數據菜單 |
| `TopologyEditorActivity.kt` | 添加 Intent 參數接收（pipeline 名稱預加載） |
| `TopologyEditorViewModel.kt` | 支持從 Intent 參數初始化 pipeline |
| `StrategyFragment.kt` | Tab 順序調整 + FAB 按鈕 |
| `StrategyManagerBottomSheet.kt` | **新建**：懸浮策略管理面板 |
| `AndroidManifest.xml` | TopologyEditorActivity 添加 intent-filter |
| `fragment_strategy.xml` | 如果有 layout XML 則添加 FAB |
| `docs/topology/screening-implementation.md` | **新建**：量化選股實現文檔 |
| `docs/topology/refactoring-plan.md` | 本文件 |

---

## 六、架構目標

```
策略 Tab (StrategyFragment)
├── FAB [+策略] — 懸浮策略管理
├── ViewPager2 子 Tab:
│   ├── Tab 0: 短線量化 (ShortTermQuantFragment)
│   │   └── 按鈕行: [建倉] [Pipeline] [持倉] [賣出▾] [數據▾]
│   ├── Tab 1: 中線量化 (MidTermQuantFragment)
│   │   └── 按鈕行: [建倉] [Pipeline] [持倉] [賣出▾] [數據▾]
│   └── Tab 2: 長線選股 (LongTermQuantFragment) [後續]
│       └── 按鈕行: [建倉] [Pipeline] [持倉] [賣出▾] [數據▾]
└── TopologyEditorActivity (從 Pipeline 按鈕啟動)
    └── 可視化編輯 Pipeline 的 Node 和 Links
```

策略共用：所有量化面板共享同一個 StrategyEngine 策略池。
Pipeline 編輯：用戶可以在 TopologyEditorActivity 中修改 pipeline 拓撲，並將其綁定到策略。
