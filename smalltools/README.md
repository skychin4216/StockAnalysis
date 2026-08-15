# StockAnalysis smalltools 工具集

> 📚 **腳本全量分類索引見 [SCRIPTS.md](SCRIPTS.md)**（回測/擬合、數據拉取、盤面分析、調試工具）

## 0. 工具集總覽

`smalltools` 包含三類工具：

| 類別 | 說明 | 入口 |
|------|------|------|
| 🎨 可視化（本 README） | DAG Pipeline / 架構 / 四週期對比流程圖 | `generate_diagrams.py` |
| 📈 回測/擬合 | 選股邏輯復刻 + 盈利回測 + 參數擬合 | `_profit_backtest.py` / `_year_backtest.py` |
| 🔌 數據/調試 | K線緩存擴展、名稱修復、網絡診斷 | 見 `SCRIPTS.md` |

---

## 1. 工具簡介

`smalltools` 是 StockAnalysis 項目的可視化輔助工具集，基於 Python 實現，用於將項目中的 DAG Pipeline XML 配置、整體架構以及四個週期的節點差異轉化為直觀的流程圖。

本工具集由兩個核心文件組成：

| 文件 | 說明 |
|------|------|
| `pipeline_visualizer.py` | 流程圖渲染引擎（解析 XML、生成 SVG / Mermaid） |
| `generate_diagrams.py` | 項目圖表生成腳本（調用渲染引擎生成全部圖表） |

生成的圖表可用於：
- 快速理解四個週期（超短 / 短 / 中 / 長線）Pipeline 的節點流向
- 把握整個項目 8 層架構的數據流動關係
- 對比四個週期在節點數量和獨有節點上的差異

---

## 2. 功能特性

- **XML 解析**：自動解析 `app/src/main/assets/usecases/` 下的 DAG Pipeline XML 文件，提取節點（Node）和連線（Link）。
- **雙格式輸出**：同時生成 SVG 矢量圖（可直接瀏覽器查看）和 Mermaid 文本圖（可粘貼到 Markdown / Notion / GitHub 中渲染）。
- **8 種節點類型**：數據源、策略、過濾、AI 預測、增強、聚合、交易動作、因子計算、數據轉換，每種類型有獨立顏色和形狀。
- **架構圖構建器**：`ArchitectureDiagramBuilder` 支持按層（Layer）手動構建多層架構圖，自動處理層級佈局。
- **對比圖生成**：將四個週期的節點數量和獨有 / 缺失節點可視化為對比圖。
- **跨平臺**：純 Python 標準庫 + 內置 SVG 生成，無需安裝 Graphviz 等外部依賴。
- **中文支持**：節點名稱和標題支持中文，SVG 中正確渲染。

---

## 3. 目錄結構

```
smalltools/
├── README.md                    # 本說明文檔
├── pipeline_visualizer.py       # 流程圖渲染引擎（核心庫）
├── generate_diagrams.py         # 項目圖表生成腳本
└── output/                      # 生成的圖表輸出目錄
    ├── ultra_short_pipeline.svg     # 超短線 Pipeline SVG
    ├── ultra_short_pipeline.mermaid # 超短線 Pipeline Mermaid
    ├── short_term_pipeline.svg      # 短線 Pipeline SVG
    ├── short_term_pipeline.mermaid  # 短線 Pipeline Mermaid
    ├── mid_term_pipeline.svg        # 中線 Pipeline SVG
    ├── mid_term_pipeline.mermaid    # 中線 Pipeline Mermaid
    ├── long_term_pipeline.svg       # 長線 Pipeline SVG
    ├── long_term_pipeline.mermaid   # 長線 Pipeline Mermaid
    ├── project_architecture.svg     # 項目架構圖 SVG
    ├── project_architecture.mermaid  # 項目架構圖 Mermaid
    ├── pipeline_comparison.svg      # 四週期對比圖 SVG
    └── pipeline_comparison.mermaid  # 四週期對比圖 Mermaid
```

---

## 4. 快速開始

### 環境要求

- Python 3.8+
- 無需安裝任何第三方依賴（僅使用標準庫）

### 運行步驟

```bash
# 1. 進入工具目錄
cd e:\Android\work\dev\StockAnalysis\smalltools

# 2. 執行生成腳本
python generate_diagrams.py
```

執行完成後，所有圖表將輸出到 `smalltools/output/` 目錄。

### 驗證輸出

```bash
# 查看生成的文件
ls output/

# 用瀏覽器打開 SVG
# Windows
start output\project_architecture.svg
# macOS
open output/project_architecture.svg
# Linux
xdg-open output/project_architecture.svg
```

---

## 5. API 文檔（簡要）

### NodeType — 節點類型枚舉

