# -*- coding: utf-8 -*-
"""Inbox 自动分析（2026-09-15 新增：CS 网页 ↔ PC 后台进程互通的「上传素材」入口）。

工作流：
  1. 用户上传文本 / 日志 / CSV 到 `data/_inbox/`（CS 网页与 PC 本地共享同一根目录）
     - PC 端：直接拖入；CS 端：在文件树右键上传或粘贴保存到此路径
  2. 守护 `_publish_candidates.daemon_serve` 每 60 秒扫描一次
     - 新文件 → 自动分类（文本 / 日志 / CSV）→ 启发式提取关键指标 → 写 outbox 报告
     - 处理后归档到 `_processed/YYYY-MM-DD/`，永不删（防用户后续需要重读）
  3. 推送微信群概要：「📥 inbox 已处理 X 文件：<标题>，详见 outbox」
  4. 用户在 CS 网页向 codebuddy agent 说「看看 inbox」/「分析 outbox 报告」，
     agent 用 read_file / search_content 读 outbox 做二次深度解读

分析口径（启发式秒级，不依赖 LLM）：
  - 文本类（.txt/.md/.py/.json 等）：
      行数/字数/段落数、关键数字（百分比/股价/金额）、关键词命中
      （错误/异常/涨停/跌停/财报/营收/Q1-Q4/AI/PCB/医药…）、是否含堆栈
  - 日志类（.log）：
      总行数、ERROR/WARN/INFO 计数、堆栈首行提取、最早/最晚时间戳、关键错误模块
  - 表格类（.csv/.tsv）：
      列头、前 3 行样本、各列类型推断（数字/文本/日期）、列统计（均值/中位/最大）

输出：
  - `data/_outbox/<原名>.md`：可读分析报告（推送到微信的概要来源）
  - `data/_outbox/<原名>.json`：结构化结果（便于 agent 二次处理）
"""
import datetime
import json
import os
import re
import shutil
import sys
import time
import traceback

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INBOX = os.path.join(_ROOT, "data", "_inbox")
OUTBOX = os.path.join(_ROOT, "data", "_outbox")
PROCESSED = os.path.join(_ROOT, "data", "_processed")

_TEXT_EXT = {".txt", ".md", ".py", ".json", ".js", ".ts", ".html", ".xml", ".yaml", ".yml", ".ini", ".cfg", ".sh", ".sql", ".tex"}
_LOG_EXT = {".log", ".out", ".err"}
_TABLE_EXT = {".csv", ".tsv"}

# 关键词命中表（中文/英文都覆盖）
KW_HIGH = ("涨停", "跌停", "破位", "停牌", "异常", "崩盘", "黑天鹅", "突破", "新高", "新低",
           "limit up", "limit down", "halt", "crash", "breakdown", "breakthrough", "surge", "plunge")
KW_FIN = ("营收", "净利润", "归母", "同比", "环比", "Q1", "Q2", "Q3", "Q4", "毛利率", "净利率", "PEE", "PBE",
          "revenue", "EPS", "net profit", "YoY", "QoQ", "gross margin")
KW_ERR = ("error", "ERROR", "Error", "fail", "FAIL", "Fail", "exception", "Exception", "traceback", "Traceback",
          "异常", "错误", "失败", "崩溃", "Fatal", "FATAL")
KW_SECTOR = ("AI", "PCB", "光模块", "光通信", "半导体", "医药", "CRO", "创新药", "新能源", "光伏", "锂电",
             "汽车", "银行", "券商", "煤炭", "石油", "黄金", "军工", "机器人")
NUM_PAT = re.compile(r"[-+]?\d+\.?\d*%?")
PRICE_PAT = re.compile(r"¥\s*\d+\.?\d*|￥\s*\d+\.?\d*|\$\s*\d+\.?\d*")
STACK_PAT = re.compile(r"(Traceback|Error|Exception)[\s\S]{0,400}", re.MULTILINE)
PY_TIME_PAT = re.compile(r"(\d{4}-\d{2}-\d{2}[T\s]\d{2}:\d{2}:\d{2})")
LOG_TIME_PAT = re.compile(r"^(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2})", re.MULTILINE)


