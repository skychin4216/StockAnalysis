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
import sys
import time
import urllib.parse
import urllib.request

# 2026-09-19 用户需求：URL 统一走 data/datasources.json（_sources），缺失回退硬编码
try:
    import _sources as _SRC
    WECOM_BASE = _SRC.url("wechat_webhook")
    _WECOM_MEDIA = _SRC.url("wechat_upload_media")
    _PUSHPLUS = _SRC.url("pushplus")
    _SERVERCHAN = _SRC.host("serverchan")   # 取 base（url 模板含 {sendkey}，调用处自行拼 key）
except Exception:  # noqa: BLE001
    WECOM_BASE = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send"
    _WECOM_MEDIA = "https://qyapi.weixin.qq.com/cgi-bin/webhook/upload_media"
    _PUSHPLUS = "https://www.pushplus.plus/send"
    _SERVERCHAN = "https://sctapi.ftqq.com"
WECOM_IMAGE_LIMIT = 2 * 1024 * 1024  # 企业微信 image 消息单张 ≤ 2MB
WECOM_FILE_MIN = 5                   # 企业微信 file 消息 5B ~ 20MB
WECOM_FILE_MAX = 20 * 1024 * 1024
WECOM_TEXT_LIMIT = 2000  # 企业微信 text 单条上限 2048 字节，留余量按 UTF-8 字节切块
PUSH_HEADERS = {"User-Agent": "Mozilla/5.0", "Content-Type": "application/json"}

# ── notify 配置加载（2026-09-14 密钥迁移后的统一入口）──────────────────
# wecom_key / pushplus_token / serverchan_key 等推送密钥已从
# app/src/main/assets/data/app_config.json（随 APK 明文入库，泄露面大）迁至
# AutoQuant/cloud_config.json 的 notify 段 —— 该文件 AES-256-GCM 加密入库
# （AutoQuant/_secrets.py，明文只留本地）。各脚本原先自带的 load_notify_cfg()
# 一律转发到这里，勿再各写一份。
_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # smalltools/ 上级 = 仓库根
CLOUD_CONFIG = os.path.join(_ROOT, "AutoQuant", "cloud_config.json")
CLOUD_ENC = CLOUD_CONFIG + ".enc"
_ASSET_CONFIG = os.path.join(_ROOT, "app", "src", "main", "assets", "data", "app_config.json")
_NOTIFY_CACHE = {}


def _decrypt_cloud_notify():
    """从 cloud_config.json.enc 内存解密出 notify 段（不落盘明文）。失败返回 None。"""
    aq = os.path.join(_ROOT, "AutoQuant")
    if aq not in sys.path:
        sys.path.insert(0, aq)
    import _secrets  # AutoQuant/_secrets.py（AES-256-GCM + scrypt）
    env_pw = os.environ.get(_secrets.ENV_PASS)
    if not env_pw and not _secrets.PASS_FILE.exists():
        return None  # 无口令（如 CI/换机器未恢复），静默走回退
    pw = env_pw.encode("utf-8") if env_pw else _secrets.load_passphrase(create=False)
    try:
        data = _secrets.decrypt_file("cloud_config.json", pw, write=False)
        return (json.loads(data.decode("utf-8")).get("notify")) or None
    except SystemExit as e:  # _secrets 用 SystemExit 报「口令不对/密文被篡改」
        print("cloud_config.json.enc 解密失败：%s" % e)
        return None


def load_notify_cfg():
    """加载 notify 推送配置（所有调用方的统一入口）。

    优先级：
      1. AutoQuant/cloud_config.json 明文的 notify 段（换机器后 python _secrets.py decrypt 还原）
      2. 同名 .enc 内存解密（明文不在时自动进行，无需预先 decrypt）
      3. 回退 assets app_config.json 的 notify 段（密钥已清空，仅剩 2 个 URL）
    """
    if "cfg" in _NOTIFY_CACHE:
        return _NOTIFY_CACHE["cfg"]
    cfg = None
    try:
        if os.path.isfile(CLOUD_CONFIG):
            with open(CLOUD_CONFIG, encoding="utf-8") as f:
                cfg = json.load(f).get("notify") or None
        elif os.path.isfile(CLOUD_ENC):
            cfg = _decrypt_cloud_notify()
    except (OSError, ValueError) as e:
        print("读取加密推送配置失败:", type(e).__name__, e)
    if not cfg:
        try:
            with open(_ASSET_CONFIG, encoding="utf-8") as f:
                cfg = json.load(f).get("notify") or {}
        except (OSError, ValueError):
            cfg = {}
    # 2026-09-14 修复：密钥迁移/换机器时可能先读到回退段（密钥已清空）。
    # 空密钥配置不缓存 —— cloud_config.json 迁移完成后无需重启守护即可恢复推送。
    has_key = any(cfg.get(k) for k in ("wecom_key", "pushplus_token", "serverchan_key"))
    if has_key:
        _NOTIFY_CACHE["cfg"] = cfg
    return cfg


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
            cfg.get("pushplus_url", _PUSHPLUS),
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
            cfg.get("serverchan_url", _SERVERCHAN), key,
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
        upload_url = _WECOM_MEDIA
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


