# -*- coding: utf-8 -*-
"""微信推送统一入口（方案B：企业微信机器人优先，逐步摆脱第三方公众号依赖）。

渠道顺序：
  1. wecom_key  企业微信群机器人（本机 POST 直推，零审核，无需订阅公众号）
  2. pushplus_token（pushplus 公众号，兜底）
  3. serverchan_key（Server酱 公众号，兜底）

与各脚本原实现一致：显式禁用系统代理（urllib 默认读 IE 代理 127.0.0.1:12450，
代理软件未运行时会导致 WinError 10061）。

notify 配置（app_config.json）：
  "wecom_url":  "https://qyapi.weixin.qq.com/cgi-bin/webhook/send"
  "wecom_key":  "<企业微信群机器人 webhook key>"   # 为空则跳过本渠道
  "pushplus_url" / "pushplus_token" ...            # 兜底渠道（公众号）
  "serverchan_url" / "serverchan_key" ...          # 兜底渠道（公众号）

用法：
  import push_channel
  sent = push_channel.push(title, content, cfg)    # cfg = app_config.json 的 notify 段
"""
import json
import time
import urllib.parse
import urllib.request

WECOM_BASE = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send"
WECOM_TEXT_LIMIT = 2000  # 企业微信 text 单条上限 2048 字节，留余量按 UTF-8 字节切块
PUSH_HEADERS = {"User-Agent": "Mozilla/5.0", "Content-Type": "application/json"}


def _opener():
    """禁用系统代理的 opener（与 cos_utils / 各守护脚本一致）。"""
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


def _split_utf8(text, limit=WECOM_TEXT_LIMIT):
    """按 UTF-8 字节数切块（优先在字符边界），保证单条不超企业微信长度限制。"""
    if len(text.encode("utf-8")) <= limit:
        return [text]
    chunks, cur, cur_bytes = [], "", 0
    for ch in text:
        b = len(ch.encode("utf-8"))
        if cur_bytes + b > limit and cur:
            chunks.append(cur)
            cur, cur_bytes = "", 0
        cur += ch
        cur_bytes += b
    if cur:
        chunks.append(cur)
    return chunks or [text]


def push_wecom(title, content, cfg, opener=None):
    """企业微信群机器人直推。返回是否至少成功发送一条。"""
    key = (cfg.get("wecom_key") or "").strip()
    if not key:
        return False
    base = (cfg.get("wecom_url") or WECOM_BASE).rstrip("/")
    url = "%s?key=%s" % (base, urllib.parse.quote(key, safe=""))
    opener = opener or _opener()
    text = content if not title else "%s\n%s" % (title, content)
    chunks = _split_utf8(text)
    sent = False
    for i, chunk in enumerate(chunks):
        body = json.dumps({"msgtype": "text", "text": {"content": chunk}},
                          ensure_ascii=False).encode("utf-8")
        try:
            req = urllib.request.Request(url, data=body, headers=PUSH_HEADERS,
                                         method="POST")
            with opener.open(req, timeout=10) as r:
                resp = json.loads(r.read().decode("utf-8"))
                ok = resp.get("errcode") in (0, "0")
            print("企微机器人 通知%s(%d/%d)" % (
                "成功" if ok else "失败:%s" % resp.get("errmsg"), i + 1, len(chunks)))
            sent = sent or ok
        except Exception as e:  # noqa: BLE001
            print("企微机器人 通知失败:", type(e).__name__, e)
        if len(chunks) > 1:
            time.sleep(0.5)  # 多分片时略微降速，避免触发群机器人限频
    return sent


def _push_pushplus(title, content, cfg, opener=None):
    """pushplus 公众号渠道（兜底）。"""
    token = (cfg.get("pushplus_token") or "").strip()
    if not token:
        return False
    opener = opener or _opener()
    try:
        req = urllib.request.Request(
            cfg.get("pushplus_url", "https://www.pushplus.plus/send"),
            data=json.dumps({"token": token, "title": title, "content": content,
                             "template": "txt"}).encode("utf-8"),
            headers=PUSH_HEADERS, method="POST")
        with opener.open(req, timeout=10) as r:
            ok = json.loads(r.read().decode("utf-8")).get("code") in (200, "200")
        print("pushplus 通知 %s" % ("成功" if ok else "返回失败"))
        return ok
    except Exception as e:  # noqa: BLE001
        print("pushplus 通知失败:", type(e).__name__, e)
        return False


def _push_serverchan(title, content, cfg, opener=None):
    """Server酱 公众号渠道（兜底）。"""
    key = (cfg.get("serverchan_key") or "").strip()
    if not key:
        return False
    opener = opener or _opener()
    try:
        url = ("%s/%s.send?title=%s&desp=%s" % (
            cfg.get("serverchan_url", "https://sctapi.ftqq.com"), key,
            urllib.parse.quote(title), urllib.parse.quote(content)))
        with opener.open(url, timeout=10) as r:
            ok = json.loads(r.read().decode("utf-8")).get("code") == 0
        print("serverchan 通知 %s" % ("成功" if ok else "返回失败"))
        return ok
    except Exception as e:  # noqa: BLE001
        print("serverchan 通知失败:", type(e).__name__, e)
        return False


def push(title, content, cfg):
    """统一微信推送入口。渠道顺序：企业微信机器人 > pushplus > serverchan。

    cfg 为 app_config.json 的 notify 段（dict）。返回是否至少一路发送成功。
    """
    sent = push_wecom(title, content, cfg)
    if not sent:
        sent = _push_pushplus(title, content, cfg)
    if not sent:
        sent = _push_serverchan(title, content, cfg)
    if not sent:
        print("未配置推送(notify.wecom_key / pushplus_token / serverchan_key)，"
              "以下消息未发送：\n%s\n%s" % (title, content))
    return sent