def _ensure_dirs():
    for d in (INBOX, OUTBOX, PROCESSED):
        os.makedirs(d, exist_ok=True)


def _classify(name):
    ext = os.path.splitext(name)[1].lower()
    if ext in _LOG_EXT:
        return "log"
    if ext in _TABLE_EXT:
        return "table"
    if ext in _TEXT_EXT:
        return "text"
    # 无扩展名或未知：按文本兜底
    return "text"


def _read_text(path):
    for enc in ("utf-8", "utf-8-sig", "gbk", "latin-1"):
        try:
            with open(path, encoding=enc) as f:
                return f.read()
        except (UnicodeDecodeError, LookupError):
            continue
    # 兜底：二进制读后强转
    with open(path, "rb") as f:
        return f.read().decode("utf-8", errors="replace")


def _kw_hits(text, kws):
    out = {}
    lower = text.lower()
    for k in kws:
        n = lower.count(k.lower())
        if n:
            out[k] = n
    return out


def _analyze_text(name, text):
    lines = text.splitlines()
    n_line = len(lines)
    n_char = len(text)
    n_para = sum(1 for l in lines if l.strip())  # 非空行
    nums = NUM_PAT.findall(text)
    prices = PRICE_PAT.findall(text)
    high = _kw_hits(text, KW_HIGH)
    fin = _kw_hits(text, KW_FIN)
    err = _kw_hits(text, KW_ERR)
    sector = _kw_hits(text, KW_SECTOR)
    stack = [m.group(0)[:400] for m in STACK_PAT.finditer(text)]
    times = PY_TIME_PAT.findall(text)
    return {
        "type": "text",
        "name": name,
        "lines": n_line,
        "non_empty_lines": n_para,
        "chars": n_char,
        "num_count": len(nums),
        "num_sample": nums[:15],
        "price_count": len(prices),
        "price_sample": prices[:5],
        "keyword_high": high,
        "keyword_finance": fin,
        "keyword_error": err,
        "keyword_sector": sector,
        "stack_trace": stack[:3],
        "first_15_lines": lines[:15],
        "last_5_lines": lines[-5:] if n_line > 5 else [],
        "time_stamps": times[:3] + (["…"] if len(times) > 3 else []),
    }


def _analyze_log(name, text):
    lines = text.splitlines()
    n = len(lines)
    err_n = sum(1 for l in lines if any(k in l for k in ("ERROR", "Error", "Exception", "Traceback", "异常", "错误")))
    warn_n = sum(1 for l in lines if any(k in l for k in ("WARN", "Warning", "警告")))
    info_n = sum(1 for l in lines if "INFO" in l)
    times = LOG_TIME_PAT.findall(text)
    # 错误行片段：截 ERROR/Exception 行前 1 行（堆栈头）
    err_lines = [(i, l) for i, l in enumerate(lines) if any(k in l for k in ("ERROR", "Error", "Exception", "Traceback"))]
    err_sample = err_lines[:5]
    first_err_block = ""
    if err_lines:
        idx = err_lines[0][0]
        block = lines[max(0, idx - 1):min(n, idx + 6)]
        first_err_block = "\n".join(block)
    return {
        "type": "log",
        "name": name,
        "lines": n,
        "error_count": err_n,
        "warn_count": warn_n,
        "info_count": info_n,
        "first_ts": times[0] if times else "",
        "last_ts": times[-1] if times else "",
        "first_err_block": first_err_block,
        "err_samples": [l for _, l in err_sample],
    }


