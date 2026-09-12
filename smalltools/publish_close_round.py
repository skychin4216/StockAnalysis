# -*- coding: utf-8 -*-
"""收盘选股整轮推送（强制完整版）。

背景：`_publish_candidates.py --once` 默认走精简通道，只推“新增买点”，
且会对比上轮，盘外往往无新增导致静默不推；守护轮则使用 `send_wechat_round()`
推完整表格，但有指纹去重。

本脚本读取最新候选清单（默认 `_last_publish.json`），直接调用
`send_wechat_round()` 强制推送完整一轮，用于收盘后手动补推。

用法：
  python publish_close_round.py           # 推送完整一轮
  python publish_close_round.py --dry     # 只打印不推送
  python publish_close_round.py --no-pos  # 不推送实仓建议段
  python publish_close_round.py --file AutoQuant/data/candidates_quant.json
"""
import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _publish_candidates as pc  # noqa: E402


def main():
    ap = argparse.ArgumentParser(description="收盘选股完整轮推送（强制推候选表格）")
    ap.add_argument("--file", default=os.path.join(HERE, "_last_publish.json"),
                    help="候选清单 JSON，默认 _last_publish.json")
    ap.add_argument("--dry", action="store_true", help="只打印不推送")
    ap.add_argument("--no-pos", action="store_true", help="不推送实仓建议段")
    args = ap.parse_args()

    with open(args.file, encoding="utf-8") as f:
        data = json.load(f)
    cfg = pc.load_notify_cfg()
    cache = pc.load_cache()
    pages, imgs = pc.round_pages(
        data, None, cfg,
        old_secids=set(),
        cache=cache,
        pos_advice=not args.no_pos,
        scene="收盘选股",
        note_offline=None,
        lowbuy_offline=None,
        table_img_dir=None if args.dry else pc.TABLE_IMG_DIR,
    )
    if args.dry:
        for title, content in pages:
            print("=" * 40)
            print(title)
            print(content)
        print("候选表格长图 + XLSX + 原始CSV(实际推送时发送): %s" % (imgs or "无"))
        return 0
    ok = False
    for title, content in pages:
        sent = pc._push_wechat(title, content, cfg)
        ok = sent or ok
        print("PUSH[%s] -> %s (%d chars)" % (title, "OK" if sent else "FAIL", len(content)))
    for path in imgs:
        # 2026-09-12：imgs = [合并长图(png), 表格(xlsx), 原始表格(csv)] → 按后缀分派
        is_file = str(path).lower().endswith((".csv", ".xlsx"))
        sent = (pc.push_channel.send_file(path, cfg) if is_file
                else pc.push_channel.send_image(path, cfg))
        ok = sent or ok
        print("PUSH-%s[%s] -> %s" % ("FILE" if is_file else "IMG",
                                     os.path.basename(path), "OK" if sent else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
