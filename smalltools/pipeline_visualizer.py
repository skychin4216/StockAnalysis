#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
pipeline_visualizer.py
======================

Pipeline XML 解析與流程圖可視化工具庫（純 Python 3 標準庫實現，零外部依賴）。

本模塊用於解析三種 Pipeline XML 格式，並將其渲染為獨立的 SVG 流程圖或
Mermaid 文本流程圖。支持跨平臺運行（Windows / Linux / macOS），可被其他
項目作為庫複用，也可通過命令行直接調用。

支持的三種 XML 格式
-------------------
1. **DagPipeline**（高通 Camera Pipeline 風格）
   - 根標籤 ``<DagPipeline>``，包含 ``<NodeList>`` 與 ``<Links>``。
   - 節點通過 ``<Link>`` 中的 ``<SourceNodeId>`` / ``<TargetNodeId>`` 連接。

2. **Pipeline V1**（Stage-based 分階段）
   - 根標籤 ``<pipeline>``，包含 ``<nodes>`` 與 ``<stages>``。
   - 階段 ``<stage>`` 內含 ``<linkList>``，``<link>`` 使用 ``from`` / ``to`` 屬性。

3. **Topology**（含顯式座標的拓撲圖）
   - 根標籤 ``<topology>``，包含 ``<nodes>`` 與 ``<streams>``。
   - 節點帶 ``<position x="" y=""/>`` 顯式座標，``<stream>`` 使用 ``source`` / ``sink`` 屬性。

核心功能
--------
- **節點類型推斷**：根據 ``module`` 字符串自動推斷節點類型並著色。
- **Kahn 拓撲排序分層佈局**：自動計算節點所在層級並生成座標。
- **SVG 渲染器**：生成深色主題獨立 SVG 文件（貝塞爾曲線連線 + 箭頭）。
- **Mermaid 渲染器**：生成 Mermaid 流程圖文本（graph TD）。
- **架構圖構建器**：支持手動構建圖用於生成項目整體架構流程圖。

命令行用法
----------
::

    python pipeline_visualizer.py input.xml -o output_dir --format svg,mermaid

