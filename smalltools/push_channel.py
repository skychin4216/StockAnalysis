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
  push_channel.send_image(png_path, cfg)           # 图片（企微 image，≤2MB）
  push_channel.send_file(csv_path, cfg)            # 文件（企微 file，5B~20MB，如原始 CSV / 全左对齐 XLSX）
"""
import base64
import hashlib
import json
import os
import time
import urllib.parse
import urllib.request

WECOM_BASE = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send"
WECOM_IMAGE_LIMIT = 2 * 1024 * 1024  # 企业微信 image 消息单张 ≤ 2MB
WECOM_FILE_MIN = 5                   # 企业微信 file 消息 5B ~ 20MB
WECOM_FILE_MAX = 20 * 1024 * 1024
WECOM_TEXT_LIMIT = 2000  # 企业微信 text 单条上限 2048 字节，留余量按 UTF-8 字节切块
PUSH_HEADERS = {"User-Agent": "Mozilla/5.0", "Content-Type": "application/json"}


def _opener():
    """禁用系统代理的 opener（与 cos_utils / 各守护脚本一致）。"""
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


def _split_utf8(text, limit=WECOM_TEXT_LIMIT):
    """按 UTF-8 字节数切块（尽量在整行边界断开，超长单行才按字符切），
    保证单条不超企业微信长度限制，且每片可从独立行开始读。"""
    if len(text.encode("utf-8")) <= limit:
        return [text]
    chunks, cur = [], ""
    lines = text.split("\n")

    def flush():
        nonlocal cur
        if cur:
            chunks.append(cur)
            cur = ""

    def split_long_line(line):
        # 单行超限：退化为字符级切块（行内无换行）
        seg, nb = "", 0
        for ch in line:
            b = len(ch.encode("utf-8"))
            if nb + b > limit and seg:
                chunks.append(seg)
                seg, nb = "", 0
            seg += ch
            nb += b
        if seg:
            chunks.append(seg)

    for line in lines:
        cand = (cur + "\n" + line) if cur else line
        if len(cand.encode("utf-8")) <= limit:
            cur = cand
            continue
        flush()
        if len(line.encode("utf-8")) > limit:
            split_long_line(line)
        else:
            cur = line
    flush()
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
        ok = False
        for attempt in range(3):
            try:
                req = urllib.request.Request(url, data=body, headers=PUSH_HEADERS,
                                             method="POST")
                with opener.open(req, timeout=10) as r:
                    resp = json.loads(r.read().decode("utf-8"))
                errcode = resp.get("errcode")
                ok = errcode in (0, "0")
                errmsg = resp.get("errmsg", "")
            except Exception as e:  # noqa: BLE001
                ok, errmsg = False, "%s: %s" % (type(e).__name__, e)
            if ok:
                break
            print("企微机器人 通知失败:%s(%d/%d) 第%d次" % (
                errmsg, i + 1, len(chunks), attempt + 1))
            if attempt < 2:
                # 45009=群机器人限频(20条/分钟)，睡足一个窗口再重试
                time.sleep(65 if "45009" in str(errmsg) or "Too many requests" in str(errmsg) else 3)
        print("企微机器人 通知%s(%d/%d)" % (
            "成功" if ok else "失败", i + 1, len(chunks)))
        sent = sent or ok
        if len(chunks) > 1:
            time.sleep(1.0)  # 多分片时降速，避免触发群机器人限频(20条/分钟)
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


def send_image(png_path, cfg):
    """发送图片（仅企业微信群机器人 image 消息，pushplus/serverchan 不支持）。

    返回是否成功。图片必须 ≤2MB、jpg/png。
    """
    key = (cfg.get("wecom_key") or "").strip()
    if not key:
        print("未配置 wecom_key，跳过图片推送（图片仅企微机器人支持）")
        return False
    if not os.path.isfile(png_path):
        print("图片文件不存在：%s" % png_path)
        return False
    raw = open(png_path, "rb").read()
    if len(raw) > WECOM_IMAGE_LIMIT:
        print("图片 %.1f MB 超过 2MB，企微 image 消息限制" % (len(raw) / 1024 / 1024))
        return False
    base = os.path.basename(png_path)
    b64 = base64.b64encode(raw).decode()
    md5 = hashlib.md5(raw).hexdigest()
    opener = _opener()
    base = (cfg.get("wecom_url") or WECOM_BASE).rstrip("/")
    url = "%s?key=%s" % (base, urllib.parse.quote(key, safe=""))
    body = json.dumps({"msgtype": "image", "image": {"base64": b64, "md5": md5}},
                      ensure_ascii=False).encode("utf-8")
    try:
        req = urllib.request.Request(url, data=body, headers=PUSH_HEADERS, method="POST")
        with opener.open(req, timeout=20) as r:
            resp = json.loads(r.read().decode("utf-8"))
            ok = resp.get("errcode") in (0, "0")
        print("企微机器人 图片(%s) %s" % (
            base, "成功" if ok else "失败:%s" % resp.get("errmsg")))
        return ok
    except Exception as e:  # noqa: BLE001
        print("企微机器人 图片发送失败:", type(e).__name__, e)
        return False


def send_file(file_path, cfg):
    """发送文件（仅企业微信群机器人 file 消息，pushplus/serverchan 不支持）。

    流程：① 用 webhook key 上传「临时素材」换 media_id
    （`/cgi-bin/webhook/upload_media?key=..&type=file`，multipart/form-data）
    → ② 以 `msgtype=file` 发出。文件需 5B~20MB。

    返回是否成功。CSV/Excel 等原始表格文件经此推送后，微信里可直接下载。
    """
    key = (cfg.get("wecom_key") or "").strip()
    if not key:
        print("未配置 wecom_key，跳过文件推送（文件仅企微机器人支持）")
        return False
    if not os.path.isfile(file_path):
        print("文件不存在：%s" % file_path)
        return False
    size = os.path.getsize(file_path)
    if not (WECOM_FILE_MIN <= size <= WECOM_FILE_MAX):
        print("文件 %.1f KB 不在企微 file 消息允许区间(5B~20MB)" % (size / 1024.0))
        return False
    opener = _opener()
    base = (cfg.get("wecom_url") or WECOM_BASE).rstrip("/")
    upload_url = base.replace("/webhook/send", "/webhook/upload_media")
    if "/upload_media" not in upload_url:
        upload_url = "https://qyapi.weixin.qq.com/cgi-bin/webhook/upload_media"
    upload_url += "?key=%s&type=file" % urllib.parse.quote(key, safe="")
    fname = os.path.basename(file_path)
    boundary = "----StockAnalysis%s" % hashlib.md5(
        ("%s%d" % (fname, time.time())).encode("utf-8")).hexdigest()[:16]
    with open(file_path, "rb") as f:
        raw = f.read()
    body = b"".join([
        ("--%s\r\n" % boundary).encode("utf-8"),
        ('Content-Disposition: form-data; name="media"; filename="%s"\r\n'
         % fname).encode("utf-8"),
        b"Content-Type: application/octet-stream\r\n\r\n",
        raw,
        ("\r\n--%s--\r\n" % boundary).encode("utf-8"),
    ])
    media_id = None
    try:
        req = urllib.request.Request(
            upload_url, data=body, method="POST",
            headers={"User-Agent": "Mozilla/5.0",
                     "Content-Type": "multipart/form-data; boundary=%s" % boundary})
        with opener.open(req, timeout=60) as r:
            resp = json.loads(r.read().decode("utf-8"))
        if resp.get("errcode") in (0, "0") and resp.get("media_id"):
            media_id = resp["media_id"]
        else:
            print("企微素材上传失败: %s" % resp.get("errmsg"))
            return False
    except Exception as e:  # noqa: BLE001
        print("企微素材上传异常:", type(e).__name__, e)
        return False
    url = "%s?key=%s" % (base, urllib.parse.quote(key, safe=""))
    payload = json.dumps({"msgtype": "file", "file": {"media_id": media_id}},
                         ensure_ascii=False).encode("utf-8")
    try:
        req = urllib.request.Request(url, data=payload, headers=PUSH_HEADERS, method="POST")
        with opener.open(req, timeout=30) as r:
            resp = json.loads(r.read().decode("utf-8"))
            ok = resp.get("errcode") in (0, "0")
        print("企微机器人 文件(%s) %s" % (
            fname, "成功" if ok else "失败:%s" % resp.get("errmsg")))
        return ok
    except Exception as e:  # noqa: BLE001
        print("企微机器人 文件发送失败:", type(e).__name__, e)
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
