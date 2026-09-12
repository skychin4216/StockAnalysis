# -*- coding: utf-8 -*-
"""选股候选表格 → PNG 图片（企微 image 消息专用，2026-09-08）。

背景：企业微信 text 消息非等宽字体，候选对齐表在手机上会错乱；
push_channel.send_image 支持企微群机器人 image 消息 → 把表格渲染成
整齐图片再推送（图内等宽排版真实生效，手机端观感整洁）。

用法：
    from _table_img import render_table
    path = render_table("主线·XML DAG 当日选股(6只)", header, rows, out_path)
参数：
    title  顶部大标题（自动清理 emoji/超 BMP 字符，避免 matplotlib 缺字形出豆腐块）
    header 表头，如 ["股票名称","代码",...]
    rows   list[list[str]]，每行长度 = len(header)，空值显示为 —
    out_path 输出 png 路径
    note   底部灰色小字说明（可选）

渲染规则：
    - 列宽 = 按内容「显示宽度」分配（汉字按 2、ASCII 按 1），自动避免内容溢出；
    - 表头加粗 + 浅灰底 + 分隔线；数据行白/浅蓝灰交替底色；
    - 全部在内存中 matplotlib 绘制（不弹窗），输出 ≤2MB PNG。
"""
import os
import re

_FONT_CANDIDATES = ["Microsoft YaHei", "SimHei", "PingFang SC",
                    "Noto Sans CJK SC", "WenQuanYi Zen Hei"]
# 常见 emoji → 字体可渲染的等效字符（不直接删，尽量保住语义；2026-09-10）
_EMOJI_MAP = {"⭐": "★", "🔒": "封板·", "🆕": "新·", "🟡": "流出·",
              "📋": "", "🎯": "", "📊": "", "💼": "", "📡": "", "🛰": "", "🚨": "!! "}


def _disp(s):
    """显示宽度：汉字/全角按 2、ASCII 按 1（等宽近似，排版用）。"""
    return sum(2 if ord(ch) > 0x2E7F else 1 for ch in str(s or ""))


def _clean(s):
    """清理 matplotlib 无法渲染的字符：emoji → 等效文字/删除，控制符 → 空格。

    注意 U+2B00-2BFF（如 ⭐）与超 BMP（📋📊 等）在 Microsoft YaHei 缺字形，
    会渲染成豆腐块；★(U+2605)/✓(U+2713) 等常用符号保留。
    """
    s = str(s or "")
    for k, v in _EMOJI_MAP.items():
        s = s.replace(k, v)
    s = re.sub(r"[\U00010000-\U0010FFFF]", "", s)   # 超 BMP（emoji 主体）
    s = re.sub(r"[\u2B00-\u2BFF\uFE0F]", "", s)     # 杂项符号与箭头补充区 + 变体选择符
    s = re.sub(r"[\x00-\x1f]", " ", s)
    return s.strip()


def _plt():
    """惰性初始化 matplotlib（Agg 后端 + 中文字体回退）。"""
    import matplotlib
    matplotlib.use("Agg")
    from matplotlib import font_manager, rcParams
    import matplotlib.pyplot as plt
    try:
        names = {f.name for f in font_manager.fontManager.ttflist}
        for cand in _FONT_CANDIDATES:
            if cand in names:
                rcParams["font.sans-serif"] = [cand] + rcParams["font.sans-serif"]
                break
    except Exception:  # noqa: BLE001
        pass
    rcParams["axes.unicode_minus"] = False
    return plt


