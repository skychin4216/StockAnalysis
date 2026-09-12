# -*- coding: utf-8 -*-
"""文本推送命令行助手（2026-09-09，供 CodeBuddy automation/三次板块挖掘推送调用）。

把一段 UTF-8 文本经 push_channel（企业微信机器人 > pushplus > serverchan）发到微信。
文本放文件里（避免命令行转义地狱），标题走 --title。

用法：
  python _push_text_cli.py --title "板块晨报 09-10" --file data/_sector_hunt_0930.txt
  python _push_text_cli.py --title "测试" --content "直接给内容也行" [--dry]

返回码：0=至少一路发送成功；2=发送失败；3=参数错。
"""
import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, ".."))
sys.path.insert(0, HERE)
import push_channel  # noqa: E402

APP_CONFIG = os.path.join(ROOT, "app", "src", "main", "assets", "data", "app_config.json")


def load_notify_cfg():
    try:
        with open(APP_CONFIG, encoding="utf-8") as f:
            return (json.load(f).get("notify") or {})
    except Exception as e:
        print("读取推送配置失败 %s: %s" % (APP_CONFIG, e))
        return {}


def main():
    ap = argparse.ArgumentParser(description="微信文本推送 CLI")
    ap.add_argument("--title", default="")
    ap.add_argument("--content", default="")
    ap.add_argument("--file", help="UTF-8 文本文件路径（与 --content 二选一，file 优先）")
    ap.add_argument("--dry", action="store_true", help="只打印不真发")
    args = ap.parse_args()
    content = args.content
    if args.file:
        try:
            with open(args.file, encoding="utf-8") as f:
                content = f.read().strip()
        except Exception as e:
            print("读取文本文件失败 %s: %s" % (args.file, e))
            return 3
    if not content.strip():
        print("空内容，未发送")
        return 3
    if args.dry:
        print("--dry 预览 %s 字节，未发送" % len(content))
        print(content[:2000])
        return 0
    sent = push_channel.push(args.title, content, load_notify_cfg())
    print("推送结果:", "成功" if sent else "失败")
    return 0 if sent else 2


if __name__ == "__main__":
    sys.exit(main())
