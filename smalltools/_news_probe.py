# -*- coding: utf-8 -*-
"""临时探针：验证公告接口分页深度 + 尝试新闻接口变体。"""
import requests

HEADERS = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
           "Referer": "https://finance.eastmoney.com/"}
PROXIES = {"http": None, "https": None}


def ann_page(stock_list, page_index=1, page_size=50):
    url = "https://np-anotice-stock.eastmoney.com/api/security/ann"
    params = {"sr": "-1", "page_size": str(page_size), "page_index": str(page_index),
              "ann_type": "A", "client_source": "web", "page_number": str(page_index),
              "stock_list": stock_list}
    r = requests.get(url, params=params, headers=HEADERS, proxies=PROXIES, timeout=15)
    return r


def probe_ann_depth():
    for pi in (1, 2, 5, 10, 20, 40):
        r = ann_page("600011", pi, 50)
        try:
            d = r.json()
            lst = (d.get("data") or {}).get("list") or []
            dates = sorted(x.get("notice_date", "")[:10] for x in lst)
            n = (d.get("data") or {}).get("total_hits") or (d.get("data") or {}).get("total") or "?"
            print("page=%d → %d条, 总hits=%s, 日期 %s ~ %s"
                  % (pi, len(lst), n, dates[0] if dates else "-", dates[-1] if dates else "-"))
        except Exception as e:
            print("page=%d 解析失败: %s body=%s" % (pi, e, r.text[:200]))


def main():
    print("== 公告分页深度 ==")
    probe_ann_depth()


if __name__ == "__main__":
    main()
