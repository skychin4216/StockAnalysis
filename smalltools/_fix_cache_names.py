# -*- coding: utf-8 -*-
"""用腾讯实时接口批量解析股票名称，更新 _kline_cache.json 中的 name 字段
（修复 is_cyclical_industry 失效问题，不用重新拉K线）
"""
import json, os, re, time, sys
import requests

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

CACHE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_kline_cache.json")
HEADERS = {"User-Agent": "Mozilla/5.0"}

def load_cache():
    with open(CACHE_FILE, "r", encoding="utf-8") as f:
        return json.load(f)

def save_cache(cache):
    with open(CACHE_FILE, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False)

def fetch_names_batch(codes):
    """批量请求 qt.gtimg.cn，返回 {secid: name}"""
    # 只处理非指数（指数名称无所谓）
    stock_codes = [c for c in codes if not (c.startswith("sh000") or c.startswith("sz399"))]
    result = {}
    # 每批 60 只
    for i in range(0, len(stock_codes), 60):
        batch = stock_codes[i:i+60]
        url = "https://qt.gtimg.cn/q=" + ",".join(batch)
        for attempt in range(3):
            try:
                r = requests.get(url, timeout=10, headers=HEADERS)
                r.encoding = "gbk"
                # 逐行解析 v_xxx="51~名称~..."
                for m in re.finditer(r'v_(\w+)="([^"]*)"', r.text):
                    secid = m.group(1)
                    fields = m.group(2).split("~")
                    if len(fields) > 1 and fields[1]:
                        result[secid] = fields[1]
                break
            except Exception:
                time.sleep(0.5)
        time.sleep(0.2)
    return result

def main():
    cache = load_cache()
    codes = [k for k in cache.keys() if cache[k].get("snaps")]
    print(f"缓存中共 {len(codes)} 只有K线，开始批量解析名称...")
    names = fetch_names_batch(codes)
    updated = 0
    for secid, info in cache.items():
        # 跳过指数
        if secid.startswith("sh000") or secid.startswith("sz399"):
            continue
        name = names.get(secid)
        if name and (not info.get("name") or info.get("name") == secid or len(info.get("name","")) <= 8):
            old = info.get("name")
            info["name"] = name
            updated += 1
            print(f"  {secid}: {old!r} -> {name!r}")
    save_cache(cache)
    print(f"\n更新完成，共更新 {updated} 只名称")

if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        import traceback
        print(traceback.format_exc())
