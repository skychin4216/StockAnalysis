# -*- coding: utf-8 -*-
"""宽基 ETF 份额（申赎）观测 —— 用「份额 + 持有人结构」跟踪国家队（汇金）的进出。

■ 为什么盯 ETF 份额，而不是十大流通股东
  十大流通股东靠季报披露，滞后 1~3 个月；且 **汇金 2015 年后主要买宽基 ETF**
  （个股层面的国家队披露实测基本停在 2015~2021，见 `_national_flow.py`）。
  ETF 份额 = 累计(申购 − 赎回)，是资金进出 ETF 的**直接计量**，也**可每日观测**。

■ 数据源（四条互补，均已实测可用）
  ① 天天基金 F10「规模变动」 gmbd
     → 期间申购(亿份) / 期间赎回(亿份) / 期末总份额(亿份) / 期末净资产(亿元)
     实测 510300 覆盖 2012→今（近年季度粒度，2012 那段为日粒度）
  ② 东财 push2delay `api/qt/stock/get` 的 f84 = **当日总份额(份)**，f116 = 总规模(元)
     （自检：f116 / f84 ≈ 单位净值，对不上会告警）
     用于**自建日频序列** —— 每交易日跑一次 `--snap`，追加进 data/_etf_share_daily.json
  ③ 天天基金 F10「持有人结构」 cyrjg（**半年/年度**粒度）
     → 机构/个人/内部持有**比例** + 期末总份额
     ⇒ 机构持有份额 = 总份额 × 机构比例，可把净赎回**归因到机构 vs 个人**
  ④ 基金**年度报告** §9.2「期末上市基金前十名持有人」（法定披露，**点名**）
     → 直接看到「中央汇金资产管理有限责任公司 / 中央汇金投资有限责任公司」持有份额与占比
     ★ 实测：中期报告**没有**该子项（只有 9.1 户数+结构），且 §11.1 声明
       「无单一投资者持有 ≥20%」，所以**只有年报能逐名确认汇金**。
     PDF 地址必须用 **http://**（https 会返回 JS 反爬挑战页，见 2026-09-13 实测）。
  ⑤ 交易所「ETF 基金份额」日频（★ **可回溯历史**，2026-09-13 新增）
     上交所 query.sse.com.cn `COMMON_SSE_ZQPZ_ETFZL_XXPL_ETFGM_SEARCH_L` 带 STAT_DATE
     → 8 只沪市宽基 **任意历史交易日**份额；`--backfill N` 一次补齐最近 N 个交易日。
     深交所 fund.szse.cn ShowReport(CATALOGID=1000_lf) **实测忽略一切日期参数**，
     只返回「当前规模(份)」→ 深市 159915/159919 **无历史**：回补记录只含沪市标的，
     跨日比较由 daily_drift 改按「共有标的」口径计算，避免口径不一致。

■ 四象限判定（份额 vs 价格，价格用「期末净资产 ÷ 期末总份额」= 单位净值）
  份额↑ & 价格↓  → 低位承接（国家队低吸）      ★ 最想抓的买点信号
  份额↑ & 价格↑  → 追涨申购（趋势确认）
  份额↓ & 价格↑  → 高位派发                    ★ 卖点信号
  份额↓ & 价格↓  → 赎回杀跌

■ 拐点（由升转降 / 由降转升）
  季度拐点：宽基合计净申赎的符号翻转（历史可回算，滞后一个季度）
  日频拐点：日频合计份额的 3 日符号（只积累到 1~2 天时退化为「相对最近季度末的漂移」）

用法：
  python _etf_share_flow.py              # 全量：快照 + 份额历史 + 持有人结构 + 年报前十名 + 拐点判定
  python _etf_share_flow.py --snap       # 只做当日快照 + 拐点判定（快，适合每日定时）
  python _etf_share_flow.py --holder     # 只刷持有人结构 + 年报前十名持有人（慢，年报级刷新）
  python _etf_share_flow.py --no-pdf     # 全量但跳过 PDF（无 pypdf 时自动跳过）
  python _etf_share_flow.py --sync-apk   # 把资产同步进 APK assets
  python _etf_share_flow.py --backfill 40  # ★ 回补最近 40 个交易日的沪市宽基份额（可回溯历史）
产物：
  data/_etf_share_hist.json    份额历史（季度）+ 四象限 + 持有人结构 + 前十名持有人 + 拐点判定
  data/_etf_share_daily.json   日频份额序列（--snap 累积当日 + --backfill 回补历史）
  data/_etf_share_signal.json  当日拐点信号（供守护读取后推送）
"""
import argparse
import json
import os
import re
import shutil
import sys
import time

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DATA_DIR = os.path.join(ROOT, "data")
APK_DATA = os.path.join(ROOT, "app", "src", "main", "assets", "data")
HIST_FILE = os.path.join(DATA_DIR, "_etf_share_hist.json")
DAILY_FILE = os.path.join(DATA_DIR, "_etf_share_daily.json")
SIGNAL_FILE = os.path.join(DATA_DIR, "_etf_share_signal.json")

UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
PX = {"http": None, "https": None}
GMB_API = "https://fundf10.eastmoney.com/FundArchivesDatas.aspx"
ANN_API = "https://api.fund.eastmoney.com/f10/JJGG"
PDF_HOST = "http://pdf.dfcfw.com/pdf/H2_%s_1.pdf"   # ★ 必须 http
PUSH_API = "https://push2delay.eastmoney.com/api/qt/stock/get"
SSE_API = "https://query.sse.com.cn/commonQuery.do"           # ★ 上交所（可按 STAT_DATE 回溯）
SSE_SQL = "COMMON_SSE_ZQPZ_ETFZL_XXPL_ETFGM_SEARCH_L"
DAILY_NOTE = "日频 ETF 份额序列（--snap 累积当日 + --backfill 回补沪市历史）"

