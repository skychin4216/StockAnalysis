# -*- coding: utf-8 -*-
"""审计龙头池：剔除 ST/退市/亏损 标的。

从 AutoQuant hot_sector_config 读取全部龙头，对每只用东财接口核对：
1. 实时行情 -> 最新名称（判断是否 ST/*ST/退市）、是否可交易
2. 财务摘要 -> 最新一期归母净利润（判断是否亏损）

用法：
  python -X utf8 -u _audit_leaders.py             # 只审计并打印报告
  python -X utf8 -u _audit_leaders.py --apply     # 审计后直接剔除问题股并重写配置
"""
import argparse
import json
import os
import re
import socket
import sys
import time
from concurrent.futures import ThreadPoolExecutor

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "AutoQuant"))
from autoquant.hot_sector_config import HOT_SECTOR_CONFIG  # noqa: E402

# 全局兜底：DNS/连接/读取最多 8 秒，超时就抛错，避免无限卡死
socket.setdefaulttimeout(8)

HEADERS = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"}
PROXIES = {"http": None, "https": None}

HOSTS = ["https://push2.eastmoney.com", "https://push2delay.eastmoney.com"]

_session = None


def get_session():
    global _session
    if _session is None:
        retry = Retry(total=2, backoff_factor=0.3,
                      status_forcelist=[429, 500, 502, 503, 504],
                      allowed_methods=["GET"])
        _session = requests.Session()
        _session.headers.update(HEADERS)
        _session.proxies.update(PROXIES)
        adapter = HTTPAdapter(max_retries=retry, pool_connections=8, pool_maxsize=16)
        _session.mount("http://", adapter)
        _session.mount("https://", adapter)
    return _session


def to_east_secid(code):
    """601969.SH / sz000338 / 000338 -> 1.601969 / 0.000338"""
    c = code.strip().lower()
    if "." in c:
        code5, mkt = c.rsplit(".", 1)
    elif c.startswith(("sh", "sz", "bj")):
        mkt, code5 = c[:2], c[2:]
    else:
        mkt, code5 = ("sz" if c.startswith(("0", "3")) else "sh" if c.startswith("6") else "bj"), c
    if mkt == "sh":
        return "1." + code5
    return "0." + code5


def fetch_quote(code):
    """返回 {name, ...} 或 None（查询失败/已退市）。"""
    secid = to_east_secid(code)
    params = {"secid": secid, "fields": "f57,f58,f43,f116,f117,f127,f60"}
    s = get_session()
    for host in HOSTS:
        try:
            r = s.get(host + "/api/qt/stock/get", params=params, timeout=6)
            data = (r.json() or {}).get("data")
            if data:
                return data
        except Exception:
            continue
    return None


def fetch_latest_net_profit(code):
    """返回 (report_date, 归母净利润-亿, 净利润-亿) 或 (None, None, None)。"""
    pure = re.sub(r"\D", "", code)[-6:]
    url = "https://datacenter.eastmoney.com/securities/api/data/v1/get"
    params = {
        "reportName": "RPT_LICO_FN_CPD",
        "columns": "ALL",
        "filter": '(SECURITY_CODE="%s")' % pure,
        "pageNumber": "1", "pageSize": "1",
        "sortColumns": "REPORTDATE", "sortTypes": "-1",
        "source": "HSF10", "client": "PC",
    }
    try:
        r = get_session().get(url, params=params, timeout=6)
        d = r.json()
        lst = ((d.get("result") or {}).get("data") or [])
        if not lst:
            return None, None, None
        row = lst[0]
        rep = (row.get("REPORTDATE") or row.get("REPORT_DATE") or "")[:10]
        npc = row.get("NETPROFIT_PARENT_COMPANY")  # 归母净利润(元)
        np_ = row.get("NETPROFIT")
        g1 = npc / 1e8 if npc is not None else None
        g2 = np_ / 1e8 if np_ is not None else None
        return rep, g1, g2
    except Exception as e:
        print("  [warn] %s 财务接口失败: %s" % (code, e), flush=True)
        return None, None, None


def check_one(item):
    code, info = item
    q = fetch_quote(code)
    name = (q or {}).get("f58") or info.get("name")
    flag = []
    if not q:
        flag.append("查询无结果(可能退市/停牌)")
    else:
        n = (q.get("f58") or "").strip()
        if re.search(r"\bST\b|\*ST", n, re.I):
            flag.append("ST股: %s" % n)
        elif "退" in n:
            flag.append("退市: %s" % n)
    rep, npc, np_ = fetch_latest_net_profit(code)
    if npc is not None and npc < 0:
        flag.append("归母净利亏损: %s亿(%s)" % (round(npc, 2), rep))
    elif npc is None and np_ is not None and np_ < 0:
        flag.append("净利亏损: %s亿(%s)" % (round(np_, 2), rep))
    return code, name, rep, flag


