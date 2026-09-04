# -*- coding: utf-8 -*-
"""外媒·宏观·名人·券商宏观策略 新闻情报扩展（_market_scan 附加组数据源）。

2026-09-04 探针结论：外网直连（Google News / Reuters RSS）在本机不可达；
以下国内可达聚合源已覆盖用户需求：
  1. 东财全球财经快讯(column=100)：贝莱德/美商务部/欧洲央行等外媒财经
  2. 东财要闻快讯(column=103)：美股盘面（苹果/特斯拉/英伟达）、AH 股公司动态
  3. 新浪财经 7x24 直播：国际政经/科技名人（特朗普/美联储/Anthropic/OpenAI 等）
  4. 东财研报中心 qType=1：券商宏观/策略/行业报告

用法：
  python _news_watch.py                    # 采集并打印分类结果
  被 _market_scan.run_once 调用：cur["ext"] = collect_ext()
"""
import datetime
import json
import os
import re
import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import requests  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
SNAP = os.path.join(HERE, "_news_watch_snap.json")
HEADERS = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
PROXIES = {"http": None, "https": None}
TIMEOUT = 8

# 外媒/宏观快讯命中词（世界财经、货币政策、地缘金融）
WORLD_KW = ("美联储", "鲍威尔", "加息", "降息", "利率", "关税", "非农", "CPI", "通胀",
            "欧洲央行", "英国央行", "日本央行", "美债", "美元", "欧元", "贝莱德",
            "高盛", "摩根", "花旗", "美国", "欧洲", "特朗普", "白宫", "商务部",
            "纳斯达克", "标普", "道指", "原油", "黄金")
# 名人/明星公司/大行动态命中词（其消息直接驱动情绪与题材）
NAME_KW = ("马斯克", "特斯拉", "英伟达", "黄仁勋", "OpenAI", "Anthropic", "谷歌",
           "微软", "苹果", "Meta", "扎克伯格", "亚马逊", "贝佐斯", "台积电",
           "巴菲特", "比尔盖茨", "黄奇帆", "巴菲特", "花旗", "瑞银", "高盛", "摩根大通")
# 排除噪音
SKIP_KW = ("泥石流", "遇难", "武装", "袭击", "空袭", "北约", "俄罗斯国防部", "乌克兰武装")


def _get(url, params):
    r = requests.get(url, params=params, timeout=TIMEOUT, headers=HEADERS, proxies=PROXIES)
    r.raise_for_status()
    return r.json()


def _east_fast(column):
    """东财快讯列表：column=100 全球 / 103 要闻。"""
    try:
        data = _get("https://np-listapi.eastmoney.com/comm/web/getFastNewsList",
                    {"client": "web", "biz": "web_724", "fastColumn": column,
                     "sortEnd": "", "pageSize": 30, "req_trace": "1"})
        return [it.get("title") or it.get("summary") or "" for it in
                ((data.get("data") or {}).get("fastNewsList") or []) if it.get("title")]
    except Exception as e:  # noqa: BLE001
        print("[news_watch] 东财快讯 column=%s 失败: %s" % (column, e), file=sys.stderr)
        return []


def _sina_live():
    """新浪财经 7x24 直播（国际政经/科技名人浓度高）。"""
    try:
        data = _get("https://zhibo.sina.com.cn/api/zhibo/feed",
                    {"page": 1, "page_size": 40, "zhibo_id": 152, "tag_id": 0,
                     "dire": "f", "dpc": 1})
        feed = (((data.get("result") or {}).get("data") or {}).get("feed") or {}).get("list") or []
        return [(it.get("rich_text") or it.get("content") or "").strip() for it in feed]
    except Exception as e:  # noqa: BLE001
        print("[news_watch] 新浪 7x24 失败: %s" % e, file=sys.stderr)
        return []


def _east_macro_reports():
    """东财研报中心 qType=1 宏观/策略/行业报告。"""
    try:
        end = datetime.date.today()
        begin = end - datetime.timedelta(days=3)
        data = _get("https://reportapi.eastmoney.com/report/list",
                    {"industryCode": "*", "pageSize": 8, "pageNo": 1, "qType": 1,
                     "code": "*", "beginTime": begin.strftime("%Y-%m-%d"),
                     "endTime": end.strftime("%Y-%m-%d")})
        return ["%s|%s" % (it.get("orgSName") or "", (it.get("title") or "")[:40])
                for it in (data.get("data") or [])]
    except Exception as e:  # noqa: BLE001
        print("[news_watch] 东财宏观研报失败: %s" % e, file=sys.stderr)
        return []


def collect_ext():
    """采集并归类 → 返回 dict；同时写 _news_watch_snap.json。"""
    t0 = time.time()
    g100 = _east_fast(100)      # 全球（外媒财经）
    g103 = _east_fast(103)      # 要闻（美股盘面/AH公司）
    sina = _sina_live()
    macro = _east_macro_reports()

    # 名人·大行优先收（驱动情绪/题材），外媒·宏观后收并去重，避免重叠
    seen = set()
    names = []
    for t in g103 + sina:
        if any(k in t for k in NAME_KW) and not any(k in t for k in SKIP_KW) and t not in seen:
            seen.add(t)
            names.append(t)
    world = []
    for t in g100 + g103:
        if any(k in t for k in WORLD_KW) and not any(k in t for k in SKIP_KW) and t not in seen:
            seen.add(t)
            world.append(t)
    world = world[:8]
    names = names[:8]

    out = {
        "ts": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "world": world,
        "names": names,
        "macro_reports": macro[:3],
    }
    try:
        with open(SNAP, "w", encoding="utf-8") as f:
            json.dump(out, f, ensure_ascii=False, indent=1)
    except OSError:
        pass
    print("[news_watch] 采集 %.1fs world=%d names=%d macro=%d"
          % (time.time() - t0, len(world), len(names), len(macro)))
    return out


if __name__ == "__main__":
    out = collect_ext()
    print("🌐 外媒·宏观:")
    for t in out["world"]:
        print("  -", t[:60])
    print("🗣️ 名人·大行动态:")
    for t in out["names"]:
        print("  -", t[:60])
    print("📑 券商宏观·策略:")
    for t in out["macro_reports"]:
        print("  -", t[:60])