# 宽基主力（★ 国家队主要承接标的：沪深300 / 上证50 / 中证500 / 中证1000 / 科创50 / 创业板）
WIDE = [
    ("510300", "沪深300ETF华泰柏瑞"), ("510310", "沪深300ETF易方达"),
    ("510330", "沪深300ETF华夏"), ("159919", "沪深300ETF嘉实"),
    ("510050", "上证50ETF华夏"), ("510500", "中证500ETF南方"),
    ("512500", "中证500ETF华夏"), ("512100", "中证1000ETF南方"),
    ("588000", "科创50ETF华夏"), ("159915", "创业板ETF易方达"),
]
HUIJIN_KW = ("汇金",)


def _get(url, headers=None, retries=3, params=None):
    """带重试（遵循 skills/NETWORK_RETRY.md：0.5s→1s→2s 退避）。"""
    h = dict(UA)
    h.update(headers or {})
    last = None
    for i in range(retries):
        try:
            return requests.get(url, params=params, headers=h, proxies=PX, timeout=(5, 20))
        except Exception as e:            # noqa: BLE001
            last = e
            time.sleep(0.5 * (2 ** i) + 0.1)
    raise RuntimeError("请求失败(已重试%d次): %s" % (retries, last))


def _f(v):
    """'1,234.5' / '---' → float / None"""
    if v is None:
        return None
    s = str(v).replace(",", "").replace("%", "").strip()
    if s in ("", "---", "--"):
        return None
    try:
        return float(s)
    except ValueError:
        return None


