# -*- coding: utf-8 -*-
"""
PC 市场库上传到腾讯云 COS（供 APK「设置 → 云端数据同步 → 下载行情库」使用）
============================================================================
背景：
  - PC 端公共市场库 StockAnalysis/data/market_data.db（含全池个股 + 四大指数
    上证/深成/创业板/科创50 全史 K 线），是 exe / smalltools / APK 的统一数据源。
  - APK 端 CloudSyncManager.downloadMarketDb 从 COS 的 {db_key} 下载并导入
    Room daily_snapshot，用于选股与「K线趋势 → 大盘K线」等展示。

流程：
  1. 先跑增量/全量更新让 data/market_data.db 数据最新（含指数全史）
  2. 本脚本把该库上传到 COS 的 {db_key}（默认 stockanalysis/db/market_data.db）
  3. 手机端「设置 → 云端数据同步 → 下载行情库」导入即生效

用法：
  python smalltools/cloud_upload_db.py                       # 上传当前市场库
  python smalltools/cloud_upload_db.py --public-read         # 上传并设公有读（可选）
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cos_utils import load_cloud_config, require_config, request  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_DB = os.path.join(ROOT, "data", "market_data.db")


def set_public_read(cfg, key):
    headers = {"x-cos-acl": "public-read"}
    status, _, body = request(
        cfg["secret_id"], cfg["secret_key"], cfg["bucket"], cfg["region"],
        "put", "/" + key, http_params={"acl": ""}, http_headers=headers)
    if status == 200:
        print("已设置公有读: %s" % key)
    else:
        print("设置公有读失败 HTTP %s（可忽略，私有读需手机端签名下载）: %s"
              % (status, body.decode("utf-8", "replace")[:300]))


def main():
    ap = argparse.ArgumentParser(description="上传市场库 market_data.db 到 COS（行情库回流）")
    ap.add_argument("--file", default=DEFAULT_DB, help="市场库路径（默认 data/market_data.db）")
    ap.add_argument("--db-key", help="覆盖 COS 对象 key（默认 stockanalysis/db/market_data.db）")
    ap.add_argument("--bucket")
    ap.add_argument("--region")
    ap.add_argument("--secret-id")
    ap.add_argument("--secret-key")
    ap.add_argument("--public-read", action="store_true",
                    help="上传后设置公有读（手机可匿名下载，不设则手机需用密钥签名下载）")
    args = ap.parse_args()

    cfg = load_cloud_config()
    for k, cli in (("bucket", args.bucket), ("region", args.region),
                   ("secret_id", args.secret_id), ("secret_key", args.secret_key),
                   ("db_key", args.db_key)):
        if cli:
            cfg[k] = cli
    require_config(cfg)

    path = args.file
    if not os.path.exists(path):
        print("找不到市场库文件: %s（请先运行 smalltools/_update_cache_inc.py 更新数据）" % path)
        sys.exit(1)

    body = open(path, "rb").read()
    key = cfg.get("db_key") or "stockanalysis/db/market_data.db"
    key = key.lstrip("/")
    headers = {"content-type": "application/octet-stream"}
    status, _, resp = request(
        cfg["secret_id"], cfg["secret_key"], cfg["bucket"], cfg["region"],
        "put", "/" + key, http_headers=headers, body=body, timeout=300)
    if status == 200:
        print("市场库上传成功 ✓  %s/%s  (%.1f MB)" % (
            cfg["bucket"], key, len(body) / 1048576.0))
        print("手机端操作：设置 → 云端数据同步 → 下载行情库")
        if args.public_read:
            set_public_read(cfg, key)
    else:
        print("上传失败 HTTP %s: %s" % (status, resp.decode("utf-8", "replace")[:500]))
        sys.exit(1)


if __name__ == "__main__":
    main()
