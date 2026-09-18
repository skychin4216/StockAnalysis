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
if getattr(sys, "frozen", False):
    # PyInstaller onefile：本模块被收集进 exe（顶层模块），__file__ 指向临时解压根。
    # cloud_config.json / ai_keys.properties 通过 --add-data 打包在解压根(.)，
    # 直接以 _MEIPASS 作为资源根，保证配置与密钥路径解析正确。
    ROOT = getattr(sys, "_MEIPASS", ROOT)

# AutoQuant 侧（含 cloud_config.json + secret_enc 解密），存在时优先使用其配置
AQ_CLOUD_CONFIG_CANDIDATES = (
    os.path.join(ROOT, "AutoQuant", "cloud_config.json"),
    os.path.join(ROOT, "AutoQuant", "data", "cloud_config.json"),
    os.path.join(ROOT, "AutoQuant", "cloudsync", "cloud_config.json"),
    os.path.join(ROOT, "cloud_config.json"),  # frozen：打包在解压根；源码：工程根（通常无此文件，无害）
)


def _load_master_key():
    """读取本地 ai_keys.properties 的 secret_master_key（AutoQuant/ 或工程根）。"""
    for p in (os.path.join(ROOT, "AutoQuant", "ai_keys.properties"),
              os.path.join(ROOT, "ai_keys.properties")):
        if os.path.exists(p):
            try:
                with open(p, encoding="utf-8-sig") as f:
                    for line in f:
                        line = line.strip()
                        if line.startswith("secret_master_key="):
                            return line.split("=", 1)[1].strip()
            except OSError:
                pass
    return ""


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
    """读取 COS 配置（AutoQuant cloud_config.json 优先，回退 app_config.json）。

    - 支持 cloud_config.json 的 secret_enc 密文解密（主密钥来自 ai_keys.properties）；
    - 支持环境变量 COS_* 覆盖（bucket/region/secret_id/secret_key/prefix/params_key）。
    """
    cfg = {}
    source = ""
    for path in AQ_CLOUD_CONFIG_CANDIDATES:
        if os.path.exists(path):
            try:
                with open(path, encoding="utf-8-sig") as f:
                    raw = json.load(f)
                cfg = raw.get("cloud_sync") or raw
                source = path
                break
            except (OSError, ValueError):
                continue
    if not cfg and os.path.exists(APP_CONFIG_PATH):
        try:
            with open(APP_CONFIG_PATH, encoding="utf-8") as f:
                cfg = (json.load(f).get("cloud_sync") or {})
            source = APP_CONFIG_PATH
        except (OSError, ValueError):
            pass

    # 环境变量覆盖（显式提供 COS_* 即视为启用云端同步）
    env_map = (("bucket", "COS_BUCKET"), ("region", "COS_REGION"),
               ("secret_id", "COS_SECRET_ID"), ("secret_key", "COS_SECRET_KEY"),
               ("prefix", "COS_PREFIX"), ("params_key", "COS_PARAMS_KEY"),
               ("db_key", "COS_DB_KEY"))
    env_set = False
    for k, env in env_map:
        v = os.environ.get(env)
        if v:
            cfg[k] = v
            env_set = True
    if env_set and (os.environ.get("COS_SECRET_ID") or os.environ.get("COS_SECRET_KEY")):
        cfg["enabled"] = True

    # 解密可提交的密文（secret_enc），主密钥来自本地 ai_keys.properties
    if cfg.get("secret_enc") and not cfg.get("secret_id"):
        mk = _load_master_key()
        if mk:
            try:
                from secrets_util import decrypt_payload
                plain = json.loads(decrypt_payload(cfg["secret_enc"], mk))
                cfg["secret_id"] = cfg["secret_id"] or plain.get("cloud_sync.secret_id", "")
                cfg["secret_key"] = cfg["secret_key"] or plain.get("cloud_sync.secret_key", "")
            except Exception:
                pass

    return {
        "enabled": bool(cfg.get("enabled", False)),
        "bucket": cfg.get("bucket", ""),
        "region": cfg.get("region", "ap-guangzhou"),
        "secret_id": cfg.get("secret_id", ""),
        "secret_key": cfg.get("secret_key", ""),
        "prefix": cfg.get("prefix", "stockanalysis/phone"),
        "params_key": cfg.get("params_key", "stockanalysis/params/backtest_params.json"),
        "db_key": cfg.get("db_key", "stockanalysis/db/market_data.db"),
        "config_source": source,
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
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # 禁用系统代理，直连
    try:
        with opener.open(req, timeout=timeout) as resp:
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
