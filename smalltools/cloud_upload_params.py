# -*- coding: utf-8 -*-
"""
PC 拟合参数回流：把 backtest_params.json 上传到腾讯云 COS
========================================================================
流程：
  1. PC 端跑 AutoQuant/export_params.py（或 AutoQuant 其他拟合脚本）产出参数
  2. 本脚本把参数文件上传到 COS 的 {params_key}（默认
     stockanalysis/params/backtest_params.json）
  3. 手机端「设置 → 云端数据同步 → 下载最新拟合参数」导入立即生效
     （CloudSyncManager.downloadParams → BacktestParamsLoader.importParams）

用法：
  python smalltools/cloud_upload_params.py                        # 上传 assets 默认参数
  python smalltools/cloud_upload_params.py --file 我的参数.json    # 指定文件
  python smalltools/cloud_upload_params.py --publish              # 同时同步到 assets（默认已是）
  python smalltools/cloud_upload_params.py --public-read          # 上传后设置公有读（可选）
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cos_utils import load_cloud_config, require_config, request  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_FILE = os.path.join(ROOT, "app", "src", "main", "assets", "backtest_params.json")
# 生成签名所需的规范化 JSON：与手机 BacktestParamsLoader.importParams 的解析保持一致
# （上传原文件即可，避免二次序列化引入差异）


def set_public_read(cfg, key):
    """可选：把对象 ACL 设为 public-read，便于无密钥匿名下载。"""
    params = {"acl": None}
    headers = {"x-cos-acl": "public-read"}
    status, _, body = request(
        cfg["secret_id"], cfg["secret_key"], cfg["bucket"], cfg["region"],
        "put", "/" + key, http_params={"acl": ""}, http_headers=headers)
    if status == 200:
        print("已设置公有读: %s" % key)
    else:
        print("设置公有读失败 HTTP %s（可忽略，私有读需手机端签名下载）: %s"
              % (status, body.decode("utf-8", "replace")[:300]))
    _ = params


def main():
    ap = argparse.ArgumentParser(description="上传拟合参数到 COS（参数回流）")
    ap.add_argument("--file", default=DEFAULT_FILE, help="要上传的 backtest_params.json")
    ap.add_argument("--params-key", help="覆盖 COS 对象 key")
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
                   ("params_key", args.params_key)):
        if cli:
            cfg[k] = cli
    require_config(cfg)

    path = args.file
    if not os.path.exists(path):
        print("找不到参数文件: %s（请先运行 AutoQuant/export_params.py）" % path)
        sys.exit(1)
    try:
        with open(path, encoding="utf-8") as f:
            json.load(f)
    except ValueError as e:
        print("参数文件不是合法 JSON: %s" % e)
        sys.exit(1)

    body = open(path, "rb").read()
    key = cfg["params_key"].lstrip("/")
    headers = {"content-type": "application/json"}
    status, _, resp = request(
        cfg["secret_id"], cfg["secret_key"], cfg["bucket"], cfg["region"],
        "put", "/" + key, http_headers=headers, body=body)
    if status == 200:
        print("参数回流成功 ✓  %s/%s  (%s KB)" % (cfg["bucket"], key, len(body) // 1024))
        print("手机端操作：设置 → 云端数据同步 → 下载最新拟合参数")
        if args.public_read:
            set_public_read(cfg, key)
    else:
        print("上传失败 HTTP %s: %s" % (status, resp.decode("utf-8", "replace")[:500]))
        sys.exit(1)


if __name__ == "__main__":
    main()
