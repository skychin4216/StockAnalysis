# -*- coding: utf-8 -*-
"""生成 C/S 远程控制架构图（APK 客户端 → PC 服务端 → 执行器）SVG+PNG。"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pipeline_visualizer import PipelineVisualizer, ArchitectureDiagramBuilder, NodeType

import pipeline_visualizer as pv
pv.LAYER_SPACING = 320

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "output")


def build():
    b = ArchitectureDiagramBuilder()
    # Layer 0: APK 客户端
    b.add_node("apk_bench", "量化工作台\nPcCandidatesDialog", NodeType.TRADE_ACTION, layer=0)
    b.add_node("apk_dlg", "🎛 RemoteControlDialog\n(新 UI: 提交/状态/日志/取消)", NodeType.DATA_TRANSFORM, layer=0)
    b.add_node("apk_client", "PcBridgeClient.kt\nOkHttp + X-Token", NodeType.DATA_TRANSFORM, layer=0)

    # Layer 1: 传输
    b.add_node("http", "局域网 HTTP :8888\nJSON REST + 2s 长轮询", NodeType.DATA_TRANSFORM, layer=1)

    # Layer 2: PC 服务端
    b.add_node("svc_http", "data_service.py\nThreadingHTTPServer", NodeType.DATA_SOURCE, layer=2)
    b.add_node("svc_rc", "remote_control.py\nRemoteControl 任务队列", NodeType.AGGREGATION, layer=2)
    b.add_node("svc_token", "Token 鉴权\ndata/remote_token.txt", NodeType.FILTER, layer=2)
    b.add_node("svc_store", "任务持久化\ndata/remote_tasks.json", NodeType.DATA_SOURCE, layer=2)

    # Layer 3: 执行器
    b.add_node("ex_small", "smalltools 脚本\n选股/回测/拟合/刷新/上传", NodeType.STRATEGY, layer=3)
    b.add_node("ex_cb", "CodeBuddy CLI\ncodebuddy run <prompt>", NodeType.AI_PREDICTION, layer=3)
    b.add_node("ex_adb", "adb 广播\n触发 APK 刷新", NodeType.DATA_TRANSFORM, layer=3)

    # Layer 4: 产物/云端
    b.add_node("out_cand", "candidates_quant.json\nbacktest_params.json", NodeType.AGGREGATION, layer=4)
    b.add_node("out_cos", "COS\ncandidates/params/market_data.db", NodeType.DATA_SOURCE, layer=4)

    b.add_edge("apk_bench", "apk_dlg", "打开")
    b.add_edge("apk_dlg", "apk_client", "调用")
    b.add_edge("apk_client", "http", "submit/get/list/logs/cancel")
    b.add_edge("http", "svc_http", "REST")
    b.add_edge("svc_http", "svc_rc", "任务路由")
    b.add_edge("svc_http", "svc_token", "X-Token 校验")
    b.add_edge("svc_rc", "svc_store", "读写")
    b.add_edge("svc_rc", "ex_small", "subprocess 执行")
    b.add_edge("svc_rc", "ex_cb", "subprocess 执行")
    b.add_edge("svc_rc", "ex_adb", "subprocess 执行")
    b.add_edge("ex_small", "out_cand", "产物")
    b.add_edge("ex_cb", "out_cand", "AI 结论")
    b.add_edge("out_cand", "out_cos", "上传")
    b.add_edge("out_cos", "apk_bench", "APK 下载候选/参数")
    b.add_edge("ex_adb", "apk_dlg", "adb 广播触发刷新")
    return b.build()


def main():
    os.makedirs(OUT, exist_ok=True)
    viz = PipelineVisualizer()
    graph = build()
    svg = os.path.join(OUT, "cs_architecture.svg")
    viz.render_svg(graph, svg, title="C/S 远程控制架构：APK 客户端 → PC 服务端 → smalltools/CodeBuddy/adb 执行器")
    from svg_to_png import convert
    png = os.path.join(OUT, "cs_architecture.png")
    convert(svg, png, scale=2)
    print(f"SVG: {svg}\nPNG: {png}")


if __name__ == "__main__":
    main()