def render_multi_table(title, sections, out_path, note=None, dpi=110):
    """多段候选表 → 单张 PNG（2026-09-10：DAG 段 + SmallTool 段合并，各自标题保留）。

    sections: [{"title": 分段标题, "header": [列名], "rows": [[...]]}, ...]
    - 列宽：**表头相同的段共用一套列宽**（逐列取组内最大）→ 同表头的段严格逐列对齐
      （2026-09-12；此前每段独立算列宽，表头相同也会错开）；表头不同的段各自独立成组；
    - 每段先画一条跨列的分段标题带（浅蓝底加粗），再画表头与数据行（斑马纹）；
    - title 为总标题，可传 None/""（不画顶部大标题）；
    - 单段传入时等价于 render_table，便于统一调用；
    - dpi 可下调控文件体积（_table_csv 超 2MB 时逐档降 dpi 用）。
    """
    plt = _plt()
    from matplotlib.patches import Rectangle

    secs = [s for s in (sections or []) if s and (s.get("rows") or [])]
    if not secs:
        raise ValueError("render_multi_table: 无有效分段")
    if len(secs) == 1 and secs[0].get("header") and not title:
        return render_table(secs[0].get("title") or "", secs[0]["header"],
                            secs[0]["rows"], out_path, note=note, dpi=dpi)

    body_pt = 15.5
    head_pt = body_pt + 1.5
    title_pt = 21
    sec_pt = 16.5
    note_pt = 12.5
    px_per_unit = body_pt * dpi / 72.0 / 2.0
    text_pad = 14
    row_h = int(body_pt * dpi / 72.0 * 2.1)
    head_h = row_h + 6
    sec_h = row_h + 4
    cell_gap = 3.0
    extra_unit = 1.0

    # heterogeneous sections（不同表头列数）也支持：每段按自己的列数绘制，段与段之间不
    # 用全局最大列数补齐成「—」，这样 16 列候选表与 6/8 列复盘表可在同一张图里各自独立。
    def _sec_metrics(s):
        hdr = s.get("header") or []
        rows = s.get("rows") or []
        nc = max(len(hdr), max((len(r) for r in rows), default=0))
        cws = []
        for i in range(nc):
            w = _disp(hdr[i]) if i < len(hdr) else 0
            for r in rows:
                if i < len(r):
                    w = max(w, _disp(r[i]))
            cws.append(int((w + extra_unit) * px_per_unit) + 2 * text_pad)
        grid = sum(cws) + (nc - 1) * int(cell_gap) + cell_gap
        return nc, cws, grid

    sec_meta = [_sec_metrics(s) for s in secs]
    # 2026-09-12：同表头的段共用一套列宽 → 跨段逐列严格对齐。
    # 此前每段各自按内容算列宽，即使表头一致也会因内容长度不同而列宽错开（长图列宽对不齐）；
    # 表头一致（列数列名完全相同）的段归为一组取逐列最大宽度，表头不同的段仍各自独立成组。
    _group = {}
    for _i, _s in enumerate(secs):
        _group.setdefault(tuple(_s.get("header") or []), []).append(_i)
    for _idxs in _group.values():
        if len(_idxs) < 2:
            continue
        _nc_max = max(sec_meta[_i][0] for _i in _idxs)
        _cws = [0] * _nc_max
        for _i in _idxs:
            for _j, _w in enumerate(sec_meta[_i][1]):
                _cws[_j] = max(_cws[_j], _w)
        for _i in _idxs:
            _nc = sec_meta[_i][0]
            _grid = sum(_cws[:_nc]) + (_nc - 1) * int(cell_gap) + cell_gap
            sec_meta[_i] = (_nc, _cws, _grid)
    M = 26
    W = max(1240, max(m[2] for m in sec_meta) + 2 * M)
    top_gap = 116 if title else 34
    seg_h = sum(sec_h + head_h + len(s["rows"]) * row_h + 10 for s in secs)
    bottom_gap = 40 + (26 if note else 0)
    H = top_gap + seg_h + bottom_gap

    fig = plt.figure(figsize=(W / dpi, H / dpi), dpi=dpi)
    ax = fig.add_axes([0, 0, 1, 1])
    ax.set_xlim(0, W)
    ax.set_ylim(0, H)
    ax.axis("off")

    if title:
        ax.text(W / 2, H - 58, _clean(title), ha="center", va="center",
                fontsize=title_pt, fontweight="bold", color="#1A1A1A")

    HEAD_BG, HEAD_FG, ZEBRA, GRID = "#3B5B92", "#FFFFFF", "#F2F6FB", "#C9D3E0"
    SEC_BG, SEC_FG = "#E8EEF7", "#24406E"

    def _cell_text(x, y, w, h, val, bold=False, color="#1A1A1A", size=body_pt):
        ax.text(x + text_pad, y + h / 2, _clean(val), ha="left", va="center",
                fontsize=size, fontweight="bold" if bold else "normal", color=color)

    def _box(x, y, w, h, bg):
        ax.add_patch(Rectangle((x, y), w, h, facecolor=bg, edgecolor=GRID, lw=0.9))

    y = H - top_gap
    for si, s in enumerate(secs):
        nc, cws, grid = sec_meta[si]
        # 左对齐（2026-09-12 用户确认）：段标题带与数据表共用同一条左边缘，
        # 此前表格居中、标题贴左边距 → 观感上「标题左对齐、内容居中」，改为全左对齐。
        x0 = M
        band_w = min(grid, W - 2 * M)
        _box(x0, y, band_w, sec_h, SEC_BG)
        _cell_text(x0, y, band_w, sec_h, s.get("title") or "", bold=True,
                   color=SEC_FG, size=sec_pt)
        y -= sec_h
        hdr = s.get("header") or []
        if hdr:
            x = x0
            for i in range(nc):
                _box(x, y, cws[i] + cell_gap, head_h, HEAD_BG)
                _cell_text(x, y, cws[i], head_h,
                           hdr[i] if i < len(hdr) else "", bold=True,
                           color=HEAD_FG, size=head_pt)
                x += cws[i] + cell_gap
            y -= head_h
        for ri, r in enumerate(s["rows"]):
            bg = ZEBRA if ri % 2 else "#FFFFFF"
            x = x0
            for ci in range(nc):
                v = (r[ci] if ci < len(r) else "") or "—"
                _box(x, y, cws[ci] + cell_gap, row_h, bg)
                _cell_text(x, y, cws[ci], row_h, v, size=body_pt)
                x += cws[ci] + cell_gap
            y -= row_h
        y -= 10

    if note:
        ax.text(M, 16, _clean(note), ha="left", va="bottom",
                fontsize=note_pt, color="#757575")

    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    fig.savefig(out_path, dpi=dpi)
    plt.close(fig)
    return out_path


