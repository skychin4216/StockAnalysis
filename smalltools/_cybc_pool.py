# -*- coding: utf-8 -*-
"""双创核心股票池（2026-09-16 用户需求：1705 只全池太多垃圾股，
只留「板块前N」——但不能纯市值排名，要市值+PE+潜力综合评分）。

设计（用户确认的口径）：
  ① 数据源：东财全 A 清单（f100 行业 / f115 PE(TTM) / f20 总市值 / f8 换手）
     + data/_kline_cybc.json 的 chg20（热度/潜力因子）；
  ② 剔除：ST/*ST/退市、亏损（PE(TTM)≤0）、市值缺失；只留双创（300/301/688/689）；
  ③ 综合评分（板内百分位加权，非纯市值）：
       score = 0.35×市值地位 + 0.25×PE 合理性 + 0.25×20日动量 + 0.15×换手活跃
     —— 景旺电子类（板内市值中等 + PE 不贵 + 行业景气有资金关注）可入池，
        工业富联类（市值过大 + 无热度）自然被市值地位外的动量/估值项拉不上去；
  ④ ETF 重仓保送：_etf_holdings.json 行业 ETF 前五重仓中的双创股直接入池
     （机构共识核心，弥补纯量化评分漏掉的真龙头）；
  ⑤ 每行业取前 N：成员≥20 → 前10；≥10 → 前5；<10 → 前3（子板块小则少取）。

产出 data/_cybc_pool.json（供 _cybc_cache.py --pool 维护 K 线、
回测/探针共用）。每月 1 日 daemon 自动重算（剔除新 ST/亏损、纳入次新）。

用法：
  python _cybc_pool.py              # 重算池（快，~30s）
  python _cybc_pool.py --verbose    # 打印每行业入池明细
"""
import argparse
import datetime
import json
import os
import re
import time

import requests
import _kline_store

HERE = os.path.dirname(os.path.abspath(__file__))
POOL_FILE = os.path.join(os.path.dirname(HERE), "data", "_cybc_pool.json")
CYBC_CACHE = os.path.join(HERE, "data", "_kline_cybc.json")   # 与 _cybc_cache.py 同路径
HOLDINGS_FILE = os.path.join(os.path.dirname(HERE), "data", "_etf_holdings.json")

W_SIZE, W_VAL, W_MOM, W_ACT = 0.30, 0.30, 0.20, 0.20
ST_RE = re.compile(r"ST|退")
CYBC_RE = re.compile(r"^(300|301|688|689)")
LEADER_TOPN = 3          # 板内市值前3 = 地位保送（用户口径：长飞这类龙头必入池；
                         # 纯综合分会把「跌得多的真龙头」挤出，与反抽场景矛盾）


def fetch_all_a():
    """东财全 A 清单（分页）→ [{code,name,industry,pe,mcap_yi,turnover}]。

    用 push2delay（项目标准 host，配 Referer 防断连；实时 host 会被限流
    RemoteDisconnected，见 _market_snapshot.py / _sector_fundflow.py 同配置）。
    """
    out, pn = [], 1
    sess = requests.Session()
    sess.headers.update({
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
        "Referer": "https://quote.eastmoney.com/"})
    while True:
        for _ in range(3):
            try:
                r = sess.get(
                    "https://push2delay.eastmoney.com/api/qt/clist/get",
                    params={"pn": pn, "pz": 100, "po": 1, "np": 1,
                            "ut": "bd1d9ddb04089700cf9c27f6f7426281",
                            "fltt": 2, "invt": 2, "fid": "f20",
                            "fs": "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23",
                            "fields": "f12,f13,f14,f2,f8,f20,f100,f115"},
                    timeout=12, proxies={"http": None, "https": None})
                j = r.json()
                break
            except Exception:
                time.sleep(0.6)
        else:
            break
        diff = (j.get("data") or {}).get("diff") or []
        if not diff:
            break
        for it in diff:
            code = str(it.get("f12") or "")
            name = str(it.get("f14") or "")
            if not code or not CYBC_RE.match(code):
                continue
            if ST_RE.search(name):                     # 剔 ST/退市
                continue
            try:
                pe = float(it.get("f115"))             # PE(TTM)
                mcap = float(it.get("f20")) / 1e8      # 总市值(亿)
                to = float(it.get("f8"))               # 换手率%
            except (TypeError, ValueError):
                continue
            if pe <= 0 or mcap <= 0:                   # 剔亏损/缺数据
                continue
            out.append({"code": code, "name": name,
                        "industry": str(it.get("f100") or "").strip() or "未分类",
                        "pe": pe, "mcap": mcap, "turnover": to})
        pn += 1
        if pn > 60:                                    # 5559/100≈56 页保险闸
            break
        time.sleep(0.15)
    return out


