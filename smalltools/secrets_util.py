# -*- coding: utf-8 -*-
"""密钥加解密工具（AES-256-GCM）。

格式：base64( nonce(12B) || ciphertext-with-tag )，与 App 端 DataConfig.kt 的
解密实现完全一致（Kotlin: AES/GCM/NoPadding, GCMParameterSpec(128, nonce)）。

- encrypt_payload(plain_text, master_key_hex) -> base64 str
- decrypt_payload(b64, master_key_hex) -> plain str

依赖 cryptography（仅在此模块延迟导入，不影响 cos_utils 等纯标准库模块）。
"""
import base64
import os


def encrypt_payload(plain_text: str, master_key_hex: str) -> str:
    """AES-256-GCM 加密，返回 base64(nonce || ct)。"""
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    key = bytes.fromhex(master_key_hex)
    if len(key) != 32:
        raise ValueError("master_key 必须为 32 字节（64 位 hex）")
    nonce = os.urandom(12)
    ct = AESGCM(key).encrypt(nonce, plain_text.encode("utf-8"), None)
    return base64.b64encode(nonce + ct).decode("ascii")


def decrypt_payload(b64: str, master_key_hex: str) -> str:
    """解密 base64(nonce || ct) 为原始字符串。密钥错误会抛异常。"""
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    key = bytes.fromhex(master_key_hex)
    raw = base64.b64decode(b64)
    nonce, ct = raw[:12], raw[12:]
    return AESGCM(key).decrypt(nonce, ct, None).decode("utf-8")