def _load(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def _save(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
        f.write("\n")


def _rows_from_archive(html):
    """天天基金 FundArchivesDatas 的 content 片段 → 每行的单元格文本列表。"""
    out = []
    for tr in re.findall(r"<tr[^>]*>(.*?)</tr>", html, re.S):
        cs = [re.sub(r"<[^>]+>", "", c).replace("&nbsp;", " ").strip()
              for c in re.findall(r"<t[dh][^>]*>(.*?)</t[dh]>", tr, re.S)]
        if cs:
            out.append(cs)
    return out


def _archive_html(text):
    """★ 响应形如 var xxx={ content:"<table ...>...</table>", ... }，实测**无 arryear 字段**，
    故取 content:" 到最后一个 </table> 的原始片段（旧写法要求 arryear 会静默返回空）。"""
    i, j = text.find('content:"'), text.rfind("</table>")
    return text[i + 9:j + 8].replace("\\'", "'") if (i >= 0 and j > i) else ""


# ══════════════════════════ ① gmbd 份额历史 ══════════════════════════
def fetch_gmbd(code):
    """天天基金规模变动 → [{date, sub, red, shares, net_asset, nav, nav_chg}] 升序。"""
    r = _get("%s?type=gmbd&mode=0&code=%s" % (GMB_API, code),
             {"Referer": "https://fundf10.eastmoney.com/gmbd_%s.html" % code})
    html = _archive_html(r.text)
    out = []
    for cs in _rows_from_archive(html):
        if len(cs) < 5 or not re.match(r"^\d{4}-\d{2}-\d{2}$", cs[0]):
            continue
        shares, na = _f(cs[3]), _f(cs[4])
        out.append({
            "date": cs[0], "sub": _f(cs[1]), "red": _f(cs[2]),
            "shares": shares, "net_asset": na,
            "nav": (na / shares) if (na and shares) else None,
            "nav_chg": _f(cs[5]) if len(cs) > 5 else None,
        })
    out.sort(key=lambda x: x["date"])
    return out


# ══════════════════════════ ② 当日快照 ══════════════════════════
def fetch_snap(code):
    """东财 push2delay 当日快照 → {shares(份), scale(元), nav, price}。"""
    mkt = "1" if code.startswith("5") else "0"
    d = _get("%s?secid=%s.%s&ut=fa5fd1943c7b386f172d6893dbfba10b"
             "&fields=f57,f58,f43,f84,f116" % (PUSH_API, mkt, code)).json().get("data") or {}
    shares = float(d.get("f84") or 0)
    scale = float(d.get("f116") or 0)
    price = (float(d.get("f43") or 0) / 100.0) or None
    return {"code": code, "name": d.get("f58"), "shares": shares, "scale": scale,
            "nav": (scale / shares) if shares else None, "price": price}


# ══════════════════════════ ⑤ 交易所日频份额（上交所，可回溯历史）══════════════════════════
def _recent_trade_days(days):
    """最近 days 个「工作日」（倒序收集后升序返回；节假日由接口空结果自然跳过）。"""
    import datetime as dt
    out, d = [], dt.date.today()
    while len(out) < days:
        if d.weekday() < 5:
            out.append(d.strftime("%Y-%m-%d"))
        d -= dt.timedelta(days=1)
    return sorted(out)


def last_trade_day():
    """最近一个工作日：给 --snap 打正确日期，避免周末把上周五的值记成周日。"""
    return _recent_trade_days(1)[0]


def fetch_sse_scale(date_str):
    """上交所「ETF 基金规模」按日份额 → {code: shares(份)}（STAT_DATE=YYYY-MM-DD）。"""
    r = _get(SSE_API, {"Referer": "https://www.sse.com.cn/"}, params={
        "isPagination": "true", "pageHelp.pageSize": "10000", "pageHelp.pageNo": "1",
        "pageHelp.beginPage": "1", "pageHelp.cacheSize": "1", "pageHelp.endPage": "1",
        "sqlId": SSE_SQL, "STAT_DATE": date_str})
    out = {}
    for it in ((r.json() or {}).get("result") or []):
        code = str(it.get("SEC_CODE") or "").strip()
        vol = _f(it.get("TOT_VOL"))                 # 单位：万份
        if code and vol:
            out[code] = vol * 10000.0
    return out


def do_backfill(days, funds):
    """回补沪市宽基 ETF 日频份额 → 并入 DAILY_FILE（只补缺失日期，不覆盖已有快照）。

    ★ 口径（2026-09-13 实测）：上交所可按 STAT_DATE 取任意历史交易日；
      深交所 ShowReport 忽略一切日期参数、只回「当前规模(份)」→ 深市 159915/159919
      无历史，故回补行只含 5 开头标的，`covered` 记录在场标的，
      跨日比较由 daily_drift 按「共有标的」计算，避免口径不一致。
    """
    sh = [(c, n) for c, n in funds if str(c).startswith("5")]
    if not sh:
        print("  无沪市标的，跳过回补")
        return 0
    daily = _load(DAILY_FILE, {"note": DAILY_NOTE, "snap": []})
    have = {x.get("date"): x for x in (daily.get("snap") or [])}
    got, added = 0, 0
    for d in _recent_trade_days(days):
        try:
            m = fetch_sse_scale(d)
        except Exception as e:                                   # noqa: BLE001
            print("  %s 抓取失败: %s" % (d, e))
            continue
        if not m:
            continue                                             # 非交易日 / 未披露
        fl = {c: {"shares": m[c], "scale": None, "nav": None, "src": "sse"}
              for c, _n in sh if c in m}
        if not fl:
            continue
        got += 1
        old_rec = have.get(d)
        if old_rec and (old_rec.get("src") or "") == "sse":
            continue                                     # 已是交易所口径，幂等跳过
        if old_rec:
            # ★ 交易所份额覆盖 f84（f84 实测滞后一日），保留深市标的与净值
            fs = dict(old_rec.get("funds") or {})
            for c, v in fl.items():
                fs[c] = v
            have[d] = {"date": d, "src": "sse", "covered": sorted(fs),
                       "total_shares": sum(x["shares"] for x in fs.values()),
                       "total_scale": old_rec.get("total_scale"), "funds": fs}
            print("  %s 用交易所份额覆盖 f84（%d 只沪市）" % (d, len(fl)))
        else:
            have[d] = {"date": d, "src": "sse", "covered": sorted(fl),
                       "total_shares": sum(v["shares"] for v in fl.values()),
                       "total_scale": None, "funds": fl}
            added += 1
    daily["note"] = DAILY_NOTE
    daily["snap"] = sorted(have.values(), key=lambda x: x["date"])
    _save(DAILY_FILE, daily)
    print("  ✓ 回补：%d 个交易日取到数据，新增 %d 行；日频序列共 %d 个交易日"
          % (got, added, len(daily["snap"])))
    return added


# ══════════════════════════ ③ cyrjg 持有人结构 ══════════════════════════
def fetch_cyrjg(code):
    """持有人结构（半年/年度）→ [{date, org_pct, per_pct, ins_pct, shares}] 升序。

    列顺序实测为：公告日期 | 机构持有比例 | 个人持有比例 | 内部持有比例 | 期末总份额(亿份)
    （用 510300 年报 §9.1 交叉校验：90.27 / 8.98 / 0.75 / 888.30 ✓）
    """
    r = _get("%s?type=cyrjg&mode=0&code=%s" % (GMB_API, code),
             {"Referer": "https://fundf10.eastmoney.com/cyrjg_%s.html" % code})
    out = []
    for cs in _rows_from_archive(_archive_html(r.text)):
        if len(cs) < 5 or not re.match(r"^\d{4}-\d{2}-\d{2}$", cs[0]):
            continue
        out.append({"date": cs[0], "org_pct": _f(cs[1]), "per_pct": _f(cs[2]),
                    "ins_pct": _f(cs[3]), "shares": _f(cs[4])})
    out.sort(key=lambda x: x["date"])
    return out


# ── §9.2 前十名持有人行解析：名称 + 持有份额(份, 千分位) + 占比(%) ──
#  ★ 两档正则：strict 贴近 510300/510050 常见排版；loose 放宽名称字符集与数字位数，
#    用于名称含「·/（）/－/空格」或份额列位数不同的年报。两档都按 rank 1..10 去重。
_TOP10_STRICT = re.compile(
    r"(\d{1,2})\s*([\u4e00-\u9fa5A-Za-z][\u4e00-\u9fa5A-Za-z0-9\-－（）()]{2,30}?)"
    r"\s*([\d,]{7,}\.\d{2})\s*(\d{1,3}\.\d{2})")
_TOP10_LOOSE = re.compile(
    r"(\d{1,2})\s*([\u4e00-\u9fa5A-Za-z][^\d%]{2,60}?)\s*"
    r"([\d,]{4,}(?:\.\d+)?)\s*(\d{1,3}\.\d{1,3})")


def _scan_top10(seg, pat):
    """按 rank 1..10 去重取行（rank 是天然的定位锚，比按空白切分稳）。"""
    rows, seen = [], set()
    for m in pat.finditer(seg):
        rank = int(m.group(1))
        if not 1 <= rank <= 10 or rank in seen:
            continue
        name = m.group(2).strip(" 、·　")
        fen, pct = _f(m.group(3)), _f(m.group(4))
        if len(name) < 2 or fen is None or pct is None or not (0 < pct <= 100):
            continue
        seen.add(rank)
        rows.append({"rank": rank, "name": name, "fen": fen, "pct": pct})
    rows.sort(key=lambda x: x["rank"])
    return rows


def parse_top10(seg):
    """§9.2 正文片段 → (items, parser)。strict 优先，明显不全才降级 loose。

    ★ 取档规则（保守优先，避免 loose 把正文里别的「名次+数字」误当持有人行）：
      · strict 认出 ≥3 行 → 认为它已正确命中表格，直接用 strict（与旧行为完全一致）；
      · strict 认出 0~2 行 → 才算失配，改用 loose（认出更多行才采纳）。
    """
    strict = _scan_top10(seg, _TOP10_STRICT)
    if len(strict) >= 3:
        return strict, "strict"
    loose = _scan_top10(seg, _TOP10_LOOSE)
    if len(loose) > len(strict):
        return loose, "loose"
    if strict:
        return strict, "strict"
    return [], "none"


def _huijin_of(rows, seg):
    """汇金占比：优先按解析出的行名匹配；名称被截断/行解析失败时退化为关键词兜底。"""
    kw = "|".join(HUIJIN_KW)
    hj = [x for x in rows if any(k in x["name"] for k in HUIJIN_KW)]
    if hj:
        return (round(sum(x["pct"] for x in hj), 2),
                sum(x["fen"] for x in hj) or None,
                [x["name"] for x in hj], "row")
    if re.search(kw, seg):
        m = re.search(r"(?:%s)[^\d]{0,40}?([\d,]{4,}(?:\.\d+)?)[^\d]{0,24}?(\d{1,3}\.\d{1,3})" % kw, seg)
        if m:                                       # 关键词附近直接抓到「份额 … 占比」
            return _f(m.group(2)), _f(m.group(1)), [], "kw"
        return None, None, [], "kw_only"
    return None, None, [], "none"


def fetch_holder_list(code):
    """年报 §9.2「期末上市基金前十名持有人」→ {as_of, items:[{rank,name,fen,pct}], huijin_pct, huijin_fen}。

    ★ 只存在于**年度报告**；中期报告无此子项。PDF 必须走 http://（https 为反爬 JS 挑战页）。

    ★ 2026-09-13 补「多档回退」（探针 `_probe_fund_holders.py` 转正 `_fund_holder_top10.py` 的教训）：
      探针当年是**严格正则失配后 dump 全文**才碰巧读到汇金，说明各基金年报的排版
      （名称长度 / 份额单位 / 空格 / 换行 / 全角括号）并不统一 —— 正式工具只有一档正则
      会**静默返回 0 行**。故此处三级回退：strict → loose → 关键词兜底，
      并把「用了哪一档」回传（`parser` / `huijin_src`），失配时带 `seg_head` 供人工定位。
    """
    try:
        from pypdf import PdfReader
    except ImportError:
        return {"error": "pypdf 未安装（pip install pypdf）"}
    import io
    r = _get("%s?fundcode=%s&pageIndex=1&pageSize=60&type=0" % (ANN_API, code),
             {"Referer": "https://fundf10.eastmoney.com/jjgg_%s.html" % code})
    items = (r.json() or {}).get("Data") or []
    cand = [it for it in items
            if "年度报告" in (it.get("TITLE") or "")
            and not re.search(r"提示性公告|摘要|更正", it.get("TITLE") or "")]
    if not cand:
        return {"error": "未找到年度报告公告"}
    ann = cand[0]
    pdf = _get(PDF_HOST % ann.get("ID"), retries=2)
    if not pdf.content.startswith(b"%PDF"):
        return {"error": "PDF 被反爬拦截（请确认使用 http://）"}
    rd = PdfReader(io.BytesIO(pdf.content))
    for i, pg in enumerate(rd.pages):
        try:
            t = pg.extract_text() or ""
        except Exception:            # noqa: BLE001
            continue
        # 命中条件放宽（原只认「持有份额」，漏掉用「占比/汇金」表述的排版）
        if not ("前十名持有人" in t and ("持有份额" in t or "占比" in t or "汇金" in t)):
            continue
        flat = re.sub(r"[ \t]+", " ", t.replace("\r", "")).replace("\n", "")
        seg = flat[flat.find("前十名持有人"):][:8000]
        got, parser = parse_top10(seg)
        if not got:                                  # 去换行后不行 → 保留换行再试一档
            seg_nl = t.replace("\r", "").strip()
            seg_nl = seg_nl[seg_nl.find("前十名持有人"):][:8000]
            got, parser = parse_top10(seg_nl)
        hpct, hfen, hnames, how = _huijin_of(got, seg)
        res = {"as_of": ann.get("TITLE"), "page": i + 1, "parser": parser,
               "items": got, "huijin_src": how,
               "huijin_pct": hpct, "huijin_fen": hfen, "huijin_names": hnames}
        if not got:
            res["seg_head"] = seg[:400]              # 失配留档，供 _fund_holder_top10.py --raw 定位
        return res
    return {"error": "年报中未定位到 §9.2 正文"}


# ══════════════════════════ 四象限 / 归因 / 拐点 ══════════════════════════
def quadrant(d_shares, d_nav):
    """份额变化 % × 净值变化 % → 四象限标签。"""
    if d_shares is None or d_nav is None:
        return "数据不全"
    up_s, up_p = d_shares > 0, d_nav > 0
    if up_s and not up_p:
        return "低位承接★"
    if up_s and up_p:
        return "追涨申购"
    if not up_s and up_p:
        return "高位派发★"
    return "赎回杀跌"


def analyze(code, name, rows):
    """把份额序列转成「区间变化 + 四象限」，并挑出净申购最大的几段。"""
    seq = [r for r in rows if r["shares"] and r["nav"]]
    periods = []
    for a, b in zip(seq, seq[1:]):
        ds = 100.0 * (b["shares"] / a["shares"] - 1)
        dn = 100.0 * (b["nav"] / a["nav"] - 1)
        net = (b["sub"] or 0) - (b["red"] or 0)
        periods.append({"end": b["date"], "d_shares_pct": ds, "d_nav_pct": dn,
                        "net_sub": net, "shares": b["shares"],
                        "quadrant": quadrant(ds, dn)})
    return {"code": code, "name": name, "n": len(rows),
            "first": rows[0]["date"] if rows else None,
            "last": rows[-1]["date"] if rows else None,
            "last_shares": rows[-1]["shares"] if rows else None,
            "periods": periods}


def attribute_institution(cyrjg, periods):
    """机构/个人持有份额 + 最近一期净赎回归因（机构 vs 个人）。

    机构持有份额 = 期末总份额 × 机构比例；相邻两个披露点作差即机构净申赎（份额，亿份）。
    ★ 与 gmbd 的净申购(=申购−赎回)口径略有差异：这里是**持有份额的变化**，
      两者同向即为强证据。
    """
    pts = [c for c in cyrjg if c.get("shares") and c.get("org_pct") is not None]
    if len(pts) < 2:
        return None
    seq = []
    for c in pts:
        org = c["shares"] * c["org_pct"] / 100.0
        per = c["shares"] * c["per_pct"] / 100.0 if c.get("per_pct") is not None else None
        seq.append({"date": c["date"], "shares": c["shares"], "org_pct": c["org_pct"],
                    "per_pct": c.get("per_pct"), "org_fen": org, "per_fen": per})
    a, b = seq[-2], seq[-1]
    d_org, d_per = b["org_fen"] - a["org_fen"], (b["per_fen"] - a["per_fen"]) if None not in (
        b["per_fen"], a["per_fen"]) else None
    tot = d_org + (d_per or 0)
    return {"seq": seq, "prev": a, "now": b,
            "d_org_fen": round(d_org, 2), "d_per_fen": (round(d_per, 2) if d_per is not None else None),
            "org_share_of_flow": (round(abs(d_org) / abs(tot), 4) if tot else None),
            "d_org_pct_pts": round(b["org_pct"] - a["org_pct"], 2)}


def detect_turn(agg):
    """宽基合计净申赎的**季度拐点**（符号翻转）→ 最近一次翻转 + 当前方向。"""
    ks = sorted(agg)
    sig = [(k, (agg[k].get("net_sub") or 0)) for k in ks if agg[k].get("n")]
    if not sig:
        return None, None
    turns = []
    for (_k0, v0), (k1, v1) in zip(sig, sig[1:]):
        if (v0 > 0) != (v1 > 0):
            turns.append({"at": k1, "dir": "购→赎" if v1 < 0 else "赎→购",
                          "net_sub": round(v1, 2)})
    last = turns[-1] if turns else None
    cur_dir = "净申购" if sig[-1][1] > 0 else "净赎回"
    return last, {"current": cur_dir, "at": sig[-1][0], "net_sub": round(sig[-1][1], 2),
                  "turns": turns[-4:]}


def _common_total(a, b):
    """两次快照「共有标的」的合计份额（回补行只含沪市，须按交集比较才不失真）。"""
    fa, fb = a.get("funds") or {}, b.get("funds") or {}
    ks = [k for k in fa if k in fb]
    if not ks:
        return None, None
    return (sum(fa[k]["shares"] for k in ks), sum(fb[k]["shares"] for k in ks))


def daily_drift(daily):
    """日频合计份额：优先用最近 3 个快照的方向；不足时退化为「相对最近季度末的漂移」。"""
    sn = daily.get("snap") or []
    if len(sn) >= 3:
        a0, b0 = sn[-3], sn[-1]
        a, b = _common_total(a0, b0)
        if a is None:                                   # 无交集 → 退回合计口径
            a, b = a0.get("total_shares"), b0.get("total_shares")
        return {"mode": "日频3日", "from": a0["date"], "to": b0["date"],
                "d_pct": round(100.0 * (b / a - 1), 3) if a else None, "n": len(sn)}
    if sn:
        return {"mode": "首日（无前值）", "to": sn[-1]["date"], "n": len(sn), "d_pct": None}
    return {"mode": "无快照", "n": 0, "d_pct": None}


def daily_lead(daily):
    """最近两个快照「共有标的」逐只份额差 → 领先净申购 / 净赎回的标的（★ 定位到具体基金）。

    回补行只含沪市标的，故必须按交集逐只比较；仅取 5 开头与深市共有的部分。
    """
    sn = daily.get("snap") or []
    if len(sn) < 2:
        return None
    a, b = sn[-2], sn[-1]
    fa, fb = a.get("funds") or {}, b.get("funds") or {}
    ks = [k for k in fa if k in fb]
    if not ks:
        return None
    name = {c: n for c, n in WIDE}
    rows = []
    for k in ks:
        s0 = (fa[k] or {}).get("shares") or 0
        s1 = (fb[k] or {}).get("shares") or 0
        if s0 > 0:
            rows.append({"code": k, "name": name.get(k, k), "d_fen": s1 - s0,
                         "d_pct": round(100.0 * (s1 / s0 - 1), 3)})
    if not rows:
        return None
    rows.sort(key=lambda x: x["d_fen"])
    return {"from": a["date"], "to": b["date"], "n": len(rows),
            "outflow": rows[:3], "inflow": rows[-3:][::-1]}


def judge(width, agg, daily, holders):
    """把份额方向 + 归因 + 前十名持有人 + 日频近端方向合成「买/卖/中性」判断（供推送用）。"""
    last_turn, cur = detect_turn(agg)
    drift = daily_drift(daily)
    med = None
    if cur:
        v = agg.get(cur["at"]) or {}
        s = v.get("sample") or []
        med = round(sorted(s)[len(s) // 2], 1) if s else None
    # 机构归因（取有归因且份额最大的那只做代表）
    attr, rep = None, None
    for code in sorted(holders, key=lambda c: -(holders[c].get("attr") or {}).get("prev", {}).get("shares", 0)
                       if (holders[c].get("attr") or {}).get("prev") else 0):
        if holders[code].get("attr"):
            attr, rep = holders[code]["attr"], code
            break
    hj = None
    for code, h in holders.items():
        hl = h.get("holder_list") or {}
        if hl.get("huijin_pct"):
            hj = (code, hl)
            break

    if cur and cur["current"] == "净赎回":
        verdict, level = "卖出/派发", "高" if (med or 0) < -20 else "中"
    elif cur and cur["current"] == "净申购":
        verdict, level = "买入/承接", "高"
    else:
        verdict, level = "中性", "低"

    lines = []
    if cur:
        lines.append("宽基合计 %s（%s）净 %+.2f 亿份%s"
                     % (cur["current"], cur["at"], cur["net_sub"],
                        "，份额变化中位 %+.1f%%" % med if med is not None else ""))
    if last_turn:
        lines.append("最近拐点：%s @ %s → 当前%s"
                     % (last_turn["dir"], last_turn["at"], cur["current"] if cur else "?"))
    if attr and rep:
        lines.append("机构归因（%s）：机构持有 %.2f→%.2f 亿份（%+.2f），占净流动 %.0f%%，机构占比 %+.2fpp"
                     % (rep, attr["prev"]["org_fen"], attr["now"]["org_fen"], attr["d_org_fen"],
                        100.0 * (attr["org_share_of_flow"] or 0), attr["d_org_pct_pts"]))
    if hj:
        lines.append("前十名持有人（%s，年报）：汇金双主体合计 %.2f%%（%s）"
                     % (hj[0], hj[1]["huijin_pct"], "、".join(hj[1].get("huijin_names") or [])))
    dp = drift.get("d_pct")
    lines.append("日频：%s（%d 个快照）%s"
                 % (drift["mode"], drift["n"], "，近端 %+.2f%%" % dp if dp is not None else ""))
    lead = daily_lead(daily)
    if lead:
        t = lead["inflow"][0]
        lines.append("日频净申购领先：%s %s %+.2f 亿份（%+.2f%%）@ %s"
                     % (t["code"], t["name"], t["d_fen"] / 1e8, t["d_pct"], lead["to"]))
        o = lead["outflow"][0]
        if o["d_fen"] < 0:
            lines.append("日频净赎回领先：%s %s %+.2f 亿份（%+.2f%%）"
                         % (o["code"], o["name"], o["d_fen"] / 1e8, o["d_pct"]))
    recent = None
    if dp is not None and lead:
        t = lead["inflow"][0]
        recent = {"from": lead["from"], "to": lead["to"], "d_pct": dp,
                  "lead": t, "board": "买入/承接" if dp > 0 else "卖出/派发"}
        if dp > 0.3 and verdict == "卖出/派发":
            lines.append("★ 日频与季度方向背离：近端已转为净申购（%+.2f%%，领先 %s），"
                         "可能是新一轮承接，季度派发结论需打折看待" % (dp, t["code"]))
            if level == "高":
                level = "中"
        elif dp < -0.3 and verdict == "买入/承接":
            lines.append("★ 日频与季度方向背离：近端转为净赎回（%+.2f%%，领先 %s），"
                         "承接力度减弱" % (dp, o["code"] if o["d_fen"] < 0 else t["code"]))
    return {"verdict": verdict, "level": level, "quarterly": cur, "last_turn": last_turn,
            "daily": drift, "daily_lead": lead, "recent_daily": recent, "attr": attr, "attr_code": rep,
            "huijin": ({"code": hj[0], "pct": hj[1]["huijin_pct"],
                        "names": hj[1].get("huijin_names")} if hj else None),
            "lines": lines}


# ══════════════════════════ 主流程 ══════════════════════════
def do_snap(funds):
    """当日快照 + 追加日频序列。返回 {code: snap}。"""
    snaps = {}
    for code, name in funds:
        try:
            s = fetch_snap(code)
            snaps[code] = s
            print("  %-7s %-16s 份额 %10.4f 亿份  规模 %9.2f 亿元  净值 %s  现价 %s"
                  % (code, (s["name"] or name)[:16], s["shares"] / 1e8,
                     s["scale"] / 1e8, ("%.4f" % s["nav"]) if s["nav"] else "?", s["price"]))
        except Exception as e:                                   # noqa: BLE001
            print("  %-7s %-16s 抓取失败: %s" % (code, name, e))
    if snaps:
        daily = _load(DAILY_FILE, {"note": DAILY_NOTE, "snap": []})
        # 清理历史误标行：旧版用 time.strftime 打标，周末跑会把周五值记成周日
        import datetime as _dt
        daily["snap"] = [x for x in (daily.get("snap") or [])
                         if not ((x.get("src") or "f84") == "f84" and x.get("date")
                                 and _dt.datetime.strptime(x["date"], "%Y-%m-%d").weekday() >= 5)]
        today = last_trade_day()      # ★ 用最近交易日打标（周末跑不会把周五的值记成周日）
        rec = {"date": today, "src": "f84",
               "total_shares": sum(v["shares"] for v in snaps.values()),
               "total_scale": sum(v["scale"] for v in snaps.values()),
               "funds": {c: {"shares": v["shares"], "scale": v["scale"], "nav": v["nav"],
                             "src": "f84"} for c, v in snaps.items()}}
        prev = [x for x in daily["snap"] if x.get("date") == today]
        if prev and (prev[0].get("src") or "") == "sse":
            # ★ 交易所口径优先：份额保留 sse（f84 实测滞后一日），只补深市标的与净值
            merged = dict(prev[0])
            fs = merged.setdefault("funds", {})
            for c, v in rec["funds"].items():
                if c not in fs:
                    fs[c] = v
                elif v.get("nav"):
                    fs[c]["nav"], fs[c]["scale"] = v["nav"], v["scale"]
            merged["covered"] = sorted(fs)
            merged["total_shares"] = sum(x["shares"] for x in fs.values())
            merged["total_scale"] = rec["total_scale"]
            daily["snap"] = [merged if x.get("date") == today else x for x in daily["snap"]]
            print("\n  · %s 已有交易所口径：份额保留 SSE，仅补深市标的/净值（%d 只）"
                  % (today, len(fs)))
        else:
            daily["snap"] = [x for x in daily["snap"] if x.get("date") != today] + [rec]
            daily["snap"].sort(key=lambda x: x["date"])
            print("\n  ✓ 已追加 %s（%d 只, %.2f 亿份）" % (today, len(snaps),
                                                          rec["total_shares"] / 1e8))
        daily["note"] = DAILY_NOTE
        _save(DAILY_FILE, daily)
        print("    日频序列共 %d 个交易日" % len(daily["snap"]))
    return snaps


def do_holder(funds, old):
    """刷新持有人结构 + 年报前十名持有人（并入 holders）。"""
    holders = dict((old or {}).get("holders") or {})
    for code, name in funds:
        h = holders.setdefault(code, {"name": name})
        try:
            cj = fetch_cyrjg(code)
            h["cyrjg"] = cj
            if cj:
                print("  %-7s 持有人结构 %d 期，最新 %s：机构 %.2f%% 个人 %.2f%% 总份额 %.2f 亿份"
                      % (code, len(cj), cj[-1]["date"], cj[-1]["org_pct"] or 0,
                         cj[-1]["per_pct"] or 0, cj[-1]["shares"] or 0))
        except Exception as e:                                   # noqa: BLE001
            print("  %-7s 持有人结构失败: %s" % (code, e))
        try:
            hl = fetch_holder_list(code)
            h["holder_list"] = hl
            if hl.get("huijin_pct"):
                print("  %-7s 年报前十名：汇金合计 %.2f%%（%s）"
                      % (code, hl["huijin_pct"], "、".join(hl.get("huijin_names") or [])))
            elif hl.get("error"):
                print("  %-7s 年报前十名跳过：%s" % (code, hl["error"]))
        except Exception as e:                                   # noqa: BLE001
            print("  %-7s 年报前十名失败: %s" % (code, e))
    return holders


def line_period(p):
    return ("   %s  净%+8.2f 亿份  份额%+7.1f%%  净值%+6.1f%%  %s"
            % (p["end"], p["net_sub"], p["d_shares_pct"], p["d_nav_pct"], p["quadrant"]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--codes", default="", help="逗号分隔，覆盖默认宽基清单")
    ap.add_argument("--snap", action="store_true", help="只做当日快照 + 拐点判定")
    ap.add_argument("--holder", action="store_true", help="只刷持有人结构 + 年报前十名持有人")
    ap.add_argument("--no-pdf", action="store_true", help="跳过年报 PDF（无 pypdf 时自动跳过）")
    ap.add_argument("--backfill", type=int, default=0, metavar="N",
                    help="★ 回补最近 N 个交易日的沪市宽基份额（上交所可按日回溯）")
    ap.add_argument("--sync-apk", action="store_true", help="把资产同步进 APK assets")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    funds = ([(c.strip(), c.strip()) for c in args.codes.split(",") if c.strip()]
             if args.codes else WIDE)
    old = _load(HIST_FILE, {})

    # ── 只刷持有人 ──────────────────────────────────────
    if args.holder:
        print("═" * 100)
        print("持有人结构（cyrjg）+ 年报 §9.2 前十名持有人")
        print("═" * 100)
        holders = do_holder(funds, old)
        old.update({"holders": holders, "holder_updated": time.strftime("%Y-%m-%d %H:%M")})
        _save(HIST_FILE, old)
        print("\n✓ %s" % HIST_FILE)
        if args.sync_apk:
            sync_apk()
        return

    # ── 当日快照 ────────────────────────────────────────
    print("═" * 100)
    print("① 当日份额快照（东财 push2delay f84）—— 建议每交易日收盘后跑 --snap 累积日频序列")
    print("═" * 100)
    do_snap(funds)
    daily = _load(DAILY_FILE, {"snap": []})

    # ── 历史回补（沪市可回溯）────────────────────────────
    if args.backfill:
        print()
        print("═" * 100)
        print("⑥ 交易所日频份额回补（上交所 STAT_DATE，最近 %d 个交易日）" % args.backfill)
        print("═" * 100)
        do_backfill(args.backfill, funds)
        daily = _load(DAILY_FILE, {"snap": []})
        if not args.snap:
            if args.sync_apk:
                sync_apk()
            return

    if args.snap:
        sig = build_signal(old, daily)
        _save(SIGNAL_FILE, sig)
        print("\n── 拐点判定 ──")
        for ln in sig["judge"]["lines"]:
            print("  " + ln)
        print("  ⇒ 判断：%s（置信 %s）%s" % (sig["judge"]["verdict"], sig["judge"]["level"],
                                          "  → 需推送" if sig.get("push") else ""))
        print("✓ %s" % SIGNAL_FILE)
        if args.sync_apk:
            sync_apk()
        return

    # ── 份额历史 ────────────────────────────────────────
    print()
    print("═" * 100)
    print("② 份额历史（天天基金 gmbd）—— 期间申购/赎回 + 份额×净值 四象限")
    print("═" * 100)
    hist, agg_period = {}, {}
    for code, name in funds:
        try:
            rows = fetch_gmbd(code)
        except Exception as e:                                   # noqa: BLE001
            print("  %-7s 抓取失败: %s" % (code, e))
            continue
        if not rows:
            print("  %-7s 无数据" % code)
            continue
        a = analyze(code, name, rows)
        hist[code] = a
        for p in a["periods"]:
            k = p["end"]
            e = agg_period.setdefault(k, {"net_sub": 0.0, "n": 0, "sample": []})
            e["net_sub"] += p["net_sub"]
            e["n"] += 1
            e["sample"].append(p["d_shares_pct"])
        last = a["periods"][-1] if a["periods"] else None
        print("\n  ── %s %s（%s ~ %s, %d 行）" % (code, name, a["first"], a["last"], a["n"]))
        if last:
            print("     最新一期 %s：份额 %.2f 亿份（%+.1f%%）净值 %+.1f%% → %s"
                  % (last["end"], last["shares"], last["d_shares_pct"], last["d_nav_pct"],
                     last["quadrant"]))
        print("     史上净申购最大的 3 期：")
        for p in sorted(a["periods"], key=lambda x: -(x["net_sub"] or 0))[:3]:
            print(line_period(p))
        print("     史上净赎回最大的 2 期：")
        for p in sorted(a["periods"], key=lambda x: (x["net_sub"] or 0))[:2]:
            print(line_period(p))

    # ── 宽基合计 ────────────────────────────────────────
    if agg_period:
        print()
        print("═" * 100)
        print("③ 宽基 ETF 合计净申赎（多只加总，抵消「ETF 之间搬家」的干扰）")
        print("═" * 100)
        print("  %-12s %4s %16s %14s" % ("期末", "只数", "合计净申购(亿份)", "份额变化中位%"))
        for k in sorted(agg_period)[-14:]:
            v = agg_period[k]
            med = sorted(v["sample"])[len(v["sample"]) // 2] if v["sample"] else 0
            print("  %-12s %4d %16.2f %14.1f" % (k, v["n"], v["net_sub"], med))

    # ── 持有人 ──────────────────────────────────────────
    holders = dict(old.get("holders") or {})
    if not args.no_pdf:
        print()
        print("═" * 100)
        print("④ 持有人结构（cyrjg）+ 年报 §9.2 前十名持有人（点名确认汇金）")
        print("═" * 100)
        holders = do_holder(funds, old)
    for code in holders:
        cj = holders[code].get("cyrjg") or []
        if len(cj) >= 2:
            holders[code]["attr"] = attribute_institution(cj, hist.get(code, {}).get("periods") or [])
    for code in sorted(holders):
        at = holders[code].get("attr")
        if at:
            print("  %-7s 机构持有 %s %.2f → %s %.2f 亿份（%+.2f，占净流动 %.0f%%）；机构占比 %+.2fpp"
                  % (code, at["prev"]["date"], at["prev"]["org_fen"], at["now"]["date"],
                     at["now"]["org_fen"], at["d_org_fen"],
                     100.0 * (at["org_share_of_flow"] or 0), at["d_org_pct_pts"]))

    asset = {"updated": time.strftime("%Y-%m-%d %H:%M"), "width": hist,
             "agg": agg_period, "holders": holders}
    _save(HIST_FILE, asset)
    sig = build_signal(asset, daily)
    _save(SIGNAL_FILE, sig)
    print()
    print("═" * 100)
    print("⑤ 拐点判定（由升转降 / 由降转升）")
    print("═" * 100)
    for ln in sig["judge"]["lines"]:
        print("  " + ln)
    print("  ⇒ 判断：%s（置信 %s）" % (sig["judge"]["verdict"], sig["judge"]["level"]))
    print("\n✓ %s\n✓ %s" % (HIST_FILE, SIGNAL_FILE))

    if args.sync_apk:
        sync_apk()


def sync_apk():
    """把份额资产同步进 APK assets。"""
    os.makedirs(APK_DATA, exist_ok=True)
    for f in (HIST_FILE, DAILY_FILE, SIGNAL_FILE):
        if os.path.exists(f):
            shutil.copy2(f, os.path.join(APK_DATA, os.path.basename(f)))
    print("✓ 已同步到 APK assets: %s" % APK_DATA)


def build_signal(asset, daily):
    """合成当日信号：拐点 + 判断 + 是否值得推送。"""
    agg = asset.get("agg") or {}
    holders = asset.get("holders") or {}
    judge_obj = judge(asset.get("width") or {}, agg, daily, holders)
    # 推送条件：① 出现新的季度拐点（最近 1 期内）；② 判断为卖出/派发；③ 机构归因方向与份额同向
    push, why = False, []
    turns = (judge_obj.get("quarterly") or {})
    lt = judge_obj.get("last_turn")
    ks = sorted(agg)
    if lt and ks and lt["at"] == ks[-1]:
        push = True
        why.append("本季出现拐点 %s" % lt["dir"])
    if judge_obj["verdict"].startswith("卖出") and judge_obj["level"] == "高":
        push = True
        why.append("高位派发且份额大幅下降")
    at = judge_obj.get("attr")
    if at and at.get("org_share_of_flow") and at["org_share_of_flow"] > 0.6:
        push = True
        why.append("机构占净流动 %.0f%%（机构主导）" % (100.0 * at["org_share_of_flow"]))
    return {"as_of": time.strftime("%Y-%m-%d %H:%M"), "ts": time.time(),
            "judge": judge_obj, "push": push, "why": why}


if __name__ == "__main__":
    sys.exit(main())
