# -*- coding: utf-8 -*-
"""选股结果 → 子编码清单卡片 + 日K/分时并排图（2026-09-21 用户需求 · Q1）

从 `data/_apk_candidates_today.json`（publish 守护产出）读当日选股，
  1) 分配子编码（_pick_codes.assign）
  2) 每只出一并排图：近 60 日日K + 当天分时（_stock_chart.render_pair）
  3) 生成「对话卡片」文本（APK「对话」Tab 直接显示，含子编码供「买 11」下单）
  4) 并排图推送微信群

CLI:
    python _pick_card.py                # 组卡 + 出图 + 推送微信群
    python _pick_card.py --no-push      # 只出卡与图，不推送
"""
from __future__ import annotations

import json
import os
import sys
from typing import Any, Dict, List, Tuple

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import _pick_codes as PC                     # noqa: E402
import _stock_chart as SC                    # noqa: E402

CAND_FILE = os.path.join(SC.DATA_DIR, "_apk_candidates_today.json")
GROUP_ORDER = ["短线", "中线", "长线", "板块轮动"]


# ══════════════════════════════════════════════════════════
# 取数
# ══════════════════════════════════════════════════════════

def latest_groups(max_per_group: int = 4) -> Tuple[str, Dict[str, List[Dict[str, Any]]]]:
    """读最新一次选股结果，转成 {组名: [pick]}，pick 已补 price/main_cost/chg_pct/score/reason。"""
    d = json.load(open(CAND_FILE, encoding="utf-8"))
    try:
        from _kline_store import load_store      # noqa: PLC0415
        from _chip_dist import summary as chip   # noqa: PLC0415
        store = load_store()
    except Exception:                            # noqa: BLE001
        store, chip = {}, None

    out: Dict[str, List[Dict[str, Any]]] = {}
    for g in GROUP_ORDER:
        items = []
        for p in (d.get("groups") or {}).get(g) or []:
            if not isinstance(p, dict) or not p.get("secid"):
                continue
            secid = p["secid"]
            snaps = (store.get(secid) or {}).get("snaps") or []
            last = float(snaps[-1].get("close") or 0) if snaps else 0.0
            prev = float(snaps[-2].get("close") or 0) if len(snaps) >= 2 else 0.0
            mc = chip(secid, store).get("main_cost") if chip else None
            items.append({
                "secid": secid,
                "name": p.get("name") or secid,
                "price": round(last, 2),
                "chg_pct": round((last / prev - 1) * 100, 2) if prev else None,
                "main_cost": mc,
                "score": p.get("ratio") or p.get("total"),
                "reason": (p.get("tech") or (p.get("reso") or {}).get("tags")
                           and ";".join((p["reso"]).get("tags") or []) or "")[:40],
                "period": p.get("signal_period") or g,
            })
        if items:
            out[g] = items[:max_per_group]
    return str(d.get("asof") or d.get("generated_at") or ""), out


# ══════════════════════════════════════════════════════════
# 组卡 + 出图 + 推送
# ══════════════════════════════════════════════════════════

def build(max_per_group: int = 4, with_charts: bool = True
          ) -> Dict[str, Any]:
    """返回 {content, detail, codes, images}。content 进「对话」，detail 进「日志」。"""
    asof, groups = latest_groups(max_per_group)
    if not groups:
        return {"content": "暂无当日选股结果", "detail": "", "codes": {}, "images": []}

    codes = PC.assign(groups, asof=asof, max_per_group=max_per_group)
    content = PC.card_text(codes, title="%s 当日选股" % asof[:10] if asof else "当日选股")

    steps = ["读取选股结果 %s（%s）" % (CAND_FILE, asof or "无时间戳")]
    images: List[str] = []
    if with_charts:
        for code, p in sorted(codes.items()):
            try:
                path = SC.render_pair(p["secid"], p.get("name") or "")
                if path:
                    images.append(path)
                    steps.append("%s %s 并排图 OK（%.0fKB）"
                                 % (code, p.get("name"), os.path.getsize(path) / 1024))
                else:
                    steps.append("%s %s 日K数据不足，跳过" % (code, p.get("name")))
            except Exception as e:                   # noqa: BLE001
                steps.append("%s %s 出图失败 %s" % (code, p.get("name"), e))
    detail = "\n".join(["① " + steps[0]]
                       + ["② " + s for s in steps[1:]]
                       + ["③ 子编码已登记 data/_pick_codes.json（跨日失效前可「买 编码」）"])
    return {"content": content, "detail": detail, "codes": codes, "images": images}


def push_wechat(images: List[str], text: str, max_imgs: int = 6, codes=None) -> int:
    """并排图 + 文本推送微信群。返回成功图片数。

    2026-09-22：选股结果**同时**以结构化清单写进 PC→APK 中继箱
    （kind="candidates" + payload.list），APK 的「股票」Tab 据此逐条建卡片并本地保存。
    微信群照旧收「图 + 文本」，两条通道并联、互不影响（见 push_channel.relay_push）。
    """
    try:
        import push_channel as PC2                   # noqa: PLC0415
        cfg = PC2.load_notify_cfg()
    except Exception as e:                           # noqa: BLE001
        print("推送不可用:", e)
        return 0
    ok = 0
    for p in images[:max_imgs]:
        try:
            if PC2.send_image(p, cfg):
                ok += 1
        except Exception as e:                       # noqa: BLE001
            print("  图片推送失败:", e)
    # 结构化清单：APK「股票」Tab 优先读 payload.list，正文只作兜底展示
    payload = None
    if codes:
        try:
            payload = {"list": [
                {"code": c, "name": (p.get("name") or ""), "secid": p.get("secid", "")}
                for c, p in sorted(codes.items())
            ]}
        except Exception as e:                       # noqa: BLE001
            print("  选股清单构建失败（仍按纯文本推送）:", e)
            payload = None
    try:
        PC2.push("当日选股（子编码下单）", text, cfg,
                 kind="candidates", payload=payload)
    except Exception as e:                           # noqa: BLE001
        print("  文本推送失败:", e)
    return ok


if __name__ == "__main__":
    no_push = "--no-push" in sys.argv
    r = build()
    print("─── 对话卡片 ───")
    print(r["content"])
    print("─── 过程明细 ───")
    print(r["detail"])
    if not no_push and r["images"]:
        n = push_wechat(r["images"], r["content"], codes=r.get("codes"))
        print("微信群已推 %d/%d 张图" % (n, len(r["images"])))