def audit(apply=False):
    all_leaders = {}
    for sector, sec in HOT_SECTOR_CONFIG.items():
        for sub, subi in sec["sub_sectors"].items():
            for code, info in subi["leaders"].items():
                all_leaders.setdefault(code, info)

    print("=" * 100, flush=True)
    print("龙头池审计：共 %d 只唯一标的" % len(all_leaders), flush=True)
    print("=" * 100, flush=True)

    problems = []
    results = {}

    with ThreadPoolExecutor(max_workers=8) as ex:
        for i, (code, name, rep, flag) in enumerate(ex.map(check_one, sorted(all_leaders.items()))):
            status = "OK " if not flag else "!! "
            print("%s %-12s %-8s %-10s %s" % (status, code, name, rep or "-",
                                              ("; ".join(flag) if flag else "正常")), flush=True)
            results[code] = flag
            if flag:
                problems.append((code, name, flag))

    print("\n" + "=" * 100, flush=True)
    print("问题标的 %d 只：" % len(problems), flush=True)
    for code, name, flag in problems:
        print("  %s %s -> %s" % (code, name, "; ".join(flag)), flush=True)
    print("=" * 100, flush=True)

    remove_codes = {c for c, _, _ in problems}

    if apply and remove_codes:
        removed = _remove_from_config(remove_codes, problems)
        seen = set()
        dedup = []
        for code, name in removed:
            if code not in seen:
                seen.add(code)
                dedup.append((code, name))
        print("\n已从 HOT_SECTOR_CONFIG 剔除 %d 只：" % len(dedup), flush=True)
        for code, name in dedup:
            print("  - %s %s" % (code, name), flush=True)
        rewrite_config()
        for sector, sec in HOT_SECTOR_CONFIG.items():
            cnt = sum(len(s["leaders"]) for s in sec["sub_sectors"].values())
            print("  %s: %d 只" % (sector, cnt), flush=True)
    return remove_codes


def _remove_from_config(remove_codes, problems):
    removed = []
    pmap = {c: name for c, name, _ in problems}
    for sector, sec in HOT_SECTOR_CONFIG.items():
        for sub, subi in sec["sub_sectors"].items():
            leaders = subi["leaders"]
            for code in list(leaders.keys()):
                if code in remove_codes:
                    removed.append((code, pmap.get(code, leaders[code].get("name"))))
                    del leaders[code]
    return removed


def rewrite_config():
    """把内存中的 HOT_SECTOR_CONFIG 写回 py 源文件。"""
    cfg = HOT_SECTOR_CONFIG
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        "..", "AutoQuant", "autoquant", "hot_sector_config.py")
    with open(path, "w", encoding="utf-8") as f:
        f.write(_gen_py(cfg))
    print("已重写 %s" % path)


