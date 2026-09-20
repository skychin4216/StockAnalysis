# -*- coding: utf-8 -*-
"""统一数据源入口（2026-09-19 用户需求：所有链接集中到 JSON，统一调用）。

单一事实源：`data/datasources.json`（PC/exe 走 _data_root；APK 副本见 --sync-apk）。
用法：
    import _sources
    _sources.url("tencent_qt", codes="sh600188,sz300308")     # 取（已格式化）URL
    r = _sources.get("tencent_kline", params={...})           # 请求（自动 UA/Referer/直连/重试）
    txt, src = _sources.get_text("tencent_kline", params=...)  # 带 fallback 链
    _sources.host("east_kline")                                # 主主机（含 https://）
    _sources.alt_hosts("east_kline")                           # 备用主机列表

命令行：
    python _sources.py                 # 列出所有源
    python _sources.py --sync-apk      # 生成 APK 副本 app/src/main/assets/datasources.json
    python _sources.py --check tencent_qt sina_kline   # 连通性自检
"""
import argparse
import json
import os
import re
import sys
import time

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
if HERE not in sys.path:
    sys.path.insert(0, HERE)

PROXIES = {"http": None, "https": None}      # 项目惯例：直连，不读系统代理
_CFG = None
_CFG_MTIME = 0.0
APK_COPY = os.path.join(ROOT, "app", "src", "main", "assets", "datasources.json")


def cfg_path():
    """配置文件路径：优先 data/datasources.json（_data_root 双态），回退 smalltools/。"""
    try:
        import _data_root
        p = os.path.join(_data_root.data_dir(), "datasources.json")
        if os.path.exists(p):
            return p
    except Exception:
        pass
    return os.path.join(ROOT, "data", "datasources.json")


def load(reload=False):
    """读取配置（mtime 缓存）。配置缺失返回 {'sources': {}}。"""
    global _CFG, _CFG_MTIME
    p = cfg_path()
    try:
        mt = os.path.getmtime(p)
    except OSError:
        return _CFG or {"sources": {}, "defaults": {}}
    if _CFG is not None and not reload and _CFG_MTIME == mt:
        return _CFG
    try:
        with open(p, encoding="utf-8") as f:
            _CFG = json.load(f)
        _CFG_MTIME = mt
    except (OSError, ValueError) as e:
        print("[_sources] 读取 %s 失败: %r" % (p, e), file=sys.stderr)
        _CFG = _CFG or {"sources": {}, "defaults": {}}
    return _CFG


def _src(name):
    s = (load().get("sources") or {}).get(name)
    if not s:
        raise KeyError("未定义的源: %s（请加到 data/datasources.json）" % name)
    return s


def _defaults():
    return load().get("defaults") or {}


def names():
    return sorted((load().get("sources") or {}).keys())


def url(name, **fmt):
    """取源的 URL（支持 {占位} 格式化）。"""
    u = _src(name).get("url") or ""
    return u.format(**fmt) if fmt else u


def host(name):
    """源的主机（含协议），用于拼 URL 或签名 host 头。"""
    u = url(name)
    i = u.find("//")
    j = u.find("/", i + 2) if i >= 0 else -1
    return u[:j] if j > 0 else u


def alt_hosts(name):
    """备用主机列表（含主主机，去重）。"""
    s = _src(name)
    out = [host(name)]
    for h in (s.get("alt_hosts") or []):
        if h not in out:
            out.append(h)
    return out


def headers(name, extra=None):
    """请求头：默认 UA + 源定义的 Referer + 调用方补充。"""
    s = _src(name)
    d = _defaults()
    h = {"User-Agent": d.get("ua") or "Mozilla/5.0"}
    ref = s.get("referer")
    if ref:
        h["Referer"] = ref
    if extra:
        h.update(extra)
    return h


def get(name, params=None, fmt=None, headers_extra=None, timeout=None,
        encoding=None, retries=None, session=None):
    """GET 请求（不做 fallback）。返回 requests.Response；失败抛异常。"""
    s = _src(name)
    d = _defaults()
    u = url(name, **(fmt or {}))
    t = timeout if timeout is not None else (s.get("timeout") or d.get("timeout") or 12)
    n = retries if retries is not None else (s.get("retries") or d.get("retries") or 2)
    cli = session or requests
    last = None
    for _try in range(max(1, int(n))):
        try:
            r = cli.get(u, params=params, timeout=t,
                        headers=headers(name, headers_extra), proxies=PROXIES)
            if encoding:
                r.encoding = encoding
            elif s.get("encoding"):
                r.encoding = s["encoding"]
            return r
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(0.5)
    raise last if last else RuntimeError("GET %s 失败" % name)


