# -*- coding: utf-8 -*-
"""轻量 SVG → PNG 光栅化器（针对 pipeline_visualizer 生成的架构图）。

支持元素：
- 背景渐变矩形（简化为垂直线性渐变）
- 节点圆角矩形 rect（fill/stroke/fill-opacity）
- 多行文本 text（text-anchor=start/middle/end，支持中文 \n 多行）
- 贝塞尔曲线 path（M + C），终点自动加方向箭头（近似 marker）

用法：
  python svg_to_png.py input.svg output.png [scale]
"""
import math
import os
import re
import sys

from PIL import Image, ImageDraw, ImageFont

# 中文字体（微软雅黑，支持粗体/常规）
FONT_BOLD = r"C:\Windows\Fonts\msyhbd.ttc"
FONT_REG = r"C:\Windows\Fonts\msyh.ttc"

FONT_CACHE = {}


def font(size: int, bold: bool) -> ImageFont.FreeTypeFont:
    key = (size, bold)
    if key not in FONT_CACHE:
        path = FONT_BOLD if bold else FONT_REG
        try:
            FONT_CACHE[key] = ImageFont.truetype(path, size)
        except Exception:
            FONT_CACHE[key] = ImageFont.load_default()
    return FONT_CACHE[key]


def parse_floats(s: str) -> list:
    return [float(x) for x in re.findall(r"[-+]?\d*\.?\d+", s)]


def lerp(a, b, t):
    return a + (b - a) * t


def cubic(p0, p1, p2, p3, t):
    mt = 1 - t
    return (
        mt ** 3 * p0[0] + 3 * mt * mt * t * p1[0] + 3 * mt * t * t * p2[0] + t ** 3 * p3[0],
        mt ** 3 * p0[1] + 3 * mt * mt * t * p1[1] + 3 * mt * t * t * p2[1] + t ** 3 * p3[1],
    )


def hex_to_rgb(h: str):
    h = h.lstrip("#")
    if len(h) == 3:
        h = "".join(c * 2 for c in h)
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def parse_svg(svg: str):
    """返回 (width, height, elements)。elements: 按文档顺序的 dict 列表。"""
    width = int(float(re.search(r'width="([\d.]+)"', svg).group(1)))
    height = int(float(re.search(r'height="([\d.]+)"', svg).group(1)))
    elements = []
    # rect
    for m in re.finditer(r'<rect\b[^>]*?/?>', svg):
        attrs = dict(re.findall(r'(\w[\w-]*)="([^"]*)"', m.group(0)))
        elements.append(("rect", attrs))
    # path
    for m in re.finditer(r'<path\b[^>]*?/?>', svg):
        attrs = dict(re.findall(r'(\w[\w-]*)="([^"]*)"', m.group(0)))
        elements.append(("path", attrs))
    # text
    for m in re.finditer(r'<text\b[^>]*>(.*?)</text>', svg, re.S):
        attrs = dict(re.findall(r'(\w[\w-]*)="([^"]*)"', m.group(0)))
        attrs["__text__"] = m.group(1)
        elements.append(("text", attrs))
    return width, height, elements


def draw_path(d: str, draw, scale, stroke, width=2, opacity=1.0):
    """绘制 M + C 贝塞尔曲线，终点画方向箭头。"""
    color = tuple(int(c * opacity + 255 * (1 - opacity)) for c in hex_to_rgb(stroke))
    # 解析命令
    tokens = re.findall(r"[MCLZ]|[-+]?\d*\.?\d+", d)
    pos = 0
    pts = []
    cmds = []
    i = 0
    while i < len(tokens):
        t = tokens[i]
        if t in "MCLZ":
            cmds.append((t, []))
            i += 1
        else:
            nums = []
            while i < len(tokens) and tokens[i] not in "MCLZ":
                nums.append(float(tokens[i]))
                i += 1
            cmds[-1][1].extend(nums)
    cur = None
    start = None
    segments = []
    for cmd, nums in cmds:
        if cmd == "M":
            cur = (nums[0], nums[1])
            start = cur
        elif cmd == "C":
            if len(nums) == 6:
                p0 = cur
                p1 = (nums[0], nums[1])
                p2 = (nums[2], nums[3])
                p3 = (nums[4], nums[5])
                # 细分采样（比直线更平滑）
                prev = p0
                pts_draw = [p0]
                for k in range(1, 41):
                    t = k / 40.0
                    p = cubic(p0, p1, p2, p3, t)
                    pts_draw.append(p)
                segments.append(pts_draw)
                cur = p3
        elif cmd == "L":
            p = (nums[0], nums[1])
            segments.append([cur, p])
            cur = p
        elif cmd == "Z":
            segments.append([cur, start])
            cur = start
    # 画线
    for seg in segments:
        for k in range(1, len(seg)):
            x1, y1 = seg[k - 1]
            x2, y2 = seg[k]
            draw.line([x1 * scale, y1 * scale, x2 * scale, y2 * scale],
                      fill=color, width=max(1, int(width * scale)))
    # 箭头：取最后一段终点方向
    if segments:
        seg = segments[-1]
        p_end = seg[-1]
        p_prev = seg[-2] if len(seg) >= 2 else seg[0]
        dx = p_end[0] - p_prev[0]
        dy = p_end[1] - p_prev[1]
        ang = math.atan2(dy, dx)
        size = max(4.0, width * 3.2 * scale)
        ex, ey = p_end[0] * scale, p_end[1] * scale
        a = ang + math.pi
        # 三角形顶点朝向终点
        tri = [
            (ex + math.cos(a) * size * 1.2, ey + math.sin(a) * size * 1.2),
            (ex + math.cos(a + 2.6) * size, ey + math.sin(a + 2.6) * size),
            (ex + math.cos(a - 2.6) * size, ey + math.sin(a - 2.6) * size),
        ]
        draw.polygon(tri, fill=color)