```python
class NodeType:
    DATA_SOURCE    = "DATA_SOURCE"      # 數據源（綠色）
    STRATEGY       = "STRATEGY"          # 策略（紫色）
    FILTER         = "FILTER"            # 過濾（橙色）
    AI_PREDICTION  = "AI_PREDICTION"     # AI 預測（青色）
    ENRICHMENT     = "ENRICHMENT"        # 增強（藍色）
    AGGREGATION    = "AGGREGATION"       # 聚合（深藍）
    TRADE_ACTION   = "TRADE_ACTION"      # 交易動作（紅色）
    FACTOR_COMPUTE = "FACTOR_COMPUTE"   # 因子計算（黃色）
    DATA_TRANSFORM = "DATA_TRANSFORM"    # 數據轉換（灰色）

    COLORS = { ... }   # 各類型對應的十六進制顏色
    SHAPES = { ... }   # 各類型對應的 Mermaid 形狀語法

    @staticmethod
    def from_module(module: str) -> str:
        """從 module 字符串自動推斷 NodeType"""
```

### PipelineNode — 節點

```python
node = PipelineNode(
    node_id="n_pool",        # 唯一標識
    name="股票池",            # 顯示名稱
    module="stock_pool",     # 模塊名（用於推斷類型）
    node_type=NodeType.DATA_SOURCE,  # 顯式指定類型（可選）
    config={"maxHoldings": 5}        # 配置參數（可選）
)
```

### PipelineLink — 連線

```python
link = PipelineLink(
    source_id="n_import",    # 源節點 ID
    target_id="n_pool",      # 目標節點 ID
    label="數據導入"          # 連線標籤（可選）
)
```

### PipelineGraph — 圖

```python
graph = PipelineGraph(
    pipeline_id="ultra_short_dag",
    name="超短線量化DAG Pipeline",
    description="市場上下文→股票池→..."
)
graph.nodes.append(node)   # 添加節點
graph.links.append(link)   # 添加連線
```

### PipelineVisualizer — 可視化器

```python
viz = PipelineVisualizer()

# 從 XML 文件解析
graph = viz.parse_file("path/to/pipeline.xml")

# 從 XML 字符串解析
graph = viz.parse_string("<DagPipeline>...</DagPipeline>")

# 渲染 SVG 文件
viz.render_svg(graph, "output.svg", title="標題")

# 渲染 Mermaid 文本
mermaid_str = viz.render_mermaid(graph)

# 一次性生成 SVG + Mermaid
viz.render_all("pipeline.xml", "output_dir/", title="標題")
```

### ArchitectureDiagramBuilder — 架構圖構建器

```python
builder = ArchitectureDiagramBuilder()

# 添加節點（帶層級）
builder.add_node("node_id", "節點名稱", NodeType.STRATEGY, layer=1)

# 添加連線
builder.add_edge("from_id", "to_id", label="關係說明")

# 構建為 PipelineGraph
graph = builder.build()
```

---

## 6. 命令行用法

### 基本用法

```bash
python generate_diagrams.py
```

生成全部 6 組圖表（4 個 Pipeline + 1 個架構圖 + 1 個對比圖），共 12 個文件。

### 僅生成特定圖表（通過函數調用）

如果只需要生成部分圖表，可以在 Python 交互環境中單獨調用：

```python
import os
os.chdir(r"e:\Android\work\dev\StockAnalysis\smalltools")

from generate_diagrams import generate_pipeline_diagrams, generate_project_architecture, generate_pipeline_comparison

os.makedirs("output", exist_ok=True)

# 只生成四個週期 Pipeline 流程圖
generate_pipeline_diagrams()

# 只生成項目架構圖
generate_project_architecture()

# 只生成對比圖
generate_pipeline_comparison()
```

### 在其他腳本中調用

```python
import sys
sys.path.insert(0, r"e:\Android\work\dev\StockAnalysis\smalltools")

from pipeline_visualizer import PipelineVisualizer

viz = PipelineVisualizer()
graph = viz.parse_file(r"...\ultra_short_pipeline.xml")
mermaid = viz.render_mermaid(graph)
print(mermaid)
```

---

## 7. 輸出格式說明

### SVG 文件

- 矢量圖格式，可用任何現代瀏覽器直接打開。
- 自動按層級（Layer / 拓撲順序）佈局節點。
- 節點按 `NodeType` 自動著色，每種類型有獨立顏色。
- 包含標題、節點名稱、連線箭頭和連線標籤。
- 支持中文顯示。

### Mermaid 文件