def post(name, data=None, json_body=None, headers_extra=None, timeout=None,
         retries=None, session=None):
    """POST 请求（推送类源约定）。"""
    s = _src(name)
    d = _defaults()
    u = url(name)
    t = timeout if timeout is not None else (s.get("timeout") or d.get("timeout") or 12)
    n = retries if retries is not None else (s.get("retries") or d.get("retries") or 1)
    cli = session or requests
    last = None
    for _try in range(max(1, int(n))):
        try:
            return cli.post(u, data=data, json=json_body, timeout=t,
                            headers=headers(name, headers_extra), proxies=PROXIES)
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(0.5)
    raise last if last else RuntimeError("POST %s 失败" % name)


def get_text(name, params=None, fmt=None, headers_extra=None, timeout=None,
             encoding=None, session=None):
    """按 fallback 链取文本：返回 (text, used_source)；全失败返回 ("", name)。"""
    chain = [name] + list(_src(name).get("fallbacks") or [])
    for i, nm in enumerate(chain):
        try:
            r = get(nm, params=params if i == 0 else _adapt_params(nm, params),
                    fmt=fmt if i == 0 else None, headers_extra=headers_extra,
                    timeout=timeout, encoding=encoding or None, session=session)
            if r is not None and r.status_code == 200 and r.text.strip():
                return r.text, nm
        except Exception:  # noqa: BLE001
            continue
    return "", name


def _adapt_params(name, params):
    """fallback 源参数适配：东财 ↔ 腾讯/新浪 的字段名不同，这里做最小映射。

    目前仅覆盖日K场景（secid/param/symbol），其余场景 fallback 层自行处理。
    """
    if not params:
        return params
    p = dict(params)
    if "secid" in p and name.startswith("sina"):
        secid = str(p["secid"])
        if "." in secid:
            mkt, code = secid.split(".", 1)
            p = {"symbol": ("sh" if mkt == "1" else "sz") + code,
                 "scale": "240", "ma": "no",
                 "datalen": str(min(1000, int(p.get("lmt") or 300)))}
    elif "symbol" in p and name.startswith(("tencent", "east")):
        sym = str(p["symbol"])
        p = {"param": "%s,day,2020-01-01,2099-12-31,%s,qfq"
             % (sym, min(1000, int(p.get("datalen") or 300)))}
    return p


_HOST_MAP = None


def _host_map():
    """域名 → 配置主机（含 alt_hosts）。供 norm() 把历史硬编码 URL 规范化。"""
    global _HOST_MAP
    if _HOST_MAP is None:
        m = {}
        for s in (load().get("sources") or {}).values():
            for u in [s.get("url")] + list(s.get("alt_hosts") or []):
                if not u or "//" not in u:
                    continue
                scheme, rest = u.split("//", 1)
                h = rest.split("/")[0]
                m.setdefault(h, "%s//%s" % (scheme, h))
        _HOST_MAP = m
    return _HOST_MAP


def norm(url):
    """把硬编码 URL 的主机换成配置里的对应主机（同域名族），失败原样返回。

    渐进式迁移用：历史文件**不必**逐处改成 url("name")，只要让 URL 过一道
    `_sources.norm(u)` 即可获得「改配置即改主机」的能力（push2his→90 分片等）。
    """
    try:
        if not url or "//" not in url:
            return url
        rest = url.split("//", 1)[1]
        host = rest.split("/")[0]
        want = _host_map().get(host)
        if want:
            tail = rest.split("/", 1)[1] if "/" in rest else ""
            return "%s/%s" % (want, tail)
    except Exception:  # noqa: BLE001
        pass
    return url


KOTLIN_COPY = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "java", "com", "chin", "stockanalysis",
    "strategy", "data", "DataSourceConfig.kt")