def _gen_py(cfg):
    """按原格式生成配置 py 文本。"""
    lines = []
    lines.append('"""')
    lines.append("2025-2026年热门板块及子板块龙头股配置（扩展版）")
    lines.append("")
    lines.append("时间范围: 2024-01-01 至 2026-06-14（最新交易日）")
    lines.append("")
    lines.append("2024-2026年热门科技板块（AI硬件供应链）:")
    lines.append("- 稀缺小金属: 钨、钼、铌、钒、钛、稀土（涨幅最大）")
    lines.append("- 有色金属: 锂、铜、铝")
    lines.append("- 半导体: 设备、设计、封测、国产替代")
    lines.append("- AI算力: 超算、GPU、国产算力、算力芯片")
    lines.append("- 光通信: 光模块、光芯片、銅纜高速連接")
    lines.append("- PCB: 服務器PCB、HDI板、IC載板")
    lines.append("- 電網設備: 特高壓、智能電網、儲能")
    lines.append("- 氦氣: 稀有氣體")
    lines.append("- 新能源: 鋰電、光伏、綠電")
    lines.append("- 存儲: HBM、國產存儲")
    lines.append("")
    lines.append("每個子板塊: 主板 TOP 5（實際取3只）")
    lines.append('"""')
    lines.append("")
    lines.append("# 2025年热门板块及子板块龙头股（主板优先，每个子板块5只）")
    lines.append("HOT_SECTOR_CONFIG = {")
    for sector, sec in cfg.items():
        lines.append("    # ============================================")
        lines.append("    # %s" % sector)
        lines.append("    # ============================================")
        lines.append("    '%s': {" % sector)
        lines.append("        'desc': '%s'," % sec["desc"])
        lines.append("        'sub_sectors': {")
        for sub, subi in sec["sub_sectors"].items():
            lines.append("            '%s': {" % sub)
            lines.append("                'desc': '%s'," % subi["desc"])
            lines.append("                'leaders': {")
            for code, info in subi["leaders"].items():
                lines.append("                    '%s': {'name': '%s', 'exchange': '%s'}," % (
                    code, info["name"], info["exchange"]))
            lines.append("                }")
            lines.append("            },")
        lines.append("        }")
        lines.append("    },")
        lines.append("")
    lines.append("}")
    lines.append("")
    lines.append("")
    lines.append("def get_all_leaders(exchange_filter: str = None) -> dict:")
    lines.append("    \"\"\"")
    lines.append("    获取所有龙头股")
    lines.append("    ")
    lines.append("    Args:")
    lines.append("        exchange_filter: 过滤交易所类型 ('主板', '创业板', '科创板', None=全部)")
    lines.append("    ")
    lines.append("    Returns:")
    lines.append("        龙头股字典 {代码: 信息}")
    lines.append("    \"\"\"")
    lines.append("    all_leaders = {}")
    lines.append("    ")
    lines.append("    for sector, sector_info in HOT_SECTOR_CONFIG.items():")
    lines.append("        for sub_sector, sub_info in sector_info['sub_sectors'].items():")
    lines.append("            for code, info in sub_info['leaders'].items():")
    lines.append("                if exchange_filter is None or info['exchange'] == exchange_filter:")
    lines.append("                    all_leaders[code] = {")
    lines.append("                        **info,")
    lines.append("                        'sector': sector,")
    lines.append("                        'sub_sector': sub_sector,")
    lines.append("                        'sub_sector_desc': sub_info['desc']")
    lines.append("                    }")
    lines.append("    ")
    lines.append("    return all_leaders")
    lines.append("")
    lines.append("")
    lines.append("def get_main_board_leaders() -> dict:")
    lines.append("    \"\"\"获取主板龙头股\"\"\"")
    lines.append("    return get_all_leaders('主板')")
    lines.append("")
    lines.append("")
    lines.append("def get_stock_count() -> dict:")
    lines.append("    \"\"\"获取各板块股票数量统计\"\"\"")
    lines.append("    stats = {}")
    lines.append("    for sector, sector_info in HOT_SECTOR_CONFIG.items():")
    lines.append("        stats[sector] = {")
    lines.append("            'desc': sector_info['desc'],")
    lines.append("            'sub_sectors': {}")
    lines.append("        }")
    lines.append("        for sub_sector, sub_info in sector_info['sub_sectors'].items():")
    lines.append("            stats[sector]['sub_sectors'][sub_sector] = {")
    lines.append("                'desc': sub_info['desc'],")
    lines.append("                'count': len(sub_info['leaders'])")
    lines.append("            }")
    lines.append("    return stats")
    lines.append("")
    lines.append("")
    lines.append("def print_sector_config():")
    lines.append("    \"\"\"打印板块配置\"\"\"")
    lines.append("    stats = get_stock_count()")
    lines.append("    ")
    lines.append("    print(\"=\" * 80)")
    lines.append("    print(\"2025-2026年热门板块及子板块龙头股配置（AI硬件供应链）\")")
    lines.append("    print(\"数据时间: 2024-01-01 至 2026-06-14\")")
    lines.append("    print(\"=\" * 80)")
    lines.append("    ")
    lines.append("    total_stocks = 0")
    lines.append("    total_sub_sectors = 0")
    lines.append("    ")
    lines.append("    for sector, info in stats.items():")
    lines.append("        sub_count = len(info['sub_sectors'])")
    lines.append("        total_sub_sectors += sub_count")
    lines.append("        ")
    lines.append("        print(f\"\\n📊 {sector} - {info['desc']} ({sub_count}个子板块)\")")
    lines.append("        print(\"-\" * 60)")
    lines.append("        ")
    lines.append("        for sub_sector, sub_info in info['sub_sectors'].items():")
    lines.append("            count = sub_info['count']")
    lines.append("            total_stocks += count")
    lines.append("            leaders = list(HOT_SECTOR_CONFIG[sector]['sub_sectors'][sub_sector]['leaders'].items())")
    lines.append("            ")
    lines.append("            print(f\"  📁 {sub_sector} ({sub_info['desc']}) - {count}只\")")
    lines.append("            ")
    lines.append("            # 每行显示3只")
    lines.append("            for i in range(0, len(leaders), 3):")
    lines.append("                row = leaders[i:i+3]")
    lines.append("                line = \"    \"")
    lines.append("                for code, info in row:")
    lines.append("                    exchange_mark = \"🅰️\" if info['exchange'] == '主板' else \"🅱️\" if info['exchange'] == '创业板' else \"🅲\"")
    lines.append("                    line += f\"{exchange_mark} {code} {info['name']} | \"")
    lines.append("                print(line.rstrip(\" |\"))")
    lines.append("    ")
    lines.append("    print(\"\\n\" + \"=\" * 80)")
    lines.append("    print(f\"主板龙头股统计:\")")
    lines.append("    print(f\"  板块数量: {len(HOT_SECTOR_CONFIG)} 个\")")
    lines.append("    print(f\"  子板块数量: {total_sub_sectors} 个\")")
    lines.append("    print(f\"  主板龙头股: {len(get_main_board_leaders())} 只\")")
    lines.append("    print(\"=\" * 80)")
    lines.append("")
    lines.append("")
    lines.append("if __name__ == '__main__':")
    lines.append("    print_sector_config()")
    return "\n".join(lines)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true", help="审计后直接剔除问题股并重写配置")
    args = ap.parse_args()
    audit(apply=args.apply)