def chg20_map():
    """_kline_cybc.json → {code: 近20日涨幅%}（作潜力/热度因子）。"""
    try:
        with open(CYBC_CACHE, encoding="utf-8") as f:
            cache = json.load(f)
    except (OSError, ValueError):
        return {}
    out = {}
    for secid, ent in cache.items():
        snaps = ent.get("snaps") or []
        if len(snaps) < 21:
            continue
        c0, c1 = snaps[-21]["close"], snaps[-1]["close"]
        if c0 > 0:
            out[secid[2:]] = (c1 / c0 - 1) * 100
    return out


def etf_core_codes():
    """_etf_holdings.json 行业 ETF 前五重仓中的双创股 → {code: (name, theme)}。"""
    try:
        with open(HOLDINGS_FILE, encoding="utf-8") as f:
            funds = json.load(f).get("funds") or []
    except (OSError, ValueError):
        return {}
    out = {}
    for fu in funds:
        theme = fu.get("theme") or fu.get("name") or ""
        for s in (fu.get("top") or [])[:5]:
            code = str(s.get("code") or "")
            if CYBC_RE.match(code):
                out[code] = (s.get("name") or code, theme)
    return out


def _pct_rank(vals, v):
    """v 在 vals 中的百分位 0~1。"""
    if not vals:
        return 0.0
    return sum(1 for x in vals if x <= v) / len(vals)


def build_pool(verbose=False):
    stocks = fetch_all_a()
    chg = chg20_map()
    cores = etf_core_codes()
    if verbose:
        print("双创候选 %d 只（已剔 ST/亏损）｜ETF重仓保送 %d 只" % (len(stocks), len(cores)))

    by_ind = {}
    for s in stocks:
        s["chg20"] = chg.get(s["code"])
        by_ind.setdefault(s["industry"], []).append(s)

    pool, stats = [], []
    for ind, lst in sorted(by_ind.items(), key=lambda kv: -len(kv[1])):
        caps = [x["mcap"] for x in lst]
        tos = [x["turnover"] for x in lst]
        moms = [x["chg20"] for x in lst if x["chg20"] is not None]
        n = len(lst)
        topn = 10 if n >= 20 else (5 if n >= 10 else 3)
        for x in lst:
            size = _pct_rank(caps, x["mcap"])
            # PE 合理性：0<PE≤60 线性递减 1→0；PE>60 给 0.2（成长溢价不歧视过狠）
            val = max(0.0, 1 - x["pe"] / 60.0) if x["pe"] <= 60 else 0.2
            mom = _pct_rank(moms, x["chg20"]) if x["chg20"] is not None else 0.3
            act = _pct_rank(tos, x["turnover"])
            x["_score"] = round((W_SIZE * size + W_VAL * val
                                 + W_MOM * mom + W_ACT * act) * 100, 1)
        # 地位保送：板内市值前 3 直接入池（龙头铁律）；其余名额按综合分
        lst.sort(key=lambda x: -x["mcap"])
        leaders = lst[:min(LEADER_TOPN, topn)]
        rest = [x for x in lst if x not in leaders]
        rest.sort(key=lambda x: -x["_score"])
        picked = leaders + rest[:max(0, topn - len(leaders))]
        # ETF 重仓保送（板内评分未进前N 的机构共识核心直接补入）
        extra = [x for x in lst[topn:] if x["code"] in cores]
        for x in picked + extra:
            pool.append({"code": x["code"], "name": x["name"], "industry": ind,
                         "mcap": round(x["mcap"], 1), "pe": round(x["pe"], 1),
                         "chg20": (round(x["chg20"], 1) if x["chg20"] is not None
                                   else None),
                         "score": x["_score"],
                         "core": (x["code"] in cores) or (x in picked)})
        stats.append((ind, n, len(picked) + len(extra)))

    pool.sort(key=lambda x: -x["score"])
    out = {"updated": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
           "n": len(pool), "n_industry": len(stats),
           "weights": {"size": W_SIZE, "val": W_VAL, "mom": W_MOM, "act": W_ACT},
           "pool": pool}
    with open(POOL_FILE, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)

    print("核心池 %d 只 / %d 个行业 → %s" % (len(pool), len(stats), POOL_FILE))
    if verbose:
        for ind, n, k in stats:
            print("  %-12s %3d 成员 → 取 %d" % (ind, n, k))
        print("─" * 70)
        for p in pool[:30]:
            print("  %-14s %s  %-10s 市值%6.0f亿 PE%6.1f chg20%+6.1f%% 分%5.1f%s"
                  % (p["name"], p["code"], p["industry"], p["mcap"], p["pe"],
                     p["chg20"] or 0, p["score"], " ·ETF重仓" if p["core"] else ""))
    return out


