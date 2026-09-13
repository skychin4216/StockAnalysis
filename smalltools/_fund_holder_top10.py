# -*- coding: utf-8 -*-
"""基金年度报告 §9.2「期末上市基金前十名持有人」抽取诊断（2026-09-13 由 `_probe_fund_holders.py` 转正）。

■ 用途
  ① **逐名复核**某只 ETF/基金的持有人名册 —— 直接看到「中央汇金资产管理有限责任公司 /
     中央汇金投资有限责任公司」等国家队主体是否在列、持有多少份、占多少比例；
  ② §9.2 抽取**失配时的显微镜**：`--raw` 逐页 dump 原文，人工确认排版后再回去给
     `_etf_share_flow.parse_top10` 加正则档位。

■ 为什么值得单独留一个工具（转正理由）
  正式链路 `_etf_share_flow.fetch_holder_list()` 用「strict → loose → 关键词兜底」三级回退抽取；
  但各家基金年报排版差异很大（名称长度 / 份额位数 / 全角括号 / 换行），一旦三档全失配，
  只看解析结果无法判断「是没这页，还是正则不够宽」—— 必须**看到原文全文**。
  本工具即那把显微镜：它最初是探针，靠「严格正则失配后 dump 全文」才碰巧读到汇金
  （实测确认 510300 汇金双主体合计 82.76%）；转正后与正式解析链路共用同一份代码，不再分叉。

■ 口径说明
  · §9.2 只存在于**年度报告**；**中期报告没有该子项**（§11.1 仅声明「无单一投资者持有 ≥20%」，
    不点名，故**只有年报能逐名确认汇金**）；
  · PDF 地址必须用 **http://**（https 会返回 JS 反爬挑战页，2026-09-13 实测）。

■ 用法
  python _fund_holder_top10.py                    # 默认 510300：走正式解析链路
  python _fund_holder_top10.py 510050 159919      # 指定多只基金
  python _fund_holder_top10.py --raw 510300       # 逐页 dump 原文（解析失配时定位）
  python _fund_holder_top10.py --json 510300      # 输出解析结果 JSON
"""
import argparse
import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import _etf_share_flow as esf  # noqa: E402  复用正式解析链路（单一事实源，勿另写解析）


def dump_raw(code):
    """逐页 dump：定位含「前十名持有人」的页并打印全文（原探针能力）。"""
    try:
        from pypdf import PdfReader
    except ImportError:
        print("✗ pypdf 未安装（pip install pypdf）")
        return 1
    r = esf._get("%s?fundcode=%s&pageIndex=1&pageSize=60&type=0" % (esf.ANN_API, code),
                 {"Referer": "https://fundf10.eastmoney.com/jjgg_%s.html" % code})
    cand = [it for it in ((r.json() or {}).get("Data") or [])
            if "年度报告" in (it.get("TITLE") or "")]
    if not cand:
        print("  %s ✗ 未找到年度报告公告" % code)
        return 1
    ann = cand[0]
    print("  %s 年报: %s" % (code, ann.get("TITLE")))
    pdf = esf._get(esf.PDF_HOST % ann.get("ID"), retries=2)
    if not pdf.content.startswith(b"%PDF"):
        print("  ✗ PDF 被反爬拦截（请确认使用 http://）")
        return 1
    rd = PdfReader(io.BytesIO(pdf.content))
    print("  页数 %d" % len(rd.pages))
    hit = 0
    for i, pg in enumerate(rd.pages):
        try:
            t = pg.extract_text() or ""
        except Exception:                                        # noqa: BLE001
            continue
        if "前十名持有人" in t:
            hit += 1
            print("\n  ════════ 第 %d 页 ════════" % (i + 1))
            print(t)
            print("\n  【汇金出现】%s" % ("是" if "汇金" in t else "否"))
    if not hit:
        print("  ✗ 未定位到 §9.2 正文页")
        return 1
    return 0


def check(code, as_json=False):
    """走正式解析链路取 §9.2，打印逐名明细 + 汇金合计。"""
    res = esf.fetch_holder_list(code)
    if as_json:
        print(json.dumps(res, ensure_ascii=False, indent=2))
        return 0
    if res.get("error"):
        print("  %s ✗ %s" % (code, res["error"]))
        if res.get("seg_head"):
            print("    正文头 400 字：%s" % res["seg_head"])
            print("    → 解析失配：用 --raw 看全文后给 _etf_share_flow.parse_top10 加档")
        return 1
    print("  %-7s %s（第 %d 页，解析档=%s，认出 %d 名）"
          % (code, res.get("as_of"), res.get("page"), res.get("parser"),
             len(res.get("items") or [])))
    for it in res.get("items") or []:
        star = "  ★汇金" if any(k in it["name"] for k in esf.HUIJIN_KW) else ""
        print("    %2d  %-38s %18.2f 份  %6.2f%%%s"
              % (it["rank"], it["name"], it["fen"], it["pct"], star))
    if res.get("huijin_pct") is not None:
        print("    ⇒ 汇金合计 %.2f%%（%s；来源=%s）"
              % (res["huijin_pct"],
                 "、".join(res.get("huijin_names") or []) or "关键词兜底",
                 res.get("huijin_src")))
    else:
        print("    ⇒ 汇金未在 §9.2 出现（来源=%s）" % res.get("huijin_src"))
    return 0


def main():
    ap = argparse.ArgumentParser(description="基金年报 §9.2「期末上市基金前十名持有人」抽取诊断")
    ap.add_argument("codes", nargs="*", default=[], help="基金代码（缺省 510300）")
    ap.add_argument("--raw", action="store_true", help="逐页 dump 原文（解析失配定位用）")
    ap.add_argument("--json", action="store_true", help="输出解析结果 JSON")
    a = ap.parse_args()
    if a.raw and a.json:
        print("✗ --raw 与 --json 互斥")
        return 3
    codes = a.codes or ["510300"]
    rc = 0
    for c in codes:
        rc |= dump_raw(c) if a.raw else check(c, a.json)
    return rc


if __name__ == "__main__":
    sys.exit(main())
