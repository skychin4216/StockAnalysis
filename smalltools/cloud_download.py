# -*- coding: utf-8 -*-
"""
下载手机上传到腾讯云 COS 的数据包，供 PC 回溯对比 / 拟合优化
========================================================================
手机端 CloudSyncManager 每天把数据库关键表打包为 phone_YYYYMMDD_HHMMSS.zip
上传到 COS 的 {prefix}/ 下。本脚本：

1. 列出现有数据包（ListObjectsV2，签名请求）
2. 下载最新若干 ZIP（默认 1 个，--max 控制，--all 全下）
3. 解压 data.json 到 `_records/cloud/<zip名>/data.json`
4. 打印各表行数摘要；可选 `--merge` 汇总成一个 cloud_export.json

参数回流：`--params` 可同时把 COS 上的 backtest_params.json 下载到
app/src/main/assets/backtest_params.json（PC 拟合结果回流手机的同一份）。

用法：
  python smalltools/cloud_download.py                  # 下载最新 1 个包
  python smalltools/cloud_download.py --max 7          # 最近 7 天
  python smalltools/cloud_download.py --all --merge    # 全部并汇总
  python smalltools/cloud_download.py --params         # 顺带下载最新参数文件
  python smalltools/cloud_download.py --prefix 自定义前缀
"""
import argparse
import io
import json
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cos_utils import load_cloud_config, require_config, request  # noqa: E402

RECORD_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_records", "cloud")
TABLE_KEYS = [
    "strategy_trade_orders", "t_trade_records", "strategy_trade_backtests",
    "daily_period_result", "strategy_trade_fitting_params",
    "t_trade_recommendations", "real_positions", "period_holding_profit",
]


def list_objects(cfg, prefix):
    """ListObjectsV2 列出现有对象，返回 [{Key, Size, LastModified}]。"""
    params = {"list-type": "2", "prefix": prefix, "max-keys": "1000"}
    status, headers, body = request(
        cfg["secret_id"], cfg["secret_key"], cfg["bucket"], cfg["region"],
        "get", "/", http_params=params)
    if status != 200:
        print("列对象失败 HTTP %s: %s" % (status, body.decode("utf-8", "replace")[:500]))
        return []
    import xml.etree.ElementTree as ET
    root = ET.fromstring(body)
    ns = {"s": "http://www.qcloud.com/document/product/436/7751"}
    items = []
    for c in root.findall(".//s:Contents", ns):
        key = c.findtext("s:Key", "", ns)
        size = c.findtext("s:Size", "0", ns)
        lm = c.findtext("s:LastModified", "", ns)
        if key and key.endswith(".zip"):
            items.append({"Key": key, "Size": int(size), "LastModified": lm})
    items.sort(key=lambda x: x["Key"], reverse=True)
    return items


def download_object(cfg, key):
    status, _, body = request(cfg["secret_id"], cfg["secret_key"],
                              cfg["bucket"], cfg["region"], "get", "/" + key)
    if status != 200:
        print("下载失败 %s HTTP %s" % (key, status))
        return None
    return body


def extract_data_json(zip_bytes):
    """从 ZIP 中解出 data.json（手机打包格式）。"""
    try:
        with zipfile.ZipFile(io.BytesIO(zip_bytes)) as z:
            names = z.namelist()
            if "data.json" not in names:
                return None, "zip 内无 data.json（含 %s）" % names
            return json.loads(z.read("data.json").decode("utf-8")), None
    except zipfile.BadZipFile as e:
        return None, "BadZipFile: %s" % e


def save_one(cfg, obj, merge_all):
    key = obj["Key"]
    name = os.path.splitext(os.path.basename(key))[0]
    body = download_object(cfg, key)
    if body is None:
        return None
    data, err = extract_data_json(body)
    if err:
        print("跳过 %s：%s" % (key, err))
        return None
    out_dir = os.path.join(RECORD_DIR, name)
    os.makedirs(out_dir, exist_ok=True)
    out_file = os.path.join(out_dir, "data.json")
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    summary = {t: len(data.get(t, [])) for t in TABLE_KEYS}
    print("已保存 %s  (%s KB, %s)" % (out_file,
          obj["Size"] // 1024, obj.get("LastModified", "")))
    print("  各表行数: %s" % ", ".join("%s=%s" % (k, v) for k, v in summary.items()))
    if merge_all is not None:
        merge_all["packages"].append({
            "name": name, "source_key": key, "uploaded": obj.get("LastModified", ""),
            "data": data})
    return data


def download_params(cfg, out_path):
    """下载 COS 上的 backtest_params.json（参数回流）。"""
    status, _, body = request(cfg["secret_id"], cfg["secret_key"],
                              cfg["bucket"], cfg["region"], "get", "/" + cfg["params_key"])
    if status != 200:
        print("参数下载失败 HTTP %s: %s" % (status, body.decode("utf-8", "replace")[:300]))
        return False
    try:
        json.loads(body.decode("utf-8"))
    except ValueError as e:
        print("参数文件不是合法 JSON：%s" % e)
        return False
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "wb") as f:
        f.write(body)
    print("参数已下载 → %s (%s KB)" % (out_path, len(body) // 1024))
    return True


def main():
    ap = argparse.ArgumentParser(description="下载手机上传的 COS 数据包")
    ap.add_argument("--max", type=int, default=1, help="下载最近 N 个包（默认 1）")
    ap.add_argument("--all", action="store_true", help="下载全部包")
    ap.add_argument("--merge", action="store_true",
                    help="汇总所有包到一个 cloud_export.json")
    ap.add_argument("--params", action="store_true",
                    help="顺带把 COS 上 backtest_params.json 下载回 assets")
    ap.add_argument("--prefix")
    ap.add_argument("--bucket")
    ap.add_argument("--region")
    ap.add_argument("--secret-id")
    ap.add_argument("--secret-key")
    args = ap.parse_args()

    cfg = load_cloud_config()
    for k in ("prefix", "bucket", "region", "secret_id", "secret_key"):
        val = getattr(args, k.replace("_", "-"), None) or getattr(args, k, None)
        if val:
            cfg[k] = val
    require_config(cfg)

    prefix = cfg["prefix"].strip("/") + "/"
    print("COS: %s  前缀 %s" % (cfg["bucket"], prefix))
    objs = list_objects(cfg, prefix)
    if not objs:
        print("未在 %s 下找到任何 phone_*.zip（先确认手机端已上传）。" % prefix)
        sys.exit(1)
    print("共 %d 个数据包，最近: %s" % (len(objs), objs[0]["Key"]))

    picked = objs if args.all else objs[:max(args.max, 1)]
    merge_all = {"packages": []} if args.merge else None
    for obj in picked:
        save_one(cfg, obj, merge_all)

    if args.merge and merge_all["packages"]:
        merge_path = os.path.join(RECORD_DIR, "cloud_export.json")
        with open(merge_path, "w", encoding="utf-8") as f:
            json.dump(merge_all, f, ensure_ascii=False, indent=1)
        print("已汇总 %d 个包 → %s" % (len(merge_all["packages"]), merge_path))

    if args.params:
        assets_dir = os.path.join(os.path.dirname(os.path.dirname(
            os.path.abspath(__file__))), "app", "src", "main", "assets")
        download_params(cfg, os.path.join(assets_dir, "backtest_params.json"))


if __name__ == "__main__":
    main()