def relay_push(title, content, cfg, kind="notice"):
    """P3：并联「PC → APK 中继箱」通道（best-effort，绝不影响企微结果）。

    企业微信把消息推给**人**，中继箱把消息推给**APK 本身** —— 两者职责不同，
    故并联而非替换（见 docs/bridge-relay-design.md §6.2）。
    缺 COS 配置 / relay 模块不可用 / 无已登记设备时静默跳过，绝不抛异常。
    """
    try:
        root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        rp_dir = os.path.join(root, "AutoQuant", "autoquant")
        if os.path.isdir(rp_dir) and rp_dir not in sys.path:
            sys.path.insert(0, rp_dir)
        import relay_push as _rp  # 延迟导入：无 COS 依赖时不影响企微推送
        return _rp.push(title, content, kind=kind)
    except Exception as e:  # noqa: BLE001
        print("中继推送跳过:", type(e).__name__, e)
        return 0


def push(title, content, cfg, kind="notice", relay=True):
    """统一微信推送入口。渠道顺序：企业微信机器人 > pushplus > serverchan。

    cfg 为 app_config.json 的 notify 段（dict）。返回是否至少一路发送成功。

    @param kind  推送类型，中继箱通道据此给 APK 分类（notice / candidates / intel）
    @param relay False = 只走微信通道，不写 PC→APK 中继箱

    2026-09-15 加固（「无论如何都要推送」）：三通道全失败时消息**落盘排队**
    （_records/_push_queue.jsonl，同 title 去重），之后任一次推送成功自动补发
    积压 —— 密钥迁移/网络抖动/企微限流期间消息不丢，恢复即送达。
    """
    sent = push_wecom(title, content, cfg)
    if not sent:
        sent = _push_pushplus(title, content, cfg)
    if not sent:
        sent = _push_serverchan(title, content, cfg)
    if not sent:
        print("未配置推送(notify.wecom_key / pushplus_token / serverchan_key)或通道故障，"
              "已落盘排队待补发：\n%s\n%s" % (title, content))
        _enqueue_failed(title, content, kind)
    else:
        try:
            flush_pending()
        except Exception as e:  # noqa: BLE001
            print("积压补发异常:", type(e).__name__, e)
    # P3：并联中继箱。**无论企微成功与否都要写** —— 两者是互补通道，
    # 企微失败（限流/未配置）时 APK 更要能拿到消息。
    if relay:
        relay_push(title, content, cfg, kind=kind)
    return sent


# ── 失败排队 + 自动补发（2026-09-15）────────────────────────────────────
_QUEUE_FILE = os.path.join(_ROOT, "smalltools", "_records", "_push_queue.jsonl")
_QUEUE_MAX = 100  # 队列上限，防极端情况无限膨胀（超过丢最旧）


def _enqueue_failed(title, content, kind):
    """三通道全失败的消息落盘排队（同 title 去重；队列超限丢最旧）。best-effort。"""
    try:
        os.makedirs(os.path.dirname(_QUEUE_FILE), exist_ok=True)
        pending = _read_queue()
        if any(it.get("title") == title for it in pending):
            return  # 同标题未发消息已在队列，不重复入队（守护重试会反复触发）
        pending.append({"ts": time.strftime("%Y-%m-%d %H:%M:%S"),
                        "kind": kind, "title": title, "content": content})
        _write_queue(pending[-_QUEUE_MAX:])
    except Exception as e:  # noqa: BLE001
        print("排队落盘失败:", type(e).__name__, e)


def _read_queue():
    if not os.path.isfile(_QUEUE_FILE):
        return []
    with open(_QUEUE_FILE, encoding="utf-8") as f:
        items = []
        for ln in f:
            ln = ln.strip()
            if not ln:
                continue
            try:
                items.append(json.loads(ln))
            except Exception:  # noqa: BLE001
                pass  # 坏行丢弃
        return items


def _write_queue(items):
    tmp = _QUEUE_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        for it in items:
            f.write(json.dumps(it, ensure_ascii=False) + "\n")
    os.replace(tmp, _QUEUE_FILE)


def flush_pending(max_n=10):
    """补发积压队列（通道恢复后由 push() 成功路径自动调用，也可手动调）。

    逐条尝试三通道，成功一条删一条；遇到仍失败立即停止（通道未恢复），
    避免空转。返回成功补发条数。
    """
    pending = _read_queue()
    if not pending:
        return 0
    cfg = load_notify_cfg()
    sent, remain = 0, list(pending)
    for it in pending[:max_n]:
        t, c = it.get("title", ""), it.get("content", "")
        ok = push_wecom(t, c, cfg) or _push_pushplus(t, c, cfg) or _push_serverchan(t, c, cfg)
        if ok:
            sent += 1
            remain.remove(it)
            time.sleep(1.0)  # 多条连发降速，避免企微群机器人限频(20条/分钟)
        else:
            break  # 通道仍不可用，剩余留队下次再试
    if sent or len(remain) != len(pending):
        _write_queue(remain)
    if sent:
        print("↩️ 已自动补发积压推送 %d 条（余 %d 条待通道恢复）" % (sent, len(remain)))
    return sent
