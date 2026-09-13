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

# ── 2026-09-09：气候/极端天气大事件主动关注（厄尔尼诺/拉尼娜等）──
# 用户指出"厄尔尼诺此前是告知后才挖掘，之后要主动盯——这是大事件"。
# 机制：1) 东财快讯/新浪7x24 文本关键词命中 → climate 组（先于行业消息面推送）；
#       2) NOAA ONI(厄尔尼诺3.4区)指数自动拉取（外网可达时生效、2天缓存，
#          不可达静默跳过，绝不阻塞情报主流程）。
CLIMATE_KW = ("厄尔尼诺", "拉尼娜", "ENSO", "厄尔尼", "暖冬", "冷冬", "倒春寒",
              "台风", "超强台风", "高温", "热浪", "寒潮", "暴雨", "洪涝",
              "干旱", "极端天气", "气候大会", "拉尼娜事件", "厄尔尼诺事件")
CLIMATE_TAG = {
    "厄尔尼诺": "→ 糖/棕榈油/铜矿/制冷需求(空调啤酒)，南美干旱扰动农产品",
    "拉尼娜": "→ 冷冬取暖(煤炭天然气)、农业防冻、风电增强，需防冻害题材",
    "台风": "→ 农业受灾/保险/灾后重建(基建管材)，沿海港口航运扰动",
    "高温": "→ 电力负荷/空调/啤酒饮料/冷链，农产品干旱减产",
    "热浪": "→ 电力负荷/空调/啤酒饮料/冷链，农产品干旱减产",
    "暴雨": "→ 水利/城市应急/基建管材，农业渍涝减产",
    "洪涝": "→ 水利/城市应急/基建管材，农业渍涝减产",
    "干旱": "→ 粮食/糖/棉花涨价预期，灌溉水利",
    "寒潮": "→ 煤炭/天然气取暖需求，农业防冻(蔬菜/水果)",
    "冷冬": "→ 煤炭/天然气取暖需求，羽绒服/防寒服饰",
    "暖冬": "→ 取暖能源需求弱，羽绒服库存压力",
    "倒春寒": "→ 果树花期冻害(苹果/梨/柑橘)，防冻题材",
}
CLIMATE_KW = tuple(dict.fromkeys(CLIMATE_KW))  # 去重保留顺序


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


def _clim_tag(txt):
    """给气候新闻补一个板块提示（命中多个关键词时取首个映射），无映射返回 None。"""
    for k, tag in CLIMATE_TAG.items():
        if k in txt:
            return tag
    return None


def fetch_oni_state():
    """NOAA ONI(厄尔尼诺3.4区)自动拉取 → 状态文本/None。

    数据：https://www.cpc.ncep.noaa.gov/data/indices/oni.ascii（每季滚动值）。
    外网可达时约 2 天更新一次；不可达静默返回 None（并 1 小时内不重试），
    不影响 _market_scan 主流程 —— 新闻关键词命中是保底通道。
    """
    cache = os.path.join(HERE, "_oni_state.json")
    try:
        if os.path.exists(cache):
            with open(cache, "r", encoding="utf-8") as f:
                d = json.load(f)
            if time.time() - d.get("ts", 0) < (172800 if d.get("ok") else 3600):
                return d.get("text") or None
        r = requests.get("https://www.cpc.ncep.noaa.gov/data/indices/oni.ascii",
                         timeout=8, headers=HEADERS, proxies=PROXIES)
        r.raise_for_status()
        rows = [ln.split() for ln in r.text.splitlines() if ln.strip()]
        rec = rows[-1]  # 末行 = 最新季度
        if len(rec) < 4:
            raise ValueError("oni.ascii 末行字段不足: %r" % rec)
        season, year, oni = rec[0], rec[1], float(rec[3])
        if oni >= 0.5:
            tag, hint = "厄尔尼诺", "（≥+0.5°C 连续季为事件）"
        elif oni <= -0.5:
            tag, hint = "拉尼娜", "（≤-0.5°C 连续季为事件）"
        else:
            tag, hint = "中性", "（未达 ±0.5°C 事件阈值）"
        tag_txt = CLIMATE_TAG.get("厄尔尼诺" if tag == "厄尔尼诺" else
                                  ("拉尼娜" if tag == "拉尼娜" else ""), "")
        text = ("NOAA ONI 厄尔尼诺3.4区 %s %s = %+.1f°C → %s %s %s"
                % (season, year, oni, tag, hint, tag_txt)).strip()
        with open(cache, "w", encoding="utf-8") as f:
            json.dump({"ts": time.time(), "ok": True, "text": text}, f, ensure_ascii=False)
        return text
    except Exception as e:  # noqa: BLE001（外网不通是常态，静默跳过）
        try:
            with open(cache, "w", encoding="utf-8") as f:
                json.dump({"ts": time.time(), "ok": False}, f)
        except OSError:
            pass
        return None


def collect_ext():
    """采集并归类 → 返回 dict；同时写 _news_watch_snap.json。

    2026-09-09 起气候事件(厄尔尼诺/拉尼娜/极端天气)最优先：climate 组先收，
    命中的文本不再重复进 names/world（seen 去重），保证大盘前能主动看到。
    """
    t0 = time.time()
    g100 = _east_fast(100)      # 全球（外媒财经）
    g103 = _east_fast(103)      # 要闻（美股盘面/AH公司）
    sina = _sina_live()
    macro = _east_macro_reports()

    seen = set()
    # ① 气候/极端天气（大事件优先，最先行推）
    clim = []
    for t in g100 + g103 + sina:
        if any(k in t for k in CLIMATE_KW) and not any(k in t for k in SKIP_KW) and t not in seen:
            seen.add(t)
            tg = _clim_tag(t)
            clim.append(t if not tg else t[:70] + " " + tg)
    oni = fetch_oni_state()
    if oni:
        clim.insert(0, oni)
    clim = clim[:5]
    # ② 名人·大行（驱动情绪/题材）
    names = []
    for t in g103 + sina:
        if any(k in t for k in NAME_KW) and not any(k in t for k in SKIP_KW) and t not in seen:
            seen.add(t)
            names.append(t)
    # ③ 外媒·宏观
    world = []
    for t in g100 + g103:
        if any(k in t for k in WORLD_KW) and not any(k in t for k in SKIP_KW) and t not in seen:
            seen.add(t)
            world.append(t)
    world = world[:8]
    names = names[:8]

    out = {
        "ts": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "climate": clim,
        "world": world,
        "names": names,
        "macro_reports": macro[:3],
    }
    try:
        with open(SNAP, "w", encoding="utf-8") as f:
            json.dump(out, f, ensure_ascii=False, indent=1)
    except OSError:
        pass
    print("[news_watch] 采集 %.1fs climate=%d world=%d names=%d macro=%d"
          % (time.time() - t0, len(clim), len(world), len(names), len(macro)))
    return out


if __name__ == "__main__":
    out = collect_ext()
    print("☀️ 气候·极端天气:")
    for t in out["climate"]:
        print("  -", t[:70])
    print("🌐 外媒·宏观:")
    for t in out["world"]:
        print("  -", t[:60])
    print("🗣️ 名人·大行动态:")
    for t in out["names"]:
        print("  -", t[:60])
    print("📑 券商宏观·策略:")
    for t in out["macro_reports"]:
        print("  -", t[:60])