def _analyze_table(name, text):
    sep = "\t" if name.lower().endswith(".tsv") else ","
    # 简单 CSV/TSV 解析（不处理引号转义，一般够用）
    rows = [r.split(sep) for r in text.splitlines() if r.strip()]
    if not rows:
        return {"type": "table", "name": name, "rows": 0}
    header = rows[0]
    body = rows[1:]
    cols = []
    for ci, h in enumerate(header):
        vals = [r[ci] if ci < len(r) else "" for r in body]
        nums = []
        for v in vals:
            try:
                nums.append(float(v.replace(",", "").replace("%", "").replace("¥", "").replace("￥", "").strip()))
            except (ValueError, AttributeError):
                pass
        if nums and len(nums) > max(1, len(vals) * 0.5):
            cols.append({"name": h, "kind": "numeric",
                         "n": len(nums), "min": min(nums), "max": max(nums),
                         "mean": round(sum(nums) / len(nums), 4)})
        else:
            uniq = set(v for v in vals if v)
            cols.append({"name": h, "kind": "text", "n_unique": len(uniq), "sample": list(uniq)[:5]})
    return {
        "type": "table",
        "name": name,
        "rows": len(body),
        "cols": len(header),
        "header": header,
        "first_3_rows": body[:3],
        "col_stats": cols,
    }


def _render_md(report, name):
    typ = report["type"]
    lines = ["# 📥 Inbox 分析报告", "", f"- **文件**：`{name}`", f"- **类型**：{typ}",
             f"- **处理时间**：`{report.get('processed_at','')}`", ""]
    if typ == "text":
        lines += [
            "## 基础统计", "", f"- 行数：{report['lines']}（非空 {report['non_empty_lines']}）",
            f"- 字符数：{report['chars']}", f"- 数字命中：{report['num_count']} 处",
            f"- 价格/金额：{report['price_count']} 处", ""]
        if report["num_sample"]:
            lines.append("**数字样本**：" + "、".join(report["num_sample"]) + "")
        if report["price_sample"]:
            lines.append("**价格样本**：" + "、".join(report["price_sample"]) + "")
        for tag, kws in [("重要事件（高/低/破位）", report["keyword_high"]),
                         ("财报相关", report["keyword_finance"]),
                         ("错误/异常", report["keyword_error"]),
                         ("板块关键词", report["keyword_sector"])]:
            if kws:
                lines.append(f"**{tag}**：" + "、".join(f"`{k}`×{n}" for k, n in kws.items()) + "")
        if report["stack_trace"]:
            lines += ["", "## 堆栈首条", "", "```", report["stack_trace"][0][:400], "```"]
        if report["first_15_lines"]:
            lines += ["", "## 前 15 行预览", "", "```"] + report["first_15_lines"] + ["```"]
        if report["time_stamps"]:
            lines += ["", f"**时间戳**：`{report['time_stamps']}`"]
    elif typ == "log":
        lines += [
            "## 日志统计", "", f"- 总行数：{report['lines']}",
            f"- ERROR/Exception 行：{report['error_count']}",
            f"- WARN 行：{report['warn_count']}",
            f"- INFO 行：{report['info_count']}",
            f"- 时间范围：`{report['first_ts']}` ~ `{report['last_ts']}`", ""]
        if report["first_err_block"]:
            lines += ["## 首条错误上下文", "", "```", report["first_err_block"], "```"]
        if report["err_samples"]:
            lines += ["", "## 错误行样本", "", "```"] + report["err_samples"] + ["```"]
    elif typ == "table":
        lines += ["## 表格统计", "", f"- 行数：{report['rows']}，列数：{report['cols']}",
                  f"- 列头：`{' | '.join(report['header'])}`", ""]
        if report["first_3_rows"]:
            lines += ["## 前 3 行", "", "```"] + [" | ".join(r) for r in report["first_3_rows"]] + ["```", ""]
        lines += ["## 列统计", ""]
        for c in report["col_stats"]:
            if c["kind"] == "numeric":
                lines.append(f"- `{c['name']}` (数字×{c['n']})：min={c['min']}, max={c['max']}, mean={c['mean']}")
            else:
                lines.append(f"- `{c['name']}` (文本，{c['n_unique']} 种)：`{', '.join(c['sample'])}`")
    return "\n".join(lines)


def _archive(src_name):
    """处理后归档：data/_processed/YYYY-MM-DD/<原名>。"""
    today = datetime.date.today().isoformat()
    dst_dir = os.path.join(PROCESSED, today)
    os.makedirs(dst_dir, exist_ok=True)
    ts = datetime.datetime.now().strftime("%H%M%S")
    base, ext = os.path.splitext(src_name)
    dst = os.path.join(dst_dir, f"{base}_{ts}{ext}")
    return dst


