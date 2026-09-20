# -*- coding: utf-8 -*-
"""K 线 / 数据源连通性诊断（读 data/datasources.json 统一配置）。

2026-09-19 改造：原为写死 URL 的一次性探针（2026-08 找腾讯 K 线替代源时用的），
现按**源名**诊断 —— 主机取自统一配置，与生产链路同一份来源，
换主机/换源后诊断结果即反映真实链路，不必改本文件。

区别：
  · 批量探活（只看状态码）→ `python _sources.py --check [源名 ...]`
  · 本脚本 → **打印响应内容**，用于排查返回体异常（反爬页/空数据/字段变更）

用法：
  python _netdiag2.py                      # 默认 K 线四源对照
  python _netdiag2.py tencent_kline sina_kline
  python _netdiag2.py --all                # 全部源（占位符用示例值填充）
"""
import os
import sys

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import _sources as S  # noqa: E402

PROXIES = {"http": None, "https": None}
HEADERS = {"User-Agent": "Mozilla/5.0"}

# K 线类源的示例查询串（诊断用；主机来自配置，参数在此拼接）
SAMPLE_QS = {
    "tencent_kline": "?param=sz300308,day,2026-07-01,2026-08-12,60,qfq",
    "tencent_proxy_kline": "?param=sz300308,day,2026-07-01,2026-08-12,60,qfq",
    "sina_kline": "?symbol=sz300308&scale=240&ma=no&datalen=60",
    "east_kline": "?secid=0.300308&klt=101&fqt=1&lmt=60"
                  "&fields1=f1,f2,f3,f4,f5,f6"
                  "&fields2=f51,f52,f53,f54,f55,f56",
    "east_delay_fflow": "?secid=0.300308&klt=101&lmt=10&fields1=f1,f2,f3"
                        "&fields2=f51,f52,f53,f54,f55,f56",
    "sina_moneyflow": "?page=1&num=30&sort=opendate&asc=0&daima=sz300308",
}
# 占位符示例值（{codes} / {secid} 等）
FMT_VALUES = {"codes": "sz300308", "code": "300308", "secid": "0.300308",
              "info": "sample", "key": "sample"}
DEFAULT = ["tencent_kline", "tencent_proxy_kline", "sina_kline", "east_kline"]


def diag(name, url):
    """请求并打印响应前 600 字（含状态码/异常）。"""
    try:
        hdr = {**HEADERS, **S.headers(name)}
        r = requests.get(url, timeout=12, headers=hdr, proxies=PROXIES)
        enc = (S._src(name) or {}).get("encoding")
        if enc:
            r.encoding = enc
        print("=== %s -> %s ===" % (name, r.status_code))
        print(r.text[:600])
        print()
    except Exception as e:  # noqa: BLE001
        print("=== %s -> ERR %s: %s ===" % (name, type(e).__name__, str(e)[:100]))
        print()


def main(argv):
    if not argv:
        names = DEFAULT
    elif argv[0] == "--all":
        names = S.names()
    else:
        names = argv
    for name in names:
        try:
            u = S.url(name, **FMT_VALUES)
        except Exception as e:  # noqa: BLE001
            print("=== %s -> 配置读取失败: %s ===" % (name, e))
            continue
        u = (u or "") + SAMPLE_QS.get(name, "")
        if not u:
            print("=== %s -> 未登记（跳过）===" % name)
            continue
        diag(name, u)


if __name__ == "__main__":
    main(sys.argv[1:])