- 純文本格式，兼容 [Mermaid](https://mermaid.js.org/) 語法。
- 可直接粘貼到以下平台渲染：
  - GitHub Markdown（支持 Mermaid 代碼塊）
  - Notion
  - VS Code（需安裝 Mermaid 插件）
  - 任何支持 Mermaid 的 Markdown 編輯器
- 使用示例：

````markdown
```mermaid
graph LR
    A["數據導入"] --> B["股票池"]
    B --> C["信號合併"]
```
````

### 各圖表說明

| 圖表 | 文件名 | 內容 |
|------|--------|------|
| 超短線 Pipeline | `ultra_short_pipeline.*` | 20 節點，持倉 1 天，純技術面 |
| 短線 Pipeline | `short_term_pipeline.*` | 22 節點，持倉 3-5 天，熱點+輪動+新聞 |
| 中線 Pipeline | `mid_term_pipeline.*` | 20 節點，持倉 1-2 週，板塊+跨日聚合 |
| 長線 Pipeline | `long_term_pipeline.*` | 19 節點，持倉 2 週+，板塊+防禦分紅 |
| 項目架構圖 | `project_architecture.*` | 8 層架構，23 個模塊，24 條連線 |
| 四週期對比圖 | `pipeline_comparison.*` | 節點數量 + 獨有 / 缺失節點對比 |

---

## 8. 跨平臺使用指南

### Windows

```powershell
cd e:\Android\work\dev\StockAnalysis\smalltools
python generate_diagrams.py
```

### macOS / Linux

```bash
cd /path/to/StockAnalysis/smalltools
python3 generate_diagrams.py
```

### 注意事項

- **Python 版本**：需 3.8 以上，腳本中使用了 `os.makedirs(..., exist_ok=True)` 等較新語法。
- **編碼**：所有文件使用 UTF-8 編碼，確保中文正確顯示。Windows 下如遇編碼問題，可在命令行執行 `chcp 65001` 切換到 UTF-8。
- **無外部依賴**：不依賴 Graphviz、matplotlib 等第三方庫，SVG 由 Python 代碼直接生成。
- **路徑處理**：腳本使用 `os.path` 自動處理路徑分隔符，跨平臺兼容。

---

## 9. 擴展自定義

### 添加新的節點類型

在 `pipeline_visualizer.py` 的 `NodeType` 類中添加：

```python
class NodeType:
    # ... 現有類型 ...
    CUSTOM_TYPE = "CUSTOM_TYPE"

    COLORS = {
        # ... 現有顏色 ...
        "CUSTOM_TYPE": "#FF6B6B",
    }

    SHAPES = {
        # ... 現有形狀 ...
        "CUSTOM_TYPE": '(["{text}"])',
    }
```

### 解析新的 XML 文件

```python
from pipeline_visualizer import PipelineVisualizer

viz = PipelineVisualizer()
graph = viz.parse_file("your_pipeline.xml")
viz.render_svg(graph, "output.svg", title="自定義 Pipeline")
```

### 構建自定義架構圖

```python
from pipeline_visualizer import ArchitectureDiagramBuilder, NodeType, PipelineVisualizer

builder = ArchitectureDiagramBuilder()
builder.add_node("input", "輸入層", NodeType.DATA_SOURCE, layer=0)
builder.add_node("process", "處理層", NodeType.DATA_TRANSFORM, layer=1)
builder.add_node("output", "輸出層", NodeType.TRADE_ACTION, layer=2)
builder.add_edge("input", "process", "數據流入")
builder.add_edge("process", "output", "結果輸出")

graph = builder.build()

viz = PipelineVisualizer()
viz.render_svg(graph, "custom_arch.svg", title="自定義架構")
```

### 修改 SVG 樣式

SVG 由 `pipeline_visualizer.py` 中的渲染邏輯生成，可在 `render_svg` 方法中調整：
- 節點圓角、陰影、字體大小
- 連線顏色、箭頭樣式
- 背景色、標題樣式
- 層級間距

### 添加新的圖表生成函數

在 `generate_diagrams.py` 中添加新函數並在 `main()` 中調用：

```python
def generate_custom_diagram():
    """生成自定義圖表"""
    from pipeline_visualizer import PipelineVisualizer, PipelineGraph, PipelineNode, PipelineLink

    graph = PipelineGraph(pipeline_id="custom", name="自定義圖")
    graph.nodes.append(PipelineNode("n1", "節點1", "module_a"))
    graph.nodes.append(PipelineNode("n2", "節點2", "module_b"))
    graph.links.append(PipelineLink("n1", "n2"))

    viz = PipelineVisualizer()
    viz.render_svg(graph, os.path.join(OUTPUT_DIR, "custom.svg"), title="自定義")
```

---

## 附錄：節點類型速查表

| 類型 | 顏色 | 說明 | 典型模塊 |
|------|------|------|----------|
| `DATA_SOURCE` | 綠色 `#059669` | 數據採集 / 數據庫 | StockScreener, StockDatabase |
| `STRATEGY` | 紫色 `#7C3AED` | 策略計算 | 20+ Strategy 類 |
| `FILTER` | 橙色 `#EA580C` | 過濾 / 篩選 | AutoSellEngine, smart_money_filter |
| `AI_PREDICTION` | 青色 `#0891B2` | AI 預測 / 編排 | AiOrchestrator, StockPickingAgent |
| `ENRICHMENT` | 藍色 `#2563EB` | 數據增強 | MarketAnalyzer, sector_boost |
| `AGGREGATION` | 深藍 `#1E40AF` | 聚合 / 匯總 | StrategyEngine, DagPipeline |
| `TRADE_ACTION` | 紅色 `#DC2626` | 交易動作 | GenerateOrders, SwapWeak |
| `FACTOR_COMPUTE` | 黃色 `#CA8A04` | 因子計算 | Pipeline Nodes |
| `DATA_TRANSFORM` | 灰色 `#475569` | 數據轉換 / UI | MainActivity, QuantFragment |