def _push_summary(name, report, cfg=None):
    """分析完成推送微信群（复用 push_channel，三通道全失败则落盘排队 —— 上次加固已覆盖）。"""
    try:
        import push_channel
        if cfg is None:
            cfg = push_channel.load_notify_cfg()
        typ = report["type"]
        head = "📥 inbox 已处理"
        if typ == "text":
            nums = "、".join(report["num_sample"][:3]) or "无数字"
            kw = []
            for tag in (report["keyword_high"], report["keyword_finance"], report["keyword_error"]):
                if tag:
                    kw += list(tag.keys())[:3]
            kw_str = "命中：" + "、".join(kw[:5]) if kw else "无关键命中"
            content = f"文件：`{name}`\n类型：文本 {report['lines']}行 / {report['chars']}字\n数字样本：{nums}\n{kw_str}\n详见 outbox/{name}.md"
        elif typ == "log":
            content = f"文件：`{name}`\n类型：日志 {report['lines']}行\nERROR×{report['error_count']} / WARN×{report['warn_count']}\n首错：`{(report['first_err_block'] or '').splitlines()[0][:80] if report['first_err_block'] else '无'}`\n详见 outbox/{name}.md"
        else:
            content = f"文件：`{name}`\n类型：表格 {report['rows']}行×{report['cols']}列\n列头：`{' | '.join(report['header'][:8])}`\n详见 outbox/{name}.md"
        push_channel.push(head, content, cfg, kind="notice")
    except Exception as e:  # noqa: BLE001
        print("inbox 推送失败:", type(e).__name__, e)


def process_one(name, cfg=None):
    """处理单个文件：分析 → 写 outbox → 归档 → 推送。返回 report。"""
    _ensure_dirs()
    src = os.path.join(INBOX, name)
    if not os.path.isfile(src):
        return None
    try:
        text = _read_text(src)
        typ = _classify(name)
        if typ == "log":
            r = _analyze_log(name, text)
        elif typ == "table":
            r = _analyze_table(name, text)
        else:
            r = _analyze_text(name, text)
        r["processed_at"] = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        # 写 outbox
        with open(os.path.join(OUTBOX, name + ".json"), "w", encoding="utf-8") as f:
            json.dump(r, f, ensure_ascii=False, indent=1)
        with open(os.path.join(OUTBOX, name + ".md"), "w", encoding="utf-8") as f:
            f.write(_render_md(r, name))
        # 归档（移动原文件）
        try:
            shutil.move(src, _archive(name))
        except Exception as e:  # noqa: BLE001
            print("归档失败:", type(e).__name__, e, "原文件保留在 inbox")
        # 推送
        _push_summary(name, r, cfg=cfg)
        return r
    except Exception:  # noqa: BLE001
        print("inbox 处理失败 [%s]:\n%s" % (name, traceback.format_exc()))
        return None


def scan_once(cfg=None, log=print):
    """扫一次 inbox，处理所有新文件，返回处理成功的数量。"""
    _ensure_dirs()
    n = 0
    try:
        names = sorted(os.listdir(INBOX))
    except FileNotFoundError:
        return 0
    for name in names:
        path = os.path.join(INBOX, name)
        if not os.path.isfile(path):
            continue
        if name.startswith(".") or name.endswith((".tmp", ".crdownload", ".part")):
            continue
        log("inbox 处理: %s" % name)
        if process_one(name, cfg=cfg):
            n += 1
    return n


if __name__ == "__main__":
    # CLI 自检 / 手动触发：python _inbox_analyze.py [filename ...]
    if len(sys.argv) > 1:
        cfg = None
        try:
            import push_channel
            cfg = push_channel.load_notify_cfg()
        except Exception:  # noqa: BLE001
            pass
        for n in sys.argv[1:]:
            r = process_one(n, cfg=cfg)
            if r:
                print("[OK]", n, "→ outbox/%s.{json,md}" % n)
            else:
                print("[FAIL]", n)
    else:
        n = scan_once()
        print("本次处理 %d 个文件" % n)