def _secid(code):
    return ("sh" if code.startswith("6") else "sz") + code


def fetch_pool_history(beg="20080101"):
    """池内股票 + 创业板指/科创50 全历史日K → data/kline_store.json。

    数据链路（2026-09-16 实测）：东财 kline 一次全量大区间不可用、腾讯按日期
    段两年也超限 → 用 _cybc_cache.fetch_k（ifzq fqkline，640 根/段）循环前移
    直至拉完，16 线程并发。幂等增量（已有且末日≥池缓存末日的跳过）。
    供 _backtest_dip_v3.py 2008 年起长周期回测/拟合。
    """
    import sys
    from concurrent.futures import ThreadPoolExecutor, as_completed
    sys.path.insert(0, HERE)
    from _cybc_cache import fetch_k, probe_end  # noqa: E402

    HIST = _kline_store.store_path()
    with open(POOL_FILE, encoding="utf-8") as f:
        pool = json.load(f).get("pool") or []
    targets = {_secid(p["code"]): {"name": p["name"], "industry": p["industry"]}
               for p in pool}
    targets["sz399006"] = {"name": "创业板指", "industry": "指数"}
    targets["sh000688"] = {"name": "科创50", "industry": "指数"}
    cache = {}
    if os.path.isfile(HIST):
        try:
            with open(HIST, encoding="utf-8") as f:
                cache = json.load(f)
        except (OSError, ValueError):
            cache = {}
    ref_last = ""
    try:
        with open(CYBC_CACHE, encoding="utf-8") as f:
            ref_last = ((json.load(f).get("sz399006") or {})
                        .get("snaps") or [{}])[-1].get("date", "")
    except (OSError, ValueError, IndexError):
        pass
    end = probe_end()
    tasks = [s for s, m in targets.items()
             if not (cache.get(s, {}).get("snaps")
                     and (not ref_last
                          or cache[s]["snaps"][-1]["date"] >= ref_last))]
    print("全历史 %d 只（共 %d，跳过 %d）→ %s"
          % (len(tasks), len(targets), len(targets) - len(tasks), HIST), flush=True)

    def work(secid):
        # 腾讯 fqkline 的 count 是「从 end 往回数 640 根」→ 从今天往 2008 倒推：
        # 每段拉 [beg, e] 的最后 640 根，再把 e 移到首根前一天，直至触达 beg。
        got, e = [], end
        for _ in range(12):                      # 4400/640≈7 段，12 段保险闸
            s = fetch_k(secid, beg, e)
            if not s:
                break
            got.extend(s)
            first = s[0]["date"].replace("-", "")
            if first <= beg or len(s) < 640:
                break
            d0 = datetime.date.fromisoformat(s[0]["date"]) - datetime.timedelta(1)
            e = d0.strftime("%Y%m%d")
        seen, dedup = set(), []
        for x in got:
            if x["date"] not in seen:
                seen.add(x["date"])
                dedup.append(x)
        return secid, sorted(dedup, key=lambda x: x["date"])

    t0, done, fail = time.time(), 0, 0
    with ThreadPoolExecutor(max_workers=16) as tp:
        futs = {tp.submit(work, s): s for s in tasks}
        for fut in as_completed(futs):
            secid, snaps = fut.result()
            meta = targets[secid]
            if snaps:
                cache[secid] = {"name": meta["name"],
                                "industry": meta["industry"], "snaps": snaps}
                done += 1
            else:
                fail += 1
                print("  ✗ %s %s 拉取失败" % (secid, meta["name"]), flush=True)
            if (done + fail) % 50 == 0:
                _save_hist(HIST, cache)
                print("  … %d/%d（成功%d 失败%d %.0fs）"
                      % (done + fail, len(tasks), done, fail,
                         time.time() - t0), flush=True)
    _save_hist(HIST, cache)
    print("全历史缓存完成：%d 成功 / %d 失败 → %s（%.0fs）"
          % (done, fail, HIST, time.time() - t0))


def _save_hist(path, cache):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(tmp, path)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--verbose", action="store_true")
    ap.add_argument("--history", action="store_true",
                    help="拉池内全部股票+创业板指/科创50 自 2008 年起全历史日K "
                         "→ data/kline_store.json（幂等增量，回测 v3 数据源）")
    args = ap.parse_args()
    if args.history:
        fetch_pool_history()
    else:
        print(build_pool(args.verbose)["n"], "只入池")