def sync_kotlin():
    """由 datasources.json 生成 Android 端 Kotlin 常量表（2026-09-19 用户需求）。

    Android 读 assets JSON 需 Context 且有运行时开销；改为**生成 object 常量表**，
    URL 变更只需重跑本函数 + 重新编译，零运行时依赖、无 Context 要求。
    """
    cfg = load()
    srcs = cfg.get("sources") or {}

    def _esc(s):
        return str(s).replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")

    out = ["package com.chin.stockanalysis.strategy.data", "",
           "import com.chin.stockanalysis.config.DataConfig",
           "",
           "// 自动生成，请勿手改：python smalltools/_sources.py --sync-apk",
           "// 来源：data/datasources.json（updated=%s）" % (cfg.get("updated") or ""),
           "// 覆盖优先级：app_config.json 的 \"ds.<name>\" > 本常量表 > 调用方 fallback",
           "", "object DataSourceConfig {", "", "    private val URLS = mapOf("]
    for k in sorted(srcs):
        out.append('        "%s" to "%s",' % (k, _esc(srcs[k].get("url") or "")))
    out += ["    )", "", "    private val HOSTS = mapOf("]
    for k in sorted(srcs):
        u = srcs[k].get("url") or ""
        if "//" not in u:
            continue
        scheme, rest = u.split("//", 1)
        out.append('        "%s" to "%s//%s",' % (k, scheme, rest.split("/")[0]))
    out += ["    )", "", "    private val ALT_HOSTS = mapOf("]
    for k in sorted(srcs):
        hs = srcs[k].get("alt_hosts") or []
        if hs:
            out.append('        "%s" to listOf(%s),'
                       % (k, ", ".join('"%s"' % _esc(h) for h in hs)))
    out += ["    )", "", """    /** 取源 URL；fmt 替换 {占位符}（如 {codes}）。未登记返回 ""。
     *  运行时可用 assets/data/app_config.json 的 "ds.<name>" 覆盖（无需改代码）。 */
    fun url(name: String, vararg fmt: Pair<String, String>): String {
        var u = runCatching { DataConfig.getOrNull("ds.$name") }.getOrNull()
            ?: URLS[name] ?: return ""
        for ((k, v) in fmt) u = u.replace("{$k}", v)
        return u
    }

    /** 未登记时回退 fallback（渐进式迁移用），fmt 同样作用于两者。 */
    fun urlOr(name: String, fallback: String, vararg fmt: Pair<String, String>): String {
        val u = url(name, *fmt)
        if (u.isNotEmpty()) return u
        var f = fallback
        for ((k, v) in fmt) f = f.replace("{$k}", v)
        return f
    }

    /** 协议+主机（如 https://qt.gtimg.cn）。未登记返回 ""。 */
    fun host(name: String): String = HOSTS[name] ?: ""

    /** 多主机备选列表；未登记返回空表。 */
    fun altHosts(name: String): List<String> = ALT_HOSTS[name] ?: emptyList()
}
"""]
    text = "\n".join(out)
    os.makedirs(os.path.dirname(KOTLIN_COPY), exist_ok=True)
    with open(KOTLIN_COPY, "w", encoding="utf-8") as f:
        f.write(text)
    return KOTLIN_COPY


def sync_apk():
    """把配置复制到 APK assets，并生成 Kotlin 常量表。返回 assets 目标路径。"""
    src = cfg_path()
    with open(src, encoding="utf-8") as f:
        raw = f.read()
    os.makedirs(os.path.dirname(APK_COPY), exist_ok=True)
    with open(APK_COPY, "w", encoding="utf-8") as f:
        f.write(raw)
    try:
        sync_kotlin()
        print("已生成 Kotlin 常量表: %s" % KOTLIN_COPY)
    except Exception as e:  # noqa: BLE001
        print("⚠️ Kotlin 常量表生成失败: %s" % e)
    return APK_COPY


def _check(names_):
    import requests
    ok = 0
    for nm in names_:
        try:
            s = _src(nm)
            u = url(nm)
            need_fmt = "{" in u
            if need_fmt:
                print("  %-22s 跳过（含占位符，需参数）：%s" % (nm, u))
                continue
            h = headers(nm)
            r = requests.get(u, timeout=8, headers=h, proxies=PROXIES)
            enc = s.get("encoding")
            if enc:
                r.encoding = enc
            print("  %-22s HTTP %s  %d 字节  %s" % (nm, r.status_code, len(r.text),
                                                   r.text[:60].replace("\n", " ")))
            ok += 1
        except Exception as e:  # noqa: BLE001
            print("  %-22s 失败 %s: %s" % (nm, type(e).__name__, str(e)[:70]))
    print("自检完成：%d/%d 可直连" % (ok, len(names_)))


def main():
    ap = argparse.ArgumentParser(description="统一数据源入口")
    ap.add_argument("--sync-apk", action="store_true", help="生成 APK assets 副本")
    ap.add_argument("--check", nargs="*", help="连通性自检（默认全部仅主机类源）")
    args = ap.parse_args()
    if args.sync_apk:
        print("已同步 APK 副本:", sync_apk())
        return
    if args.check is not None:
        _check(args.check or [n for n in names()])
        return
    cfg = load()
    print("配置: %s（%d 个源，updated=%s）" % (cfg_path(), len(names()), cfg.get("updated")))
    for n in names():
        s = _src(n)
        fb = (" ← fallback: " + ",".join(s["fallbacks"])) if s.get("fallbacks") else ""
        print("  %-22s %s%s" % (n, s.get("url"), fb))


if __name__ == "__main__":
    main()
