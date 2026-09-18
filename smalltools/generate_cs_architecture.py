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
    b.add_node("apk_dlg", "📡 远程面板\nRemoteControlPanel/Dialog", NodeType.DATA_TRANSFORM, layer=0)
    b.add_node("apk_client", "PcBridgeClient.kt\n业务 API（无地址概念）", NodeType.DATA_TRANSFORM, layer=0)
    b.add_node("apk_relay", "CosRelayClient.kt\n信封 + HMAC 元数据签名", NodeType.DATA_TRANSFORM, layer=0)

    # Layer 1: 传输（联网中继，已无局域网/IP）
    b.add_node("relay_cos", "腾讯云 COS 中继\npc/inbox·pc/outbox/{deviceId}", NodeType.DATA_TRANSFORM, layer=1)

    # Layer 2: PC 中继守护 + 本机服务端
    b.add_node("svc_relay", "relay_worker.py\n轮询 inbox → 本机 API → outbox", NodeType.AGGREGATION, layer=2)
    b.add_node("svc_http", "data_service.py\nThreadingHTTPServer 127.0.0.1:8888", NodeType.DATA_SOURCE, layer=2)
    b.add_node("svc_rc", "remote_control.py\nRemoteControl 任务队列", NodeType.AGGREGATION, layer=2)
    b.add_node("svc_token", "本机 API 鉴权\ndata/remote_token.txt（仅本机）", NodeType.FILTER, layer=2)
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
    b.add_edge("apk_client", "apk_relay", "转发")
    b.add_edge("apk_relay", "relay_cos", "PUT 命令 / 轮询应答")
    b.add_edge("relay_cos", "svc_relay", "轮询 pc/inbox")
    b.add_edge("svc_relay", "relay_cos", "应答写 outbox")
    b.add_edge("svc_relay", "svc_http", "调用 127.0.0.1:8888")
    b.add_edge("svc_http", "svc_rc", "任务路由")
    b.add_edge("svc_http", "svc_token", "本机 token 校验")
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