作者: StockAnalysis Team
許可: MIT
"""

from __future__ import annotations

import argparse
import os
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Dict, List, Optional, Tuple
from xml.sax.saxutils import escape as _xml_escape

# =============================================================================
# 常量定義
# =============================================================================

#: 層間水平間距（像素），相鄰層之間的 X 軸距離
LAYER_SPACING: int = 320
#: 層內垂直間距（像素），同一層內相鄰節點之間的 Y 軸距離
VERTICAL_SPACING: int = 130
#: 水平起始偏移（像素），第一層的 X 座標
START_X: int = 60
#: 垂直起始偏移（像素，相對於內容區域），第一個節點相對於標題區的 Y 偏移
START_Y: int = 60
#: 標題區域高度（像素），頂部標題與描述所佔空間
TITLE_AREA_HEIGHT: int = 60
#: 圖例區域高度（像素），底部圖例所佔空間
LEGEND_AREA_HEIGHT: int = 110
#: 節點寬度（像素）
NODE_WIDTH: int = 200
#: 節點高度（像素）
NODE_HEIGHT: int = 70
#: 節點圓角半徑（像素）
NODE_RADIUS: int = 8
#: SVG 右側 / 下方額外留白（像素）
SVG_PADDING: int = 40

#: SVG 深色背景漸變起始色
BG_COLOR_TOP: str = "#0f172a"
#: SVG 深色背景漸變結束色
BG_COLOR_BOTTOM: str = "#1e293b"
#: 連線顏色
EDGE_COLOR: str = "#64748b"
#: 標題文字顏色
TITLE_COLOR: str = "#f1f5f9"
#: 描述文字顏色
DESC_COLOR: str = "#94a3b8"
#: 層標籤顏色
LAYER_LABEL_COLOR: str = "#475569"
#: 節點名稱文字顏色
NODE_NAME_COLOR: str = "#ffffff"
#: 節點 module 文字顏色
NODE_MODULE_COLOR: str = "#cbd5e1"
#: 節點 id 文字顏色
NODE_ID_COLOR: str = "#94a3b8"


# =============================================================================
# 節點類型定義
# =============================================================================

class NodeType(Enum):
    """節點類型枚舉。

    每個類型對應一種顏色，用於 SVG / Mermaid 渲染時的視覺區分。
    """

    #: 數據源（綠色）
    DATA_SOURCE = "DATA_SOURCE"
    #: 策略（紫色）
    STRATEGY = "STRATEGY"
    #: 過濾器（紅色）
    FILTER = "FILTER"
    #: AI 預測（藍色）
    AI_PREDICTION = "AI_PREDICTION"
    #: 數據增強（青色）
    ENRICHMENT = "ENRICHMENT"
    #: 聚合（靛色）
    AGGREGATION = "AGGREGATION"
    #: 交易動作（橙色）
    TRADE_ACTION = "TRADE_ACTION"
    #: 因子計算（藍綠色）
    FACTOR_COMPUTE = "FACTOR_COMPUTE"
    #: 數據轉換（灰色）
    DATA_TRANSFORM = "DATA_TRANSFORM"


#: 節點類型 -> 主色（填充色）
NODE_TYPE_COLORS: Dict[NodeType, str] = {
    NodeType.DATA_SOURCE: "#059669",
    NodeType.STRATEGY: "#7C3AED",
    NodeType.FILTER: "#DC2626",
    NodeType.AI_PREDICTION: "#2563EB",
    NodeType.ENRICHMENT: "#0891B2",
    NodeType.AGGREGATION: "#4F46E5",
    NodeType.TRADE_ACTION: "#D97706",
    NodeType.FACTOR_COMPUTE: "#0D9488",
    NodeType.DATA_TRANSFORM: "#6B7280",
}

#: 節點類型 -> 中文標籤（用於圖例）
NODE_TYPE_LABELS: Dict[NodeType, str] = {
    NodeType.DATA_SOURCE: "數據源",
    NodeType.STRATEGY: "策略",
    NodeType.FILTER: "過濾器",
    NodeType.AI_PREDICTION: "AI預測",
    NodeType.ENRICHMENT: "數據增強",
    NodeType.AGGREGATION: "聚合",
    NodeType.TRADE_ACTION: "交易動作",
    NodeType.FACTOR_COMPUTE: "因子計算",
    NodeType.DATA_TRANSFORM: "數據轉換",
}

#: 節點類型 -> Mermaid classDef 名稱
_MERMAID_CLASS_NAMES: Dict[NodeType, str] = {
    NodeType.DATA_SOURCE: "dataSource",
    NodeType.STRATEGY: "strategy",
    NodeType.FILTER: "filter",
    NodeType.AI_PREDICTION: "aiPrediction",
    NodeType.ENRICHMENT: "enrichment",
    NodeType.AGGREGATION: "aggregation",
    NodeType.TRADE_ACTION: "tradeAction",
    NodeType.FACTOR_COMPUTE: "factorCompute",
    NodeType.DATA_TRANSFORM: "dataTransform",
}

#: module 字符串 -> NodeType 的精確映射表
_MODULE_TYPE_MAP: Dict[str, NodeType] = {
    # DATA_SOURCE
    "data_import": NodeType.DATA_SOURCE,
    "stock_pool": NodeType.DATA_SOURCE,
    "market_context": NodeType.DATA_SOURCE,
    "candidate_pool": NodeType.DATA_SOURCE,
    "sector_stock_pool": NodeType.DATA_SOURCE,
    # FILTER
    "smart_money_filter": NodeType.FILTER,
    "news_guard": NodeType.FILTER,
    "holding_guard": NodeType.FILTER,
    "t1_auto_sell": NodeType.FILTER,
    # AI_PREDICTION
    "ai_predict": NodeType.AI_PREDICTION,
    # ENRICHMENT
    "news_strength": NodeType.ENRICHMENT,
    "rotation_penalty": NodeType.ENRICHMENT,
    "heat_score": NodeType.ENRICHMENT,
    "adaptive_params": NodeType.ENRICHMENT,
    "multi_period_hot": NodeType.ENRICHMENT,
    "cross_day_aggregation": NodeType.ENRICHMENT,
    "defensive_dividend": NodeType.ENRICHMENT,
    "inst_tips": NodeType.ENRICHMENT,
    "ma_convergence": NodeType.ENRICHMENT,
    # AGGREGATION
    "signal_merge": NodeType.AGGREGATION,
    "sector_boost": NodeType.AGGREGATION,
    # TRADE_ACTION
    "generate_orders": NodeType.TRADE_ACTION,
    "swap_weak": NodeType.TRADE_ACTION,
    "position_merge": NodeType.TRADE_ACTION,
    "crosstab_publish": NodeType.TRADE_ACTION,
    # FACTOR_COMPUTE
    "bounce_reversal": NodeType.FACTOR_COMPUTE,
    "candle_pattern": NodeType.FACTOR_COMPUTE,
    # DATA_TRANSFORM
    "bg_manager": NodeType.DATA_TRANSFORM,
    "fitting_save": NodeType.DATA_TRANSFORM,
}

#: 關鍵詞 -> NodeType 的模糊匹配規則（當精確匹配失敗時使用）
_KEYWORD_RULES: List[Tuple[str, NodeType]] = [
    ("strategy:", NodeType.STRATEGY),
    ("filter", NodeType.FILTER),
    ("guard", NodeType.FILTER),
    ("source", NodeType.DATA_SOURCE),
    ("import", NodeType.DATA_SOURCE),
    ("context", NodeType.DATA_SOURCE),
    ("pool", NodeType.DATA_SOURCE),
    ("predict", NodeType.AI_PREDICTION),
    ("order", NodeType.TRADE_ACTION),
    ("trade", NodeType.TRADE_ACTION),
    ("swap", NodeType.TRADE_ACTION),
    ("position", NodeType.TRADE_ACTION),
    ("publish", NodeType.TRADE_ACTION),
    ("merge", NodeType.AGGREGATION),
    ("boost", NodeType.AGGREGATION),
    ("sort", NodeType.AGGREGATION),
    ("pattern", NodeType.FACTOR_COMPUTE),
    ("reversal", NodeType.FACTOR_COMPUTE),
    ("bounce", NodeType.FACTOR_COMPUTE),
    ("score", NodeType.ENRICHMENT),
    ("strength", NodeType.ENRICHMENT),
    ("penalty", NodeType.ENRICHMENT),
    ("heat", NodeType.ENRICHMENT),
    ("adaptive", NodeType.ENRICHMENT),
    ("hot", NodeType.ENRICHMENT),
    ("dividend", NodeType.ENRICHMENT),
    ("convergence", NodeType.ENRICHMENT),
    ("output", NodeType.DATA_TRANSFORM),
    ("sink", NodeType.DATA_TRANSFORM),
    ("save", NodeType.DATA_TRANSFORM),
    ("manager", NodeType.DATA_TRANSFORM),
]


def infer_node_type(module: str) -> NodeType:
    """根據 module 字符串推斷節點類型。

    推斷順序：
    1. ``strategy:`` 前綴 → :data:`NodeType.STRATEGY`
    2. 精確匹配 ``_MODULE_TYPE_MAP``
    3. 關鍵詞模糊匹配 ``_KEYWORD_RULES``
    4. 默認 → :data:`NodeType.DATA_TRANSFORM`

    Args:
        module: 節點的 module 標識字符串。

    Returns:
        推斷出的 :class:`NodeType` 枚舉值。
    """
    if not module:
        return NodeType.DATA_TRANSFORM

    normalized = module.strip().lower()

    # 1. strategy: 前綴
    if normalized.startswith("strategy:"):
        return NodeType.STRATEGY

    # 2. 精確匹配
    if normalized in _MODULE_TYPE_MAP:
        return _MODULE_TYPE_MAP[normalized]

    # 3. 關鍵詞模糊匹配
    for keyword, node_type in _KEYWORD_RULES:
        if keyword in normalized:
            return node_type

    # 4. 默認
    return NodeType.DATA_TRANSFORM


# =============================================================================
# 數據模型
# =============================================================================

@dataclass
class PipelineNode:
    """Pipeline 節點數據模型。

    Attributes:
        id: 節點唯一標識。
        name: 節點顯示名稱（通常為中文）。
        module: 節點模塊標識（用於推斷類型）。
        node_type: 節點類型（由 module 推斷或手動指定）。
        layer: 節點所在層級（Kahn 排序或手動指定）。
        x: 節點左上角 X 座標（像素）。
        y: 節點左上角 Y 座標（像素）。
        config: 節點配置參數字典。
        position_explicit: 是否使用了顯式座標（Topology 格式）。
    """

    id: str
    name: str = ""
    module: str = ""
    node_type: NodeType = NodeType.DATA_TRANSFORM
    layer: int = 0
    x: Optional[int] = None
    y: Optional[int] = None
    config: Dict[str, str] = field(default_factory=dict)
    position_explicit: bool = False

    def center(self) -> Tuple[float, float]:
        """返回節點中心座標。

        Returns:
            ``(cx, cy)`` 元組。若座標未設置則返回 ``(0, 0)``。
        """
        if self.x is None or self.y is None:
            return (0.0, 0.0)
        return (self.x + NODE_WIDTH / 2.0, self.y + NODE_HEIGHT / 2.0)

    def right_anchor(self) -> Tuple[float, float]:
        """返回節點右側中點座標（連線起點）。"""
        if self.x is None or self.y is None:
            return (0.0, 0.0)
        return (self.x + NODE_WIDTH, self.y + NODE_HEIGHT / 2.0)

    def left_anchor(self) -> Tuple[float, float]:
        """返回節點左側中點座標（連線終點）。"""
        if self.x is None or self.y is None:
            return (0.0, 0.0)
        return (float(self.x), self.y + NODE_HEIGHT / 2.0)


@dataclass
class PipelineEdge:
    """Pipeline 連線數據模型。

    Attributes:
        source: 源節點 id。
        target: 目標節點 id。
        label: 連線標籤（可選，用於 Mermaid 邊標籤）。
    """

    source: str
    target: str
    label: str = ""


# ── 兼容別名 ──
# PipelineLink 是 PipelineEdge 的別名，提供與 generate_diagrams.py 等腳本的 API 兼容
PipelineLink = PipelineEdge


@dataclass
class PipelineGraph:
    """Pipeline 圖數據模型，包含節點列表、連線列表和元信息。

    Attributes:
        id: 圖的唯一標識。
        name: 圖的名稱。
        description: 圖的描述。
        nodes: 節點列表。
        edges: 連線列表。
        format_hint: 解析來源格式標記（"dag" / "pipeline" / "topology" / "manual"）。
    """

    id: str = ""
    name: str = ""
    description: str = ""
    nodes: List[PipelineNode] = field(default_factory=list)
    edges: List[PipelineEdge] = field(default_factory=list)
    format_hint: str = ""

    # ---- 節點索引輔助方法 ----

    def get_node(self, node_id: str) -> Optional[PipelineNode]:
        """根據 id 查找節點。

        Args:
            node_id: 節點 id。

        Returns:
            匹配的 :class:`PipelineNode`，找不到則返回 ``None``。
        """
        for node in self.nodes:
            if node.id == node_id:
                return node
        return None

    @property
    def node_map(self) -> Dict[str, PipelineNode]:
        """返回 ``{id: node}`` 字典。"""
        return {node.id: node for node in self.nodes}

    def has_explicit_positions(self) -> bool:
        """判斷圖中是否已有節點設置了顯式座標。

        Returns:
            若任一節點的 ``position_explicit`` 為 ``True`` 則返回 ``True``。
        """
        return any(node.position_explicit for node in self.nodes)

    # ── 兼容屬性 ──
    @property
    def links(self) -> List[PipelineEdge]:
        """``edges`` 的別名，提供 API 兼容。"""
        return self.edges


# =============================================================================
# XML 解析器
# =============================================================================

class _BaseXMLParser:
    """XML 解析器基類，提供通用的節點 / 連線構建輔助方法。

    子類需實現 :meth:`parse` 方法，從 :class:`ET.Element` 構建 :class:`PipelineGraph`。
    """

    @staticmethod
    def _parse_config(config_elem: Optional[ET.Element]) -> Dict[str, str]:
        """解析 ``<config>`` 元素中的 ``<param>`` 鍵值對。

        Args:
            config_elem: ``<config>`` 元素，可為 ``None``。

        Returns:
            ``{name: value}`` 字典。
        """
        result: Dict[str, str] = {}
        if config_elem is None:
            return result
        for param in config_elem.iterfind("param"):
            name = param.get("name", "")
            value = param.get("value", "")
            if name:
                result[name] = value
        return result

    @staticmethod
    def _make_node(
        node_id: str,
        module: str = "",
        name: str = "",
        config: Optional[Dict[str, str]] = None,
        x: Optional[int] = None,
        y: Optional[int] = None,
        position_explicit: bool = False,
    ) -> PipelineNode:
        """構建 :class:`PipelineNode` 並自動推斷類型。

        Args:
            node_id: 節點 id。
            module: 模塊標識。
            name: 顯示名稱。
            config: 配置字典。
            x: 顯式 X 座標。
            y: 顯式 Y 座標。
            position_explicit: 是否顯式座標。

        Returns:
            構建好的 :class:`PipelineNode`。
        """
        display_name = name or node_id
        return PipelineNode(
            id=node_id,
            name=display_name,
            module=module,
            node_type=infer_node_type(module),
            config=config or {},
            x=x,
            y=y,
            position_explicit=position_explicit,
        )

    def parse(self, root: ET.Element) -> PipelineGraph:
        """從 XML 根元素解析為 :class:`PipelineGraph`（子類實現）。"""
        raise NotImplementedError


class DagPipelineParser(_BaseXMLParser):
    """格式 A 解析器：DagPipeline（高通 Camera Pipeline 風格）。

    解析 ``<DagPipeline>`` 根元素，讀取 ``<NodeList>`` 中的 ``<Node>``
    以及 ``<Links>`` 中的 ``<Link>``。
    """

    def parse(self, root: ET.Element) -> PipelineGraph:
        """解析 DagPipeline XML。

        Args:
            root: ``<DagPipeline>`` 根元素。

        Returns:
            構建好的 :class:`PipelineGraph`。
        """
        graph = PipelineGraph(
            id=root.get("id", ""),
            name=root.get("name", ""),
            description=root.get("description", ""),
            format_hint="dag",
        )

        # 解析 NodeList
        node_list = root.find("NodeList")
        if node_list is not None:
            for node_elem in node_list.iterfind("Node"):
                node_id = node_elem.get("id", "")
                if not node_id:
                    continue
                module = node_elem.get("module", "")
                name = node_elem.get("name", "")
                config = self._parse_config(node_elem.find("config"))
                graph.nodes.append(
                    self._make_node(node_id, module, name, config)
                )

        # 解析 Links
        links_elem = root.find("Links")
        if links_elem is not None:
            for link_elem in links_elem.iterfind("Link"):
                source = self._text(link_elem, "SourceNodeId")
                target = self._text(link_elem, "TargetNodeId")
                if source and target:
                    graph.edges.append(PipelineEdge(source=source, target=target))

        return graph

    @staticmethod
    def _text(parent: ET.Element, tag: str) -> str:
        """讀取子元素的文本內容。

        Args:
            parent: 父元素。
            tag: 子元素標籤名。

        Returns:
            子元素的文本（去除首尾空白），找不到則返回空字符串。
        """
        child = parent.find(tag)
        if child is not None and child.text:
            return child.text.strip()
        return ""


class PipelineV1Parser(_BaseXMLParser):
    """格式 B 解析器：Pipeline V1（Stage-based 分階段）。

    解析 ``<pipeline>`` 根元素，讀取 ``<nodes>`` 中的 ``<node>``
    以及 ``<stages>`` / ``<stage>`` / ``<linkList>`` / ``<link>`` 結構。
    """

    def parse(self, root: ET.Element) -> PipelineGraph:
        """解析 Pipeline V1 XML。

        Args:
            root: ``<pipeline>`` 根元素。

        Returns:
            構建好的 :class:`PipelineGraph`。
        """
        graph = PipelineGraph(
            id=root.get("id", ""),
            name=root.get("name", ""),
            description=root.get("description", ""),
            format_hint="pipeline",
        )

        # 解析 nodes
        nodes_elem = root.find("nodes")
        if nodes_elem is not None:
            for node_elem in nodes_elem.iterfind("node"):
                node_id = node_elem.get("id", "")
                if not node_id:
                    continue
                module = node_elem.get("module", "")
                name = node_elem.get("name", "")
                config = self._parse_config(node_elem.find("config"))
                graph.nodes.append(
                    self._make_node(node_id, module, name, config)
                )

        # 解析 stages -> linkList -> link
        stages_elem = root.find("stages")
        if stages_elem is not None:
            for stage in stages_elem.iterfind("stage"):
                stage_name = stage.get("name", "")
                for link_list in stage.iterfind("linkList"):
                    list_label = link_list.get("name", "")
                    edge_label = list_label or stage_name
                    for link in link_list.iterfind("link"):
                        source = link.get("from", "")
                        target = link.get("to", "")
                        if source and target:
                            graph.edges.append(
                                PipelineEdge(source=source, target=target, label=edge_label)
                            )

        return graph


class TopologyParser(_BaseXMLParser):
    """格式 C 解析器：Topology（含顯式座標）。

    解析 ``<topology>`` 根元素，讀取 ``<nodes>`` 中帶 ``<position>``
    座標的 ``<node>``，以及 ``<streams>`` 中的 ``<stream>`` 連線。
    """

    def parse(self, root: ET.Element) -> PipelineGraph:
        """解析 Topology XML。

        Args:
            root: ``<topology>`` 根元素。

        Returns:
            構建好的 :class:`PipelineGraph`，節點帶有顯式座標。
        """
        graph = PipelineGraph(
            id=root.get("id", ""),
            name=root.get("name", ""),
            description=root.get("description", ""),
            format_hint="topology",
        )

        # 解析 nodes（含 position 座標）
        nodes_elem = root.find("nodes")
        if nodes_elem is not None:
            for node_elem in nodes_elem.iterfind("node"):
                node_id = node_elem.get("id", "")
                if not node_id:
                    continue
                module = node_elem.get("module", "")
                name = node_elem.get("displayName", "") or node_elem.get("name", "")
                config = self._parse_config(node_elem.find("config"))

                # 解析 position
                x_val: Optional[int] = None
                y_val: Optional[int] = None
                pos_elem = node_elem.find("position")
                if pos_elem is not None:
                    x_val = self._to_int(pos_elem.get("x"))
                    y_val = self._to_int(pos_elem.get("y"))

                graph.nodes.append(
                    self._make_node(
                        node_id,
                        module,
                        name,
                        config,
                        x=x_val,
                        y=y_val,
                        position_explicit=(x_val is not None and y_val is not None),
                    )
                )

        # 解析 streams
        streams_elem = root.find("streams")
        if streams_elem is not None:
            for stream in streams_elem.iterfind("stream"):
                source = stream.get("source", "")
                target = stream.get("sink", "")
                if source and target:
                    graph.edges.append(PipelineEdge(source=source, target=target))

        return graph

    @staticmethod
    def _to_int(value: Optional[str]) -> Optional[int]:
        """安全地將字符串轉為 int。

        Args:
            value: 字符串值。

        Returns:
            轉換後的整數，失敗則返回 ``None``。
        """
        if value is None:
            return None
        try:
            return int(float(value))
        except (ValueError, TypeError):
            return None


class PipelineXMLParser:
    """Pipeline XML 統一解析器（工廠模式）。

    根據 XML 根標籤自動選擇對應的格式解析器：

    - ``DagPipeline`` → :class:`DagPipelineParser`
    - ``pipeline`` → :class:`PipelineV1Parser`
    - ``topology`` → :class:`TopologyParser`

    支持從文件路徑或 XML 字符串解析。
    """

    #: 根標籤 -> 解析器實例的映射
    _PARSERS: Dict[str, _BaseXMLParser] = {
        "DagPipeline": DagPipelineParser(),
        "pipeline": PipelineV1Parser(),
        "topology": TopologyParser(),
    }

    def parse_file(self, xml_path: str) -> PipelineGraph:
        """從 XML 文件路徑解析。

        Args:
            xml_path: XML 文件路徑。

        Returns:
            解析得到的 :class:`PipelineGraph`。

        Raises:
            FileNotFoundError: 文件不存在。
            ValueError: XML 格式錯誤或不支持根標籤。
        """
        path = Path(xml_path)
        if not path.exists():
            raise FileNotFoundError(f"XML 文件不存在: {xml_path}")
        try:
            tree = ET.parse(str(path))
        except ET.ParseError as exc:
            raise ValueError(f"XML 解析失敗 ({xml_path}): {exc}") from exc
        root = tree.getroot()
        return self._dispatch(root)

    def parse_string(self, xml_content: str) -> PipelineGraph:
        """從 XML 字符串解析。

        Args:
            xml_content: XML 文本內容。

        Returns:
            解析得到的 :class:`PipelineGraph`。

        Raises:
            ValueError: XML 格式錯誤或不支持根標籤。
        """
        try:
            root = ET.fromstring(xml_content)
        except ET.ParseError as exc:
            raise ValueError(f"XML 字符串解析失敗: {exc}") from exc
        return self._dispatch(root)

    def _dispatch(self, root: ET.Element) -> PipelineGraph:
        """根據根標籤分發到對應解析器。

        Args:
            root: XML 根元素。

        Returns:
            解析得到的 :class:`PipelineGraph`。

        Raises:
            ValueError: 不支持的根標籤。
        """
        tag = root.tag
        # 處理帶命名空間的情況（取最後一部分）
        if "}" in tag:
            tag = tag.split("}")[-1]
        parser = self._PARSERS.get(tag)
        if parser is None:
            supported = ", ".join(self._PARSERS.keys())
            raise ValueError(
                f"不支持的 XML 根標籤 '<{root.tag}>'，"
                f"支持的格式: {supported}"
            )
        return parser.parse(root)


# =============================================================================
# Kahn 拓撲排序分層佈局計算器
# =============================================================================

class KahnLayoutCalculator:
    """Kahn 拓撲排序分層佈局計算器。

    使用 Kahn 算法對 DAG 進行拓撲排序，將節點分配到不同層級（Layer），
    並根據層級和層內順序計算每個節點的 ``(x, y)`` 座標。

    佈局規則：
    - 入度為 0 的節點位於第 0 層。
    - 逐層剝離：剝離當前層後，新入度為 0 的節點進入下一層。
    - 環檢測：若某一輪 ready 為空但仍有剩餘節點，則將剩餘節點放入新的一層。
    - 層間水平間距 ``LAYER_SPACING``，層內垂直間距 ``VERTICAL_SPACING``。
    - 起始偏移 ``(START_X, START_Y)``（Y 軸疊加標題區高度）。
    """

    @staticmethod
    def compute_layers(graph: PipelineGraph) -> Dict[str, int]:
        """使用 Kahn 算法計算每個節點的層級。

        Args:
            graph: :class:`PipelineGraph` 圖數據。

        Returns:
            ``{node_id: layer}`` 字典。
        """
        node_ids = [node.id for node in graph.nodes]
        id_set = set(node_ids)

        # 構建鄰接表與入度表
        adjacency: Dict[str, List[str]] = {nid: [] for nid in node_ids}
        in_degree: Dict[str, int] = {nid: 0 for nid in node_ids}
        for edge in graph.edges:
            if edge.source in id_set and edge.target in id_set:
                adjacency[edge.source].append(edge.target)
                in_degree[edge.target] += 1

        layers: Dict[str, int] = {}
        remaining = list(node_ids)  # 保持原始順序
        remaining_set = set(node_ids)
        current_layer = 0

        while remaining_set:
            # 找出當前入度為 0 的節點（保持原始順序）
            ready = [nid for nid in remaining if nid in remaining_set and in_degree[nid] == 0]

            if not ready:
                # 環檢測：剩餘節點形成環，全部放入當前層
                for nid in remaining:
                    if nid in remaining_set:
                        layers[nid] = current_layer
                break

            for nid in ready:
                layers[nid] = current_layer
                remaining_set.discard(nid)
                for neighbor in adjacency[nid]:
                    in_degree[neighbor] -= 1
            current_layer += 1

        return layers

    @staticmethod
    def compute_positions_from_layers(graph: PipelineGraph) -> None:
        """根據已計算的層級（``node.layer``）計算節點座標。

        將同一層的節點在垂直方向均勻分佈，不同層在水平方向排列。
        直接修改 ``graph`` 中各節點的 ``x`` / ``y`` 屬性。

        Args:
            graph: :class:`PipelineGraph`，節點需已設置 ``layer``。
        """
        # 按層分組，保持節點在 nodes 列表中的原始順序
        layer_groups: Dict[int, List[str]] = {}
        for node in graph.nodes:
            layer_groups.setdefault(node.layer, []).append(node.id)

        node_map = graph.node_map
        for layer, node_ids in layer_groups.items():
            count = len(node_ids)
            for idx, nid in enumerate(node_ids):
                node = node_map.get(nid)
                if node is None:
                    continue
                # 垂直居中：當層節點數較少時整體居中顯示
                node.x = START_X + layer * LAYER_SPACING
                node.y = TITLE_AREA_HEIGHT + START_Y + idx * VERTICAL_SPACING

    @classmethod
    def apply(cls, graph: PipelineGraph) -> None:
        """對圖應用完整的 Kahn 分層佈局。

        若圖中已有顯式座標（Topology 格式），則僅計算層級（用於 Mermaid 分組），
        不覆蓋顯式座標；否則同時計算層級和座標。

        Args:
            graph: :class:`PipelineGraph` 圖數據。
        """
        layers = cls.compute_layers(graph)
        for node in graph.nodes:
            node.layer = layers.get(node.id, 0)

        if graph.has_explicit_positions():
            # 已有顯式座標，僅補全未設置座標的節點
            cls._fill_missing_positions(graph)
        else:
            cls.compute_positions_from_layers(graph)

    @staticmethod
    def _fill_missing_positions(graph: PipelineGraph) -> None:
        """為缺少座標的節點補全座標（基於層級）。

        用於混合場景：部分節點有顯式座標，部分沒有。

        Args:
            graph: :class:`PipelineGraph`。
        """
        layer_groups: Dict[int, List[PipelineNode]] = {}
        for node in graph.nodes:
            layer_groups.setdefault(node.layer, []).append(node)

        for layer, nodes in layer_groups.items():
            idx = 0
            for node in nodes:
                if node.x is None or node.y is None:
                    node.x = START_X + layer * LAYER_SPACING
                    node.y = TITLE_AREA_HEIGHT + START_Y + idx * VERTICAL_SPACING
                    idx += 1


# =============================================================================
# SVG 渲染器
# =============================================================================

class SVGRenderer:
    """SVG 深色主題渲染器。

    生成獨立的 ``.svg`` 文件，包含：
    - 漸變背景（深色主題）
    - 頂部標題與描述
    - 左側層標籤
    - 圓角矩形節點（按 NodeType 著色）
    - 三次貝塞爾曲線連線（帶箭頭）
    - 底部節點類型圖例

    所有文本均經過 XML 轉義以確保安全。
    """

    def render(
        self,
        graph: PipelineGraph,
        output_path: str,
        title: str = "",
    ) -> str:
        """渲染 SVG 文件。

        Args:
            graph: :class:`PipelineGraph` 圖數據（需已計算座標）。
            output_path: 輸出 ``.svg`` 文件路徑。
            title: 可選的自定義標題（覆蓋 graph.name）。

        Returns:
            實際寫入的文件路徑。
        """
        # 確保座標已計算
        if not graph.has_explicit_positions():
            KahnLayoutCalculator.compute_positions_from_layers(graph)

        svg_content = self._build_svg(graph, title)

        # 確保輸出目錄存在
        out_dir = os.path.dirname(os.path.abspath(output_path))
        os.makedirs(out_dir, exist_ok=True)

        with open(output_path, "w", encoding="utf-8") as f:
            f.write(svg_content)

        return output_path

    # ------------------------------------------------------------------
    # 內部構建方法
    # ------------------------------------------------------------------

    def _build_svg(self, graph: PipelineGraph, title: str) -> str:
        """構建完整 SVG 字符串。

        Args:
            graph: 圖數據。
            title: 自定義標題。

        Returns:
            SVG 文本。
        """
        width, height = self._calc_dimensions(graph)
        display_title = title or graph.name or graph.id or "Pipeline"

        parts: List[str] = []
        parts.append('<?xml version="1.0" encoding="UTF-8"?>')
        parts.append(
            f'<svg xmlns="http://www.w3.org/2000/svg" '
            f'width="{width}" height="{height}" '
            f'viewBox="0 0 {width} {height}" '
            f'font-family="Microsoft YaHei, PingFang SC, Noto Sans CJK SC, sans-serif">'
        )

        # defs：漸變 + 箭頭標記
        parts.append(self._build_defs())

        # 背景
        parts.append(
            f'<rect width="{width}" height="{height}" fill="url(#bgGradient)" />'
        )

        # 標題區
        parts.append(self._build_title(display_title, graph.description, width))

        # 層標籤
        parts.append(self._build_layer_labels(graph))

        # 連線
        parts.append(self._build_edges(graph))

        # 節點
        parts.append(self._build_nodes(graph))

        # 圖例
        parts.append(self._build_legend(graph, width, height))

        parts.append("</svg>")
        return "\n".join(parts)

    def _build_defs(self) -> str:
        """構建 ``<defs>`` 定義（漸變背景 + 箭頭標記）。

        Returns:
            ``<defs>`` XML 片段。
        """
        return (
            "  <defs>\n"
            '    <linearGradient id="bgGradient" x1="0" y1="0" x2="0" y2="1">\n'
            f'      <stop offset="0%" stop-color="{BG_COLOR_TOP}" />\n'
            f'      <stop offset="100%" stop-color="{BG_COLOR_BOTTOM}" />\n'
            "    </linearGradient>\n"
            '    <marker id="arrowhead" markerWidth="10" markerHeight="10" '
            'refX="9" refY="3" orient="auto" markerUnits="strokeWidth">\n'
            f'      <path d="M0,0 L9,3 L0,6 Z" fill="{EDGE_COLOR}" />\n'
            "    </marker>\n"
            "  </defs>"
        )

    def _build_title(self, title: str, description: str, width: int) -> str:
        """構建頂部標題與描述。

        Args:
            title: 標題文本。
            description: 描述文本。
            width: SVG 寬度。

        Returns:
            標題區 XML 片段。
        """
        parts: List[str] = []
        cx = width / 2
        parts.append(
            f'  <text x="{cx}" y="30" text-anchor="middle" '
            f'font-size="20" font-weight="bold" fill="{TITLE_COLOR}">'
            f'{_esc(title)}</text>'
        )
        if description:
            parts.append(
                f'  <text x="{cx}" y="52" text-anchor="middle" '
                f'font-size="12" fill="{DESC_COLOR}">'
                f'{_esc(description)}</text>'
            )
        return "\n".join(parts)

    def _build_layer_labels(self, graph: PipelineGraph) -> str:
        """構建層標籤（每層左上方顯示 "Layer N"）。

        Args:
            graph: 圖數據。

        Returns:
            層標籤 XML 片段。
        """
        if not graph.nodes:
            return ""

        # 按層分組
        layer_nodes: Dict[int, List[PipelineNode]] = {}
        for node in graph.nodes:
            layer_nodes.setdefault(node.layer, []).append(node)

        parts: List[str] = []
        for layer in sorted(layer_nodes.keys()):
            x = START_X + layer * LAYER_SPACING
            # 標籤位於該層第一個節點上方
            first_y = TITLE_AREA_HEIGHT + START_Y - 18
            parts.append(
                f'  <text x="{x}" y="{first_y}" font-size="11" '
                f'font-weight="bold" fill="{LAYER_LABEL_COLOR}">'
                f'Layer {layer}</text>'
            )
        return "\n".join(parts)

    def _build_edges(self, graph: PipelineGraph) -> str:
        """構建連線（三次貝塞爾曲線 + 箭頭）。

        Args:
            graph: 圖數據。

        Returns:
            連線 XML 片段。
        """
        if not graph.nodes:
            return ""

        node_map = graph.node_map
        parts: List[str] = []
        parts.append('  <g id="edges">')

        for edge in graph.edges:
            src = node_map.get(edge.source)
            tgt = node_map.get(edge.target)
            if src is None or tgt is None:
                continue
            if src.x is None or src.y is None or tgt.x is None or tgt.y is None:
                continue

            sx, sy = src.right_anchor()
            tx, ty = tgt.left_anchor()

            # 三次貝塞爾曲線控制點
            dx = max(40.0, abs(tx - sx) * 0.4)
            cx1 = sx + dx
            cy1 = sy
            cx2 = tx - dx
            cy2 = ty

            path = (
                f"  <path d=\"M {sx:.1f},{sy:.1f} "
                f"C {cx1:.1f},{cy1:.1f} {cx2:.1f},{cy2:.1f} "
                f"{tx:.1f},{ty:.1f}\" "
                f'fill="none" stroke="{EDGE_COLOR}" stroke-width="2" '
                f'marker-end="url(#arrowhead)" opacity="0.75" />'
            )
            parts.append(path)

        parts.append("  </g>")
        return "\n".join(parts)

    def _build_nodes(self, graph: PipelineGraph) -> str:
        """構建節點（圓角矩形 + 文本）。

        Args:
            graph: 圖數據。

        Returns:
            節點 XML 片段。
        """
        parts: List[str] = []
        parts.append('  <g id="nodes">')

        for node in graph.nodes:
            if node.x is None or node.y is None:
                continue
            color = NODE_TYPE_COLORS.get(node.node_type, NODE_TYPE_COLORS[NodeType.DATA_TRANSFORM])
            nx = node.x
            ny = node.y
            cx = nx + NODE_WIDTH / 2

            # 節點矩形（半透明填充 + 實色描邊）
            parts.append(
                f"  <rect x=\"{nx}\" y=\"{ny}\" "
                f'width="{NODE_WIDTH}" height="{NODE_HEIGHT}" '
                f'rx="{NODE_RADIUS}" ry="{NODE_RADIUS}" '
                f'fill="{color}" fill-opacity="0.85" '
                f'stroke="{color}" stroke-width="2" />'
            )

            # 節點名稱（14px，白色）
            name_text = node.name or node.id
            parts.append(
                f'  <text x="{cx}" y="{ny + 24}" text-anchor="middle" '
                f'font-size="14" font-weight="bold" fill="{NODE_NAME_COLOR}">'
                f'{_esc(name_text)}</text>'
            )

            # module（10px，淺灰）
            if node.module:
                parts.append(
                    f'  <text x="{cx}" y="{ny + 44}" text-anchor="middle" '
                    f'font-size="10" fill="{NODE_MODULE_COLOR}">'
                    f'{_esc(node.module)}</text>'
                )

            # id（8px，更灰）
            parts.append(
                f'  <text x="{cx}" y="{ny + 60}" text-anchor="middle" '
                f'font-size="8" fill="{NODE_ID_COLOR}">'
                f'{_esc(node.id)}</text>'
            )

        parts.append("  </g>")
        return "\n".join(parts)

    def _build_legend(self, graph: PipelineGraph, width: int, height: int) -> str:
        """構建底部圖例（節點類型顏色對照）。

        Args:
            graph: 圖數據（用於確定實際出現的類型）。
            width: SVG 寬度。
            height: SVG 高度。

        Returns:
            圖例 XML 片段。
        """
        # 收集圖中實際出現的類型，按枚舉順序排列
        used_types = sorted(
            set(node.node_type for node in graph.nodes),
            key=lambda t: list(NodeType).index(t),
        )
        if not used_types:
            used_types = list(NodeType)

        parts: List[str] = []
        parts.append('  <g id="legend">')

        legend_y = height - LEGEND_AREA_HEIGHT + 20
        parts.append(
            f'  <text x="{SVG_PADDING}" y="{legend_y}" font-size="12" '
            f'font-weight="bold" fill="{DESC_COLOR}">圖例</text>'
        )

        # 每行最多 5 個圖例項
        per_row = 5
        item_width = (width - 2 * SVG_PADDING) / per_row
        swatch_size = 14

        for i, ntype in enumerate(used_types):
            row = i // per_row
            col = i % per_row
            ix = SVG_PADDING + col * item_width
            iy = legend_y + 20 + row * 28
            color = NODE_TYPE_COLORS[ntype]
            label = NODE_TYPE_LABELS.get(ntype, ntype.value)

            parts.append(
                f'  <rect x="{ix}" y="{iy}" width="{swatch_size}" '
                f'height="{swatch_size}" rx="3" fill="{color}" />'
            )
            parts.append(
                f'  <text x="{ix + swatch_size + 6}" y="{iy + 12}" '
                f'font-size="11" fill="{DESC_COLOR}">{_esc(label)}</text>'
            )

        parts.append("  </g>")
        return "\n".join(parts)

    def _calc_dimensions(self, graph: PipelineGraph) -> Tuple[int, int]:
        """計算 SVG 畫布尺寸。

        Args:
            graph: 圖數據。

        Returns:
            ``(width, height)`` 元組。
        """
        if not graph.nodes:
            return (400, 300)

        # 按層分組計算每層節點數
        layer_counts: Dict[int, int] = {}
        max_x = 0
        max_y = 0
        for node in graph.nodes:
            layer_counts[node.layer] = layer_counts.get(node.layer, 0) + 1
            if node.x is not None:
                max_x = max(max_x, node.x + NODE_WIDTH)
            if node.y is not None:
                max_y = max(max_y, node.y + NODE_HEIGHT)

        max_layer = max(node.layer for node in graph.nodes) if graph.nodes else 0
        max_count = max(layer_counts.values()) if layer_counts else 1

        width = max(
            START_X + (max_layer + 1) * LAYER_SPACING + SVG_PADDING,
            max_x + SVG_PADDING,
            600,
        )
        height = max(
            TITLE_AREA_HEIGHT + START_Y + max_count * VERTICAL_SPACING
            + LEGEND_AREA_HEIGHT + SVG_PADDING,
            max_y + LEGEND_AREA_HEIGHT + SVG_PADDING,
            400,
        )
        return (int(width), int(height))


# =============================================================================
# Mermaid 渲染器
# =============================================================================

class MermaidRenderer:
    """Mermaid 流程圖文本渲染器。

    生成 Mermaid ``graph TD``（上到下）流程圖文本，包括：
    - 按 NodeType 使用不同節點形狀
    - ``classDef`` 定義顏色樣式
    - 按 Layer 添加註釋分隔
    - ``subgraph`` 包裹同一層節點（可選）

    輸出為可直接粘貼到 Mermaid 渲染器的文本字符串。
    """

    #: 節點類型 -> Mermaid 形狀模板（使用 %s 佔位符避免花括號轉義問題）
    _SHAPE_TEMPLATES: Dict[NodeType, str] = {
        NodeType.DATA_SOURCE: '%s(["%s"])',          # 體育場形（stadium）
        NodeType.STRATEGY: '%s{{"%s"}}',              # 六邊形（hexagon）
        NodeType.FILTER: '%s["%s"]',                  # 矩形（rectangle）
        NodeType.AI_PREDICTION: '%s("%s")',           # 圓角矩形（rounded）
        NodeType.ENRICHMENT: '%s[["%s"]]',            # 子程序（subroutine）
        NodeType.AGGREGATION: '%s[("%s")]',           # 圓柱/數據庫（database）
        NodeType.TRADE_ACTION: '%s(("%s"))',          # 圓形（circle）
        NodeType.FACTOR_COMPUTE: '%s{"%s"}',          # 菱形（rhombus）
        NodeType.DATA_TRANSFORM: '%s[/"%s"\\]',       # 平行四邊形（parallelogram）
    }

    def render(self, graph: PipelineGraph, use_subgraph: bool = True) -> str:
        """渲染 Mermaid 流程圖文本。

        Args:
            graph: :class:`PipelineGraph` 圖數據（需已計算層級）。
            use_subgraph: 是否使用 ``subgraph`` 包裹同一層節點。

        Returns:
            Mermaid 流程圖文本字符串。
        """
        lines: List[str] = []
        lines.append("graph TD")
        lines.append("")

        # 標題註釋
        title_parts = []
        if graph.name:
            title_parts.append(graph.name)
        if graph.id:
            title_parts.append(f"id={graph.id}")
        if title_parts:
            lines.append(f"    %% {' | '.join(title_parts)}")
        if graph.description:
            lines.append(f"    %% {graph.description}")
        lines.append("")

        # 按層分組節點
        layer_nodes: Dict[int, List[PipelineNode]] = {}
        for node in graph.nodes:
            layer_nodes.setdefault(node.layer, []).append(node)

        # 節點定義（按層）
        for layer in sorted(layer_nodes.keys()):
            nodes = layer_nodes[layer]
            lines.append(f"    %% ===== Layer {layer} =====")
            if use_subgraph:
                lines.append(f'    subgraph L{layer}["Layer {layer}"]')
                indent = "        "
            else:
                indent = "    "
            for node in nodes:
                lines.append(indent + self._node_line(node))
            if use_subgraph:
                lines.append("    end")
            lines.append("")

        # 連線定義
        lines.append("    %% ===== 連線 =====")
        for edge in graph.edges:
            lines.append("    " + self._edge_line(edge))
        lines.append("")

        # classDef 樣式定義
        lines.append("    %% ===== 樣式定義 =====")
        used_types = sorted(
            set(node.node_type for node in graph.nodes),
            key=lambda t: list(NodeType).index(t),
        )
        for ntype in used_types:
            class_name = _MERMAID_CLASS_NAMES[ntype]
            color = NODE_TYPE_COLORS[ntype]
            lines.append(
                f"    classDef {class_name} fill:{color},stroke:#ffffff,"
                f"stroke-width:1px,color:#ffffff"
            )

        return "\n".join(lines) + "\n"

    # ------------------------------------------------------------------
    # 內部方法
    # ------------------------------------------------------------------

    def _node_line(self, node: PipelineNode) -> str:
        """生成單個節點的 Mermaid 定義行。

        Args:
            node: 節點數據。

        Returns:
            Mermaid 節點定義行字符串。
        """
        nid = _sanitize_mermaid_id(node.id)
        label = _sanitize_mermaid_label(node.name or node.id)
        template = self._SHAPE_TEMPLATES.get(
            node.node_type, self._SHAPE_TEMPLATES[NodeType.DATA_TRANSFORM]
        )
        class_name = _MERMAID_CLASS_NAMES.get(
            node.node_type, _MERMAID_CLASS_NAMES[NodeType.DATA_TRANSFORM]
        )
        node_def = template % (nid, label)
        return f"{node_def}:::{class_name}"

    def _edge_line(self, edge: PipelineEdge) -> str:
        """生成單條連線的 Mermaid 定義行。

        Args:
            edge: 連線數據。

        Returns:
            Mermaid 連線定義行字符串。
        """
        src = _sanitize_mermaid_id(edge.source)
        tgt = _sanitize_mermaid_id(edge.target)
        if edge.label:
            label = _sanitize_mermaid_label(edge.label)
            return f"{src} -->|{label}| {tgt}"
        return f"{src} --> {tgt}"


# =============================================================================
# 主入口類
# =============================================================================

class PipelineVisualizer:
    """Pipeline 可視化主入口類。

    封裝 XML 解析、佈局計算、SVG / Mermaid 渲染的完整流程，
    提供簡潔的 API 供外部項目複用。

    使用示例::

        viz = PipelineVisualizer()
        graph = viz.parse_file("pipeline.xml")
        viz.render_svg(graph, "output.svg")
        mermaid_text = viz.render_mermaid(graph)
        print(mermaid_text)
    """

    def __init__(self) -> None:
        """初始化可視化器，創建內部解析器和渲染器實例。"""
        self._parser = PipelineXMLParser()
        self._svg_renderer = SVGRenderer()
        self._mermaid_renderer = MermaidRenderer()

    # ------------------------------------------------------------------
    # 解析方法
    # ------------------------------------------------------------------

    def parse_file(self, xml_path: str) -> PipelineGraph:
        """從 XML 文件解析 Pipeline 圖。

        自動檢測格式（DagPipeline / Pipeline V1 / Topology），
        解析後自動應用 Kahn 分層佈局。

        Args:
            xml_path: XML 文件路徑。

        Returns:
            解析並佈局後的 :class:`PipelineGraph`。

        Raises:
            FileNotFoundError: 文件不存在。
            ValueError: XML 格式錯誤。
        """
        graph = self._parser.parse_file(xml_path)
        KahnLayoutCalculator.apply(graph)
        return graph

    def parse_string(self, xml_content: str) -> PipelineGraph:
        """從 XML 字符串解析 Pipeline 圖。

        自動檢測格式，解析後自動應用 Kahn 分層佈局。

        Args:
            xml_content: XML 文本內容。

        Returns:
            解析並佈局後的 :class:`PipelineGraph`。

        Raises:
            ValueError: XML 格式錯誤。
        """
        graph = self._parser.parse_string(xml_content)
        KahnLayoutCalculator.apply(graph)
        return graph

    # ------------------------------------------------------------------
    # 渲染方法
    # ------------------------------------------------------------------

    def render_svg(
        self,
        graph: PipelineGraph,
        output_path: str,
        title: str = "",
    ) -> str:
        """渲染 SVG 文件。

        Args:
            graph: :class:`PipelineGraph` 圖數據。
            output_path: 輸出 ``.svg`` 文件路徑。
            title: 可選自定義標題。

        Returns:
            實際寫入的文件路徑。
        """
        return self._svg_renderer.render(graph, output_path, title)

    def render_mermaid(self, graph: PipelineGraph, use_subgraph: bool = True) -> str:
        """渲染 Mermaid 流程圖文本。

        Args:
            graph: :class:`PipelineGraph` 圖數據。
            use_subgraph: 是否使用 subgraph 包裹同層節點。

        Returns:
            Mermaid 文本字符串。
        """
        return self._mermaid_renderer.render(graph, use_subgraph=use_subgraph)

    # ------------------------------------------------------------------
    # 一站式方法
    # ------------------------------------------------------------------

    def render_all(
        self,
        xml_path: str,
        output_dir: str,
        title: str = "",
    ) -> Dict[str, str]:
        """一站式解析並渲染所有格式（SVG + Mermaid）。

        解析 XML 文件後，同時生成 SVG 文件和 Mermaid 文本文件，
        輸出到指定目錄。文件名基於圖的 id 或輸入文件名。

        Args:
            xml_path: 輸入 XML 文件路徑。
            output_dir: 輸出目錄。
            title: 可選自定義標題。

        Returns:
            ``{"svg": svg_path, "mermaid": mmd_path, "mermaid_text": text}`` 字典。

        Raises:
            FileNotFoundError: XML 文件不存在。
            ValueError: XML 格式錯誤。
        """
        graph = self.parse_file(xml_path)

        # 確定輸出文件名
        base_name = graph.id or Path(xml_path).stem
        os.makedirs(output_dir, exist_ok=True)

        svg_path = os.path.join(output_dir, f"{base_name}.svg")
        mmd_path = os.path.join(output_dir, f"{base_name}.mmd")

        self.render_svg(graph, svg_path, title)

        mermaid_text = self.render_mermaid(graph)
        with open(mmd_path, "w", encoding="utf-8") as f:
            f.write(mermaid_text)

        return {
            "svg": svg_path,
            "mermaid": mmd_path,
            "mermaid_text": mermaid_text,
        }


# =============================================================================
# 項目架構圖構建器
# =============================================================================

class ArchitectureDiagramBuilder:
    """項目架構流程圖構建器。

    支持手動構建圖（不從 XML 解析），用於生成整個項目的架構流程圖。
    通過 :meth:`add_node` 和 :meth:`add_edge` 定義節點和連線，
    最後調用 :meth:`build` 生成 :class:`PipelineGraph`。

    使用示例::

        builder = ArchitectureDiagramBuilder("project_arch", "項目架構圖")
        builder.add_node("data", "數據採集層", NodeType.DATA_SOURCE, layer=0)
        builder.add_node("strategy", "策略分析層", NodeType.STRATEGY, layer=1)
        builder.add_node("dag", "DAG Pipeline層", NodeType.AGGREGATION, layer=2)
        builder.add_node("trade", "交易執行層", NodeType.TRADE_ACTION, layer=3)
        builder.add_node("holding", "持倉管理層", NodeType.DATA_TRANSFORM, layer=4)
        builder.add_node("sell", "賣出引擎", NodeType.FILTER, layer=3)
        builder.add_edge("data", "strategy")
        builder.add_edge("strategy", "dag")
        builder.add_edge("dag", "trade")
        builder.add_edge("trade", "holding")
        builder.add_edge("holding", "sell")
        builder.add_edge("sell", "strategy", label="反饋")
        graph = builder.build()

        viz = PipelineVisualizer()
        viz.render_svg(graph, "architecture.svg", title="項目架構")
    """

    def __init__(self, graph_id: str = "architecture", graph_name: str = "架構圖") -> None:
        """初始化架構圖構建器。

        Args:
            graph_id: 圖的唯一標識。
            graph_name: 圖的名稱。
        """
        self._id: str = graph_id
        self._name: str = graph_name
        self._nodes: List[PipelineNode] = []
        self._edges: List[PipelineEdge] = []
        self._layer_max: int = 0

    def add_node(
        self,
        id: str,
        name: str,
        node_type: NodeType = NodeType.DATA_TRANSFORM,
        layer: int = 0,
        module: str = "",
    ) -> "ArchitectureDiagramBuilder":
        """添加一個節點。

        Args:
            id: 節點唯一標識。
            name: 節點顯示名稱。
            node_type: 節點類型。
            layer: 節點所在層級。
            module: 可選模塊標識。

        Returns:
            返回 ``self`` 以支持鏈式調用。
        """
        self._nodes.append(
            PipelineNode(
                id=id,
                name=name,
                module=module,
                node_type=node_type,
                layer=layer,
            )
        )
        self._layer_max = max(self._layer_max, layer)
        return self

    def add_edge(
        self,
        from_id: str,
        to_id: str,
        label: str = "",
    ) -> "ArchitectureDiagramBuilder":
        """添加一條連線。

        Args:
            from_id: 源節點 id。
            to_id: 目標節點 id。
            label: 連線標籤（可選）。

        Returns:
            返回 ``self`` 以支持鏈式調用。
        """
        self._edges.append(PipelineEdge(source=from_id, target=to_id, label=label))
        return self

    def build(self) -> PipelineGraph:
        """構建 :class:`PipelineGraph`。

        將已添加的節點和連線組裝為 :class:`PipelineGraph`，
        並根據手動指定的層級計算節點座標。

        Returns:
            構建好的 :class:`PipelineGraph`。
        """
        graph = PipelineGraph(
            id=self._id,
            name=self._name,
            description="",
            nodes=list(self._nodes),
            edges=list(self._edges),
            format_hint="manual",
        )
        # 根據手動指定的層級計算座標
        KahnLayoutCalculator.compute_positions_from_layers(graph)
        return graph


# =============================================================================
# 輔助函數
# =============================================================================

def _esc(text: str) -> str:
    """轉義 XML/SVG 特殊字符。

    Args:
        text: 原始文本。

    Returns:
        轉義後的安全文本。
    """
    if not text:
        return ""
    return _xml_escape(str(text))


def _sanitize_mermaid_id(raw_id: str) -> str:
    """將節點 id 清理為合法的 Mermaid 標識符。

    Mermaid 節點 id 僅允許字母、數字、下劃線。其他字符替換為下劃線。

    Args:
        raw_id: 原始 id。

    Returns:
        清理後的 Mermaid 合法 id。
    """
    if not raw_id:
        return "node"
    result = []
    for ch in raw_id:
        if ch.isalnum() or ch == "_":
            result.append(ch)
        else:
            result.append("_")
    sanitized = "".join(result)
    if not sanitized or not sanitized[0].isalpha() and sanitized[0] != "_":
        sanitized = "n_" + sanitized
    return sanitized


def _sanitize_mermaid_label(label: str) -> str:
    """清理 Mermaid 節點標籤文本。

    移除/替換 Mermaid 語法中的特殊字符（雙引號、管道符、換行等）。

    Args:
        label: 原始標籤。

    Returns:
        清理後的標籤文本。
    """
    if not label:
        return ""
    text = str(label)
    text = text.replace('"', "'")
    text = text.replace("|", "/")
    text = text.replace("\n", " ")
    text = text.replace("<", "(")
    text = text.replace(">", ")")
    text = text.replace("[", "(")
    text = text.replace("]", ")")
    text = text.replace("{", "(")
    text = text.replace("}", ")")
    return text.strip()


# =============================================================================
# 命令行接口
# =============================================================================

def _build_arg_parser() -> argparse.ArgumentParser:
    """構建命令行參數解析器。

    Returns:
        :class:`argparse.ArgumentParser` 實例。
    """
    parser = argparse.ArgumentParser(
        prog="pipeline_visualizer",
        description="Pipeline XML 解析與流程圖可視化工具（SVG / Mermaid）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "示例:\n"
            "  python pipeline_visualizer.py pipeline.xml\n"
            "  python pipeline_visualizer.py pipeline.xml -o output\n"
            "  python pipeline_visualizer.py pipeline.xml -o output --format svg,mermaid\n"
            "  python pipeline_visualizer.py pipeline.xml --format mermaid --no-subgraph\n"
        ),
    )
    parser.add_argument(
        "input",
        help="輸入的 Pipeline XML 文件路徑",
    )
    parser.add_argument(
        "-o", "--output",
        default=".",
        help="輸出目錄（默認當前目錄）",
    )
    parser.add_argument(
        "--format",
        default="svg,mermaid",
        help="輸出格式，逗號分隔（svg, mermaid），默認 'svg,mermaid'",
    )
    parser.add_argument(
        "--title",
        default="",
        help="自定義標題（覆蓋 XML 中的 name）",
    )
    parser.add_argument(
        "--no-subgraph",
        action="store_true",
        help="Mermaid 輸出不使用 subgraph 包裹同層節點",
    )
    return parser


def main(argv: Optional[List[str]] = None) -> int:
    """命令行入口函數。

    Args:
        argv: 命令行參數列表（默認從 ``sys.argv`` 讀取）。

    Returns:
        進程退出碼（0 成功，1 失敗）。
    """
    parser = _build_arg_parser()
    args = parser.parse_args(argv)

    # 驗證輸入文件
    if not os.path.exists(args.input):
        print(f"錯誤: 輸入文件不存在: {args.input}", file=sys.stderr)
        return 1

    # 解析格式參數
    formats = [f.strip().lower() for f in args.format.split(",") if f.strip()]
    valid_formats = {"svg", "mermaid"}
    for fmt in formats:
        if fmt not in valid_formats:
            print(f"錯誤: 不支持的格式 '{fmt}'，支持: {', '.join(valid_formats)}", file=sys.stderr)
            return 1

    # 確保輸出目錄存在
    os.makedirs(args.output, exist_ok=True)

    viz = PipelineVisualizer()

    # 解析 XML
    try:
        graph = viz.parse_file(args.input)
    except (FileNotFoundError, ValueError) as exc:
        print(f"錯誤: {exc}", file=sys.stderr)
        return 1

    base_name = graph.id or os.path.splitext(os.path.basename(args.input))[0]
    title = args.title or graph.name

    print(f"已解析: {graph.name or graph.id}")
    print(f"  格式: {graph.format_hint}")
    print(f"  節點數: {len(graph.nodes)}")
    print(f"  連線數: {len(graph.edges)}")

    # 渲染 SVG
    if "svg" in formats:
        svg_path = os.path.join(args.output, f"{base_name}.svg")
        viz.render_svg(graph, svg_path, title)
        print(f"  SVG 已生成: {svg_path}")

    # 渲染 Mermaid
    if "mermaid" in formats:
        mmd_path = os.path.join(args.output, f"{base_name}.mmd")
        use_subgraph = not args.no_subgraph
        mermaid_text = viz.render_mermaid(graph, use_subgraph=use_subgraph)
        with open(mmd_path, "w", encoding="utf-8") as f:
            f.write(mermaid_text)
        print(f"  Mermaid 已生成: {mmd_path}")

    return 0


# =============================================================================
# 模塊入口
# =============================================================================

if __name__ == "__main__":
    sys.exit(main())
