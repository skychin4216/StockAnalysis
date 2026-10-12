# -*- coding: utf-8 -*-
"""拉取 APK「上传今日数据」里的日志（2026-09-21 转正）

背景：APK 的 `CloudSyncManager` 会把当日 logcat 打进数据包上传到 COS：
    `{prefix}/phone_YYYYMMDD_HHMMSS.zip` → 内含 `data.json` + `log_YYYYMMDD.txt`
（见 `FileLogger.readTodayLog()` / `CloudSyncManager.buildDataPackage()`）

原来只能靠一次性脚本去捞，本模块把它**转正**成可复用工具，失败排查时一条命令搞定。

复用现成能力：`cloud_download.list_objects(cfg, prefix, suffix)` + `download_object(cfg, key)`
（它们对带 query 的 COS 请求签名是正确的，`cos_utils.request` 直接拼 query 会 403）。

CLI:
    python _apk_log_fetch.py                     # 拉最新一个包，打印问题行
    python _apk_log_fetch.py --list              # 只列出可用包
    python _apk_log_fetch.py --key <COS key>     # 指定包
    python _apk_log_fetch.py --grep 发送失败      # 自定义关键字
    python _apk_log_fetch.py --full              # 除问题行外，再打印日志尾部 40 行
"""
from __future__ import annotations

import argparse
import io
import os
import re
import sys
import zipfile
from typing import Any, Dict, List

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

OUT_DIR = os.path.join(ROOT, "data", "_apk_logs")

# 默认关注的问题关键字（发送/中继/网络）
DEFAULT_GREP = ["resolve", "UnknownHost", "发送失败", "中继超时", "CosRelay", "CosSigner",
                "SDK put 失败", "回退自研", "SocketTimeout", "ConnectException",
                "SignatureDoesNotMatch", "403", "FATAL", "AndroidRuntime"]


def list_packs(prefix: str = "") -> List[Dict[str, Any]]:
    """列出 COS 上的手机数据包（按时间升序）。"""
    import cos_utils as C                            # noqa: PLC0415
    import cloud_download as D                       # noqa: PLC0415
    cfg = C.load_cloud_config()
    pre = prefix or cfg.get("prefix") or "stockanalysis/phone"
    objs = D.list_objects(cfg, pre, None) or []
    packs = [o for o in objs if re.search(r"phone_\d{8}_\d{6}\.zip$", str(o.get("Key", "")))]
    packs.sort(key=lambda o: o.get("Key", ""))
    return packs


def fetch(key: str = "") -> Dict[str, Any]:
    """下载指定（或最新）数据包，解出 log_*.txt / data.json。"""
    import cos_utils as C                            # noqa: PLC0415
    import cloud_download as D                       # noqa: PLC0415
    cfg = C.load_cloud_config()
    packs = list_packs()
    if not packs:
        return {"ok": False, "error": "COS 上没有 phone_*.zip 数据包"}
    obj = next((o for o in packs if o["Key"] == key), None) if key else packs[-1]
    if obj is None:
        return {"ok": False, "error": "指定的 key 不存在: %s" % key}
    raw = D.download_object(cfg, obj["Key"])
    if not raw:
        return {"ok": False, "error": "下载失败: %s" % obj["Key"]}
    out = {"ok": True, "key": obj["Key"], "size": obj.get("Size"),
           "last_modified": obj.get("LastModified"), "log_text": "", "names": [], "data": None}
    try:
        z = zipfile.ZipFile(io.BytesIO(raw))
        out["names"] = z.namelist()
        for n in z.namelist():
            if n.startswith("log_"):
                out["log_text"] = z.read(n).decode("utf-8", "replace")
            elif n == "data.json":
                import json                          # noqa: PLC0415
                try:
                    out["data"] = json.loads(z.read(n).decode("utf-8", "replace"))
                except Exception:                    # noqa: BLE001
                    pass
    except Exception as e:                           # noqa: BLE001
        out["ok"] = False
        out["error"] = "解压失败: %s" % e
    return out


def save(log_text: str, key: str) -> str:
    m = re.search(r"phone_(\d{8})_", key)
    day = m.group(1) if m else "unknown"
    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, "log_%s.txt" % day)
    with open(path, "w", encoding="utf-8") as f:
        f.write(log_text)
    return path


def main() -> int:
    ap = argparse.ArgumentParser(description="拉取 APK 上传到 COS 的当日日志")
    ap.add_argument("--list", action="store_true", help="只列出可用数据包")
    ap.add_argument("--key", default="", help="指定 COS key（默认最新）")
    ap.add_argument("--grep", default="", help="自定义关键字（逗号分隔）")
    ap.add_argument("--full", action="store_true", help="额外打印日志尾部 40 行")
    args = ap.parse_args()

    if args.list:
        packs = list_packs()
        print("共 %d 个数据包：" % len(packs))
        for o in packs:
            print("  %-52s %8.1f KB  %s" % (o["Key"].split("/")[-1],
                                            (o.get("Size") or 0) / 1024,
                                            o.get("LastModified")))
        return 0

    r = fetch(args.key)
    if not r.get("ok"):
        print("失败:", r.get("error"))
        return 1
    print("数据包: %s ｜ %.1f KB ｜ %s" % (r["key"], (r["size"] or 0) / 1024,
                                          r["last_modified"]))
    print("包内:", r["names"])
    txt = r.get("log_text") or ""
    if not txt:
        print("包内没有 log_*.txt")
        return 1
    path = save(txt, r["key"])
    print("日志 %d 行 -> %s" % (len(txt.splitlines()), path))

    kws = [k.strip() for k in args.grep.split(",") if k.strip()] or DEFAULT_GREP
    hits = [ln for ln in txt.splitlines() if any(k in ln for k in kws)]
    print("\n=== 命中问题关键字 %d 行（%s）===" % (len(hits), ", ".join(kws[:6])))
    for ln in hits[-60:]:
        print("  ", ln.strip()[:180])
    if args.full:
        print("\n=== 日志尾部 40 行 ===")
        for ln in txt.splitlines()[-40:]:
            print("  ", ln.strip()[:180])
    return 0


if __name__ == "__main__":
    sys.exit(main())
