# -*- coding: utf-8 -*-
"""
腾讯云 COS 签名 V5 + 上传/下载 工具（纯标准库，无第三方依赖）
========================================================================
与 App 端 `CosSigner.kt` 算法完全一致（HMAC-SHA1），供 PC 端脚本复用：
- cloud_download.py   下载手机上传的数据包（ZIP → data.json）用于回溯对比
- cloud_upload_params.py  把 PC 拟合好的 backtest_params.json 上传回流

签名规则（官方文档 436/7778）：
    signKey      = HMAC-SHA1(SecretKey, KeyTime)
    HttpString   = Method\\n UriPathname \\n HttpParameters \\n HttpHeaders \\n
    StringToSign = "sha1\\n" + KeyTime + "\\n" + SHA1(HttpString) + "\\n"
    Signature    = HMAC-SHA1(signKey, StringToSign)

注意：UriPathname 中的 `/` 分隔符**不编码**；参数/头按 key 字典序，值做
RFC3986 编码（保留 -_.~）。
"""
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP_CONFIG_PATH = os.path.join(ROOT, "app", "src", "main", "assets", "data", "app_config.json")


def _url_encode(s, keep_slash=False):
    """RFC3986 编码：保留 -_.~；keep_slash=True 时保留 `/`（UriPathname 用）"""
    out = urllib.parse.quote(s, safe="-_.~")
    if keep_slash:
        out = out.replace("%2F", "/")
    return out


def _hmac_sha1_hex(key, data):
    return hmac.new(key.encode("utf-8"), data.encode("utf-8"), hashlib.sha1).hexdigest()


def _sha1_hex(data):
    return hashlib.sha1(data.encode("utf-8")).hexdigest()


def sign(secret_id, secret_key, method, uri_pathname,
         http_params=None, http_headers=None, start_time=None, end_time=None):
    """生成 COS 请求 Authorization 头。

    Args:
        method: "put" / "get"（小写）
        uri_pathname: 以 `/` 开头的对象路径，如 "/stockanalysis/phone_x.zip"
        http_params: URL 查询参数 dict（如 {"prefix": "..."}）
        http_headers: 请求头 dict（小写 key，必须含 "host"）
        start_time / end_time: Unix 秒；缺省取当前时间 / +600
    """
    http_params = {k.lower(): v for k, v in (http_params or {}).items()}
    http_headers = {k.lower(): v for k, v in (http_headers or {}).items()}
    if start_time is None:
        start_time = int(time.time())
    if end_time is None:
        end_time = start_time + 600
    key_time = "%d;%d" % (start_time, end_time)
    sign_key = _hmac_sha1_hex(secret_key, key_time)

    param_str = "&".join(
        "%s=%s" % (_url_encode(k), _url_encode(v))
        for k, v in sorted(http_params.items()))
    header_str = "&".join(
        "%s=%s" % (_url_encode(k), _url_encode(v))
        for k, v in sorted(http_headers.items()))
    http_string = "%s\n%s\n%s\n%s\n" % (
        method.lower(), _url_encode(uri_pathname, keep_slash=True),
        param_str, header_str)
    string_to_sign = "sha1\n%s\n%s\n" % (key_time, _sha1_hex(http_string))
    signature = _hmac_sha1_hex(sign_key, string_to_sign)

    header_list = ";".join(sorted(http_headers.keys()))
    param_list = ";".join(sorted(http_params.keys()))
    return ("q-sign-algorithm=sha1&q-ak=%s&q-sign-time=%s&q-key-time=%s"
            "&q-header-list=%s&q-url-param-list=%s&q-signature=%s" % (
                secret_id, key_time, key_time, header_list, param_list, signature))


def load_cloud_config():
    """从 app_config.json 的 cloud_sync 区块读取 COS 配置。"""
    with open(APP_CONFIG_PATH, encoding="utf-8") as f:
        cfg = json.load(f)
    sync = cfg.get("cloud_sync") or {}
    return {
        "enabled": bool(sync.get("enabled", False)),
        "bucket": sync.get("bucket", ""),
        "region": sync.get("region", "ap-guangzhou"),
        "secret_id": sync.get("secret_id", ""),
        "secret_key": sync.get("secret_key", ""),
        "prefix": sync.get("prefix", "stockanalysis/phone"),
        "params_key": sync.get("params_key", "stockanalysis/params/backtest_params.json"),
    }


def endpoint(host, uri_pathname, http_params=None, use_https=True):
    scheme = "https" if use_https else "http"
    query = urllib.parse.urlencode(http_params or {})
    url = "%s://%s%s" % (scheme, host, uri_pathname)
    if query:
        url += "?" + query
    return url


def request(secret_id, secret_key, bucket, region, method,
            uri_pathname, http_params=None, http_headers=None,
            body=None, timeout=60):
    """签名并发送 COS 请求，返回 (status, headers, body_bytes)。"""
    host = "%s.cos.%s.myqcloud.com" % (bucket, region)
    headers = {k.lower(): v for k, v in (http_headers or {}).items()}
    headers.setdefault("host", host)
    auth = sign(secret_id, secret_key, method, uri_pathname,
                http_params=http_params, http_headers=headers)
    url = endpoint(host, uri_pathname, http_params)
    req = urllib.request.Request(url, data=body, method=method.upper())
    req.add_header("Authorization", auth)
    for k, v in headers.items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, dict(resp.headers), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def configured(cfg):
    return bool(cfg.get("bucket") and cfg.get("region")
                and cfg.get("secret_id") and cfg.get("secret_key"))


def require_config(cfg):
    if not configured(cfg):
        print("错误：cloud_sync 未配置。请先在 app/src/main/assets/data/app_config.json 填写 bucket/secret_id/secret_key，或通过 --bucket/--secret-id/--secret-key 传入。")
        sys.exit(2)
    if not cfg.get("enabled"):
        print("警告：app_config.json 中 cloud_sync.enabled=false（配置区块已存在，仅未启用）。继续使用已填写的密钥。")