def render_table(title, header, rows, out_path, note=None, dpi=110):
    """渲染表格为 PNG。返回 out_path；失败抛异常由调用方兜底。"""
    plt = _plt()
    from matplotlib.patches import Rectangle

    # 字号按像素目标换算（px = pt * dpi / 72）
    body_pt = 15.5            # 数据字 15.5pt → ~23.7px
    head_pt = body_pt + 1.5
    title_pt = 21
    note_pt = 12.5
    px_per_unit = body_pt * dpi / 72.0 / 2.0   # 每 1 显示单位宽度 ≈ 汉字半宽
    text_pad = 14             # 单元格左右留白（每侧）
    row_h = int(body_pt * dpi / 72.0 * 2.1)    # 行高 ≈ 2.1 倍字高
    head_h = row_h + 6
    cell_gap = 3.0            # 单元格之间的水平间隙(网格线视觉)
    extra_unit = 1.0          # 每列在内容最大显示宽度上额外加的单位宽度(避免表头紧贴)

    # 列宽：表头与内容显示宽度最大值 → px
    n_col = len(header)
    col_w = []
    for i in range(n_col):
        w = _disp(header[i])
        for r in rows:
            v = r[i] if i < len(r) else ""
            w = max(w, _disp(v))
        col_w.append(int((w + extra_unit) * px_per_unit) + 2 * text_pad)

    M = 26                    # 画布左右边距
    W = max(1240, int(sum(col_w)) + 2 * M + (n_col - 1) * int(cell_gap))
    top_gap = 116             # 标题区高度（顶栏标题与表头间留足距离，防止遮挡）
    bottom_gap = 40 + (26 if note else 0)
    H = top_gap + head_h + len(rows) * row_h + bottom_gap

    fig = plt.figure(figsize=(W / dpi, H / dpi), dpi=dpi)
    ax = fig.add_axes([0, 0, 1, 1])
    ax.set_xlim(0, W)
    ax.set_ylim(0, H)
    ax.axis("off")

    # 标题（位于顶部预留区内居中；top_gap 与其上下都留有间隙，不与表头重叠）
    ax.text(W / 2, H - 58, _clean(title), ha="center", va="center",
            fontsize=title_pt, fontweight="bold", color="#1A1A1A")

    HEAD_BG = "#3B5B92"
    HEAD_FG = "#FFFFFF"
    ZEBRA = "#F2F6FB"
    GRID = "#C9D3E0"

    def _cell_text(x, y, w, h, val, bold=False, color="#1A1A1A", size=body_pt):
        ax.text(x + text_pad, y + h / 2, _clean(val), ha="left", va="center",
                fontsize=size, fontweight="bold" if bold else "normal", color=color)

    def _box(x, y, w, h, bg):
        ax.add_patch(Rectangle((x, y), w, h, facecolor=bg, edgecolor=GRID, lw=0.9))

    y = H - top_gap
    # 表头行
    x = M
    for i, hname in enumerate(header):
        _box(x, y, col_w[i] + cell_gap, head_h, HEAD_BG)
        _cell_text(x, y, col_w[i], head_h, hname, bold=True, color=HEAD_FG,
                   size=head_pt)
        x += col_w[i] + cell_gap
    y -= head_h
    # 数据行
    for ri, r in enumerate(rows):
        bg = ZEBRA if ri % 2 else "#FFFFFF"
        x = M
        for ci in range(n_col):
            v = (r[ci] if ci < len(r) else "") or "—"
            _box(x, y, col_w[ci] + cell_gap, row_h, bg)
            _cell_text(x, y, col_w[ci], row_h, v,
                       size=body_pt if ri % 2 == 0 else body_pt)
            x += col_w[ci] + cell_gap
        y -= row_h
    # 底部说明（可选）
    if note:
        ax.text(M, 16, _clean(note), ha="left", va="bottom",
                fontsize=note_pt, color="#757575")

    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    fig.savefig(out_path, dpi=dpi)
    plt.close(fig)
    return out_path


if __name__ == "__main__":
    # 自检样例：控制台生成一张示例图验证排版/字体
    _header = ["股票名称", "代码", "趋势图谱", "星后形态", "RSI", "SAR",
               "MACD", "OBV", "均线粘合", "换手", "量比"]
    _rows = [
        ["封板·工业富联", "601138", "连跌2天→十字星", "星后大阳", "32.5",
         "SAR红3", "金叉", "↑量能配合", "粘合", "3.2%", "2.1"],
        ["新·中科曙光", "603019", "回踩MA20", "—", "45.1",
         "SAR红5", "金叉", "↑", "多头", "5.8%", "3.4"],
        ["流出·宁德时代", "300750", "破位", "—", "28.9",
         "SAR绿2", "死叉", "↓", "发散", "1.2%", "0.9"],
    ]
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "data", "_table_sample.png")
    render_table("主线·XML DAG 当日选股(3只)（形态匹配只标注，看涨才买入）",
                 _header, _rows, out, note="🔒=封板当日不可买 · 🆕=本轮新晋 · 🟡=资金流出（emoji 在图中以文字标注替代）")
    print("样例已生成:", out)