def draw_text(text: str, attrs, draw, scale):
    x = float(attrs.get("x", "0"))
    y = float(attrs.get("y", "0"))
    size = int(float(attrs.get("font-size", "12")))
    anchor = attrs.get("text-anchor", "start")
    fill = attrs.get("fill", "#ffffff")
    bold = "bold" in attrs.get("font-weight", "")
    lines = text.split("\n")
    f = font(size, bold)
    line_h = int(size * 1.5)
    # 以首行基线 y 为基准向下排布
    for li, line in enumerate(lines):
        if not line.strip():
            continue
        yy = y + li * line_h
        tw = draw.textlength(line, font=f)
        if anchor == "middle":
            xx = x - tw / 2
        elif anchor == "end":
            xx = x - tw
        else:
            xx = x
        draw.text((xx * scale, (yy - size) * scale), line, font=f, fill=hex_to_rgb(fill))


def convert(svg_path, png_path, scale=2):
    svg = open(svg_path, encoding="utf-8").read()
    width, height, elements = parse_svg(svg)
    img = Image.new("RGB", (width * scale, height * scale))
    draw = ImageDraw.Draw(img)
    # 背景垂直渐变
    c1 = hex_to_rgb("#0f172a")
    c2 = hex_to_rgb("#1e293b")
    for yy in range(height * scale):
        t = yy / max(height * scale - 1, 1)
        color = tuple(int(lerp(c1[i], c2[i], t)) for i in range(3))
        draw.line([(0, yy), (width * scale, yy)], fill=color)
    for kind, attrs in elements:
        if kind == "rect":
            x = float(attrs.get("x", 0))
            y = float(attrs.get("y", 0))
            w = float(attrs.get("width", 0))
            h = float(attrs.get("height", 0))
            rx = float(attrs.get("rx", 0))
            fill = attrs.get("fill", "#333")
            # 跳过 url(...) 渐变引用与铺满全图的背景矩形（背景已单独画渐变）
            if fill.startswith("url(") or (w >= width - 1 and h >= height - 1):
                continue
            opacity = float(attrs.get("fill-opacity", "1"))
            color = hex_to_rgb(fill)
            if opacity < 1:
                color = tuple(int(c * opacity + 255 * (1 - opacity)) for c in color)
            radius = int(rx * scale)
            draw.rounded_rectangle(
                [x * scale, y * scale, (x + w) * scale, (y + h) * scale],
                radius=radius, fill=color,
            )
            if attrs.get("stroke"):
                sw = max(1, int(float(attrs.get("stroke-width", "1")) * scale))
                draw.rounded_rectangle(
                    [x * scale, y * scale, (x + w) * scale, (y + h) * scale],
                    radius=radius, outline=hex_to_rgb(attrs["stroke"]), width=sw,
                )
        elif kind == "path":
            d = attrs.get("d", "")
            if not d:
                continue
            stroke = attrs.get("stroke")
            if not stroke:
                continue  # 箭头 marker 本体不重复画
            sw = float(attrs.get("stroke-width", "2"))
            op = float(attrs.get("opacity", "1"))
            draw_path(d, draw, scale, stroke, width=sw, opacity=op)
        elif kind == "text":
            draw_text(attrs.get("__text__", ""), attrs, draw, scale)
    os.makedirs(os.path.dirname(os.path.abspath(png_path)), exist_ok=True)
    img.save(png_path, "PNG")
    return width * scale, height * scale


if __name__ == "__main__":
    src = sys.argv[1] if len(sys.argv) > 1 else "output/full_architecture.svg"
    dst = sys.argv[2] if len(sys.argv) > 2 else src.rsplit(".", 1)[0] + ".png"
    sc = int(sys.argv[3]) if len(sys.argv) > 3 else 2
    w, h = convert(src, dst, scale=sc)
    print(f"PNG 已生成: {dst} ({w}x{h})")
