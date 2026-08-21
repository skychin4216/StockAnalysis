# -*- coding: utf-8 -*-
"""
多日回测脚本：复刻 StockCheckPipeline.analyzeSnaps，
对 LeaderStockPool 核心股票池（可扩展候选池）在最近几个交易日逐日扫描，
输出每天每个周期应选到的股票。

口径与 backtest_guangmo.py 完全一致：
- 四周期参数（超短/短/中/长）与 Kotlin companion 一致
- changePct 使用正确口径（腾讯真实涨跌幅 / 东财 f59）
- 大盘方向：每天用「截至当日收盘」的三指数 MA5/10/20 方向 tripleVote
- 逐股用「截至当日」的历史K线跑 analyze_snaps

用法：
    python _multiday_backtest.py > out_multiday.txt 2> err_multiday.txt
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from backtest_guangmo import (
    analyze_snaps, PARAMS, fetch_kline, get_index_dir, triple_vote,
    parse_market_regime,
)

# 待扫描交易日（含8/13实时日K，若当日未收盘则用最后可用K线）
SCAN_DATES = ["20260811", "20260812", "20260813"]

# 大盘三指数
INDEXES = ["sh000001", "sz399001", "sz399006"]

# ───────────────── 核心股票池（与 LeaderStockPool.getMainlineCodes 一致） ─────────────────
# 从 DEFAULT_CONFIGS 的所有产业主线（非概念）板块提取
LEADER_STOCKS = sorted(set([
    # 稀缺小金属
    "sh603993", "sz000657", "sh600549", "sh603399", "sz000960", "sh600497",
    "sh600111", "sz000758", "sh600392", "sh600101", "sz000629", "sh600117",
    "sz002149", "sz000545", "sh600456", "sh601600",
    # 有色金属
    "sz002460", "sz002466", "sh600338", "sh600362", "sz000630", "sh601899",
    "sz000807", "sh600219",
    # 半导体
    "sh688981", "sz002371", "sh603501", "sh688396", "sz300661", "sh688256",
    "sh688036", "sz002156", "sh600584", "sh688012", "sz002049", "sh688008",
    "sh603290", "sz300782", "sh688521", "sz300236", "sh603690",
    "sz300576", "sh603650", "sz002372",
    # AI算力
    "sh600536", "sh688111", "sz300502", "sh688041", "sz002230", "sh603019",
    "sz000063", "sh601138", "sz002463",
    # 光通信
    "sz300394", "sz300308", "sh600498", "sh601869", "sh600105", "sh600487",
    "sh600703", "sh688313", "sz002281", "sh600745", "sz002475",
    # PCB
    "sz002916", "sz002815", "sh603228", "sz002384", "sh603920", "sz300476",
    "sh603989", "sz002579", "sh688036", "sh600176", "sh600801", "sh600183",
    "sh603799", "sz002902", "sz002340",
    # 电网设备
    "sh601700", "sh600406", "sh600312", "sh600131", "sz002339", "sh600517",
    "sz300763", "sz300274", "sz300118", "sh600089", "sh600875", "sz002168",
    "sh600973", "sh600522", "sh603618",
    # 氦气
    "sh600378", "sh600746", "sh600028", "sz002430", "sh600160",
    # 新能源
    "sz300750", "sh601012", "sh600438", "sz002459", "sh600900", "sh600025",
    "sh600011", "sh601615", "sz002202", "sh600416",
    # 存储
    "sh688256", "sz002036", "sz002055", "sh603160",
]))

# 日K缓存：secid -> {"name":..., "snaps":[...]}，一次拉取多日复用
KLINE_CACHE = {}
CACHE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_kline_cache.json")


def get_kline_cached(secid, beg="20250101", end="20260813"):
    if secid in KLINE_CACHE:
        return KLINE_CACHE[secid]["name"], KLINE_CACHE[secid]["snaps"]
    name, snaps, src = fetch_kline(secid, beg, end)
    if not snaps:
        KLINE_CACHE[secid] = {"name": secid, "snaps": [], "src": "none"}
        return None, []
    KLINE_CACHE[secid] = {"name": name, "snaps": snaps, "src": src}
    time.sleep(0.05)  # 轻微限速防封
    return name, snaps


def save_cache():
    """原子写缓存：先写临时文件再替换，避免截断损坏"""
    try:
        tmp = CACHE_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(KLINE_CACHE, f, ensure_ascii=False)
        os.replace(tmp, CACHE_FILE)
    except Exception as e:
        print(f"  缓存保存失败: {e}")


def load_cache():
    global KLINE_CACHE
    if os.path.exists(CACHE_FILE):
        try:
            with open(CACHE_FILE, "r", encoding="utf-8") as f:
                KLINE_CACHE = json.load(f)
        except Exception:
            KLINE_CACHE = {}


def filter_asof(snaps, asof_date):
    """返回 <= asof_date 的K线（保留顺序）"""
    return [s for s in snaps if s["date"] <= asof_date]


def market_dir_asof(idx_snaps_map, asof_date):
    """每天用截至当日收盘的三指数算 tripleVote"""
    dirs = []
    for secid in INDEXES:
        snaps = idx_snaps_map.get(secid, [])
        sub = filter_asof(snaps, asof_date)
        if len(sub) < 20:
            dirs.append("UNKNOWN")
            continue
        d = get_index_dir(None, sub)
        dirs.append(d)
    tv = triple_vote(dirs)
    return tv, dirs


LOG = []
OUT_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out_multiday.txt")


def log(msg):
    LOG.append(str(msg))
    print(msg)


def main():
    load_cache()  # 复用上次已下载的日K
    log("=" * 72)
    log(f"多日回测：扫描 {len(LEADER_STOCKS)} 只核心龙头股")
    log(f"交易日: {SCAN_DATES}")
    log(f"周期: {list(PARAMS.keys())}")
    log("=" * 72)

    # 1. 拉取三指数日K
    log("\n── 1. 拉取大盘三指数 ──")
    idx_snaps = {}
    for secid in INDEXES:
        name, snaps = get_kline_cached(secid)
        idx_snaps[secid] = snaps
        last = snaps[-1]["date"] if snaps else "无"
        log(f"  {secid} {name or ''} K线数={len(snaps)} 最新={last}")

    # 2. 预拉取核心股票日K
    log(f"\n── 2. 拉取核心股票日K（{len(LEADER_STOCKS)}只）──")
    fail_list = []
    for i, secid in enumerate(sorted(LEADER_STOCKS)):
        name, snaps = get_kline_cached(secid)
        if not snaps:
            fail_list.append(secid)
            log(f"  [{i+1}/{len(LEADER_STOCKS)}] {secid}: 拉取失败")
        else:
            log(f"  [{i+1}/{len(LEADER_STOCKS)}] {name}({secid}) K线数={len(snaps)} 最新={snaps[-1]['date']}")
    save_cache()
    if fail_list:
        log(f"\n  ⚠️ 拉取失败 {len(fail_list)} 只: {fail_list}")
    else:
        log("  ✅ 全部拉取成功")

    # 3. 逐日扫描
    log("\n" + "=" * 72)
    log("逐日扫描结果（changePct 为正确口径）")
    log("=" * 72)

    # 失败原因统计（用于诊断为何无选股）
    from collections import Counter
    fail_counter = Counter()

    for asof in SCAN_DATES:
        log(f"\n{'#'*72}")
        log(f"## 交易日 {asof}")
        log(f"{'#'*72}")

        # 当日大盘方向
        tv, dirs = market_dir_asof(idx_snaps, asof)
        regime = parse_market_regime(tv)
        log(f"  大盘方向: tripleVote={tv} (三指数 {dirs}) → regime={regime}")

        # 当日应选股
        for period, p in PARAMS.items():
            selected = []
            near = []  # 接近通过（passCount 排名靠前）
            for secid in sorted(LEADER_STOCKS):
                info = KLINE_CACHE.get(secid)
                if not info:
                    continue
                snaps = info["snaps"]
                name = info.get("name") or secid
                sub = filter_asof(snaps, asof)
                if len(sub) < 20:
                    continue
                sub = [dict(s) for s in sub]
                sub[-1]["name"] = name
                try:
                    # 正确口径：changePct 已是真实涨跌幅
                    r = analyze_snaps(sub, p, tv)
                except Exception as e:
                    log(f"      ⚠️ {name}({secid}) [{period}] 分析异常: {e}")
                    continue
                if r.get("passed"):
                    selected.append((name, secid, r))
                else:
                    near.append((name, secid, r))
                    # 统计失败项
                    for k in r.get("activeChecks", []):
                        if not r["checks"][k][0]:
                            fail_counter[(asof, period, k)] += 1
            if not selected:
                log(f"  [{period}] 无应选股票")
                # 输出接近通过的 Top5（passCount 最高的）
                near_sorted = sorted(near, key=lambda x: -x[2]["passCount"])[:5]
                if near_sorted:
                    log(f"      接近通过 Top{len(near_sorted)}:")
                    for name, secid, r in near_sorted:
                        fails = [k for k in r["activeChecks"] if not r["checks"][k][0]]
                        log(f"        {name}({secid}) {r['passCount']}/{r['totalChecks']} "
                            f"粘合{r['convergenceDegree']:.2f}% 跌幅{r['drawdownPct']:.1f}% "
                            f"未过:{';'.join(fails)}")
                continue
            log(f"  [{period}] 应选 {len(selected)} 只:")
            for name, secid, r in selected:
                log(f"      {name}({secid}) 通过{r['passCount']}/{r['totalChecks']} "
                    f"粘合{r['convergenceDegree']:.2f}% 跌幅{r['drawdownPct']:.1f}% "
                    f"量比{r['volumeRatio']:.2f} 涨{r['changePctReal']:.2f}%")

    # 诊断：各交易日各周期失败项统计
    log("\n" + "=" * 72)
    log("诊断：无选股原因统计（各交易日×周期，最常失败检查 Top10）")
    log("=" * 72)
    for (asof, period, chk), cnt in fail_counter.most_common(40):
        log(f"  {asof} [{period}] {chk}: {cnt} 只未过")

    save_cache()


if __name__ == "__main__":
    try:
        main()
    except Exception:
        import traceback
        log("\n[发生异常]")
        log(traceback.format_exc())
    finally:
        # 写日志到文件（不依赖 shell 重定向）
        try:
            with open(OUT_FILE, "w", encoding="utf-8") as f:
                f.write("\n".join(LOG))
            print(f"\n[已写入日志到 {OUT_FILE}]")
        except Exception as e:
            print(f"日志写文件失败: {e}")
