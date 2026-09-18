# -*- coding: utf-8 -*-
"""从本地明文密钥生成加密密文（可提交 git）。

读取：
  - keystore.properties（本地，含 tencent.secretId/secretKey + secret.masterKey）

输出：
  1. app/src/main/assets/data/secrets.enc
     base64( nonce || AES-GCM(json{"cloud_sync.secret_id":...,"cloud_sync.secret_key":...}) )
     App 端 DataConfig 启动时解密后覆盖 app_config.json 的同名空字段。
  2. AutoQuant/cloud_config.json
     secret_id/secret_key 置空，新增 secret_enc 字段（exe 端解密使用）。

用法：python smalltools/_encrypt_secrets.py
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "AutoQuant"))
from smalltools.secrets_util import encrypt_payload
KEYSTORE = os.path.join(ROOT, "keystore.properties")
SECRETS_ENC = os.path.join(ROOT, "app", "src", "main", "assets", "data", "secrets.enc")
EXE_CFG = os.path.join(ROOT, "AutoQuant", "cloud_config.json")


def _load_props(path):
    props = {}
    with open(path, encoding="utf-8-sig") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, _, v = line.partition("=")
            props[k.strip()] = v.strip()
    return props


def main():
    props = _load_props(KEYSTORE)
    sid = props.get("tencent.secretId", "").strip()
    sk = props.get("tencent.secretKey", "").strip()
    mk = props.get("secret.masterKey", "").strip()
    if not (sid and sk and mk):
        print("[FAIL] keystore.properties 缺少 tencent.secretId/tencent.secretKey/secret.masterKey")
        sys.exit(1)

    payload = json.dumps({
        "cloud_sync.secret_id": sid,
        "cloud_sync.secret_key": sk,
    }, ensure_ascii=False, separators=(",", ":"))
    enc = encrypt_payload(payload, mk)

    with open(SECRETS_ENC, "w", encoding="ascii") as f:
        f.write(enc)
    print("[OK] App secrets.enc ->", SECRETS_ENC, "len=", len(enc))

    if os.path.exists(EXE_CFG):
        with open(EXE_CFG, encoding="utf-8-sig") as f:
            cfg = json.load(f)
        cs = cfg.setdefault("cloud_sync", {})
        cs["secret_id"] = ""
        cs["secret_key"] = ""
        cs["secret_enc"] = enc
        with open(EXE_CFG, "w", encoding="utf-8") as f:
            json.dump(cfg, f, ensure_ascii=False, indent=2)
        print("[OK] exe cloud_config.json updated")
    else:
        print("[WARN] not found:", EXE_CFG)
    print("[DONE]")


if __name__ == "__main__":
    main()
