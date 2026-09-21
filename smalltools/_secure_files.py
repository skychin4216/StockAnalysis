# -*- coding: utf-8 -*-
"""敏感文件「选择性加密入库」工具（2026-09-21 用户需求 · Q3）

## 为什么不用 git-crypt / age
本机 `git-crypt` / `age` / `gpg` **都未安装**，且安装需管理员/包管理器。
项目本身已有 AES-256-GCM 实现（`secrets_util.py`，与 APK 端 `secrets.enc` 同格式），
主密钥走 `keystore.properties`（**不入 git**）—— 直接复用它，零新增依赖。

## 策略：`lock` / `unlock`（而不是 git filter）
`git filter`（clean/smudge）需要写 `git config`；这里改为**显式两步**，语义更清楚：

    lock    明文 → 加密为 `X.enc` 并**加入 git**；明文由 .gitignore 排除
    unlock  从 `X.enc` 解回明文到工作区（供程序正常运行）

即：**仓库里只有密文，工作区只有明文**，二者不会同时被跟踪。

## 用法
    python _secure_files.py --lock      # 加密登记表里的文件 → 生成 .enc 并 git add
    python _secure_files.py --unlock    # 从 .enc 还原明文（换机器/新克隆后跑一次）
    python _secure_files.py --status    # 查看登记表与加密状态

## 登记新文件
把路径加进下面的 `SECURE_FILES`，`--lock` 时明文会从 git 里撤下（`git rm --cached`）。
"""
from __future__ import annotations

import argparse
import base64
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import secrets_util as SU                        # noqa: E402

# ── 需要版本化但必须加密的文件（相对仓库根）──
#
# ⚠️ 重要安全前提：本工具用 `secrets_util`（AES-256-GCM）+ `keystore.properties` 的
#    SECRET_MASTER_KEY。**但同一把主密钥会被编译进 APK 的 BuildConfig**（见
#    `app/build.gradle.kts` → `DataConfig.loadSecrets()`）——也就是说：
#      只要 APK 分发出去，主密钥就等于公开，用它加密再推到公开仓库**没有意义**。
#    ⇒ 所以这里的准则不是「敏感就加密」，而是：
#        ① **真敏感**（个人实仓、真实成交）→ **根本不要入库**（gitignore 就够，见下）
#        ② **运维过程数据**（订单流水/持仓快照/子编码）→ 需要版本化时才用本工具加密
SECURE_FILES = [
    "data/_trade_orders.jsonl",        # 订单流水：需要留档时加密入库
    "data/_auto_positions.json",       # 自动交易持仓快照
    "data/_paper_account.json",        # 模拟盘账户
    "data/_pick_codes.json",           # 当日选股子编码
    # ★ 刻意不收录 data/_holdings.json（个人实仓）：它属于「真敏感」，
    #   即便加密也不该进公开仓库 —— 保持 gitignore 即可。
]

ENC_SUFFIX = ".enc"


def _enc_path(rel: str) -> str:
    return os.path.join(ROOT, rel + ENC_SUFFIX)


def _plain_path(rel: str) -> str:
    return os.path.join(ROOT, rel)


def _master_key() -> str:
    """AES 主密钥（hex）—— 来自 keystore.properties → BuildConfig（不入 git）。"""
    import cos_utils as C                            # noqa: PLC0415
    key = C._load_master_key()
    if not key:
        raise RuntimeError("未找到主密钥：请在 keystore.properties 配置 SECRET_MASTER_KEY"
                           "（与 APK BuildConfig.SECRET_MASTER_KEY 同一把）")
    return key


def lock() -> int:
    """明文 → 密文（.enc），并把明文从 git 中撤下、密文加入 git。"""
    try:
        mk = _master_key()
    except Exception as e:                           # noqa: BLE001
        print("✗", e)
        return 0
    n = 0
    for rel in SECURE_FILES:
        src = _plain_path(rel)
        if not os.path.exists(src):
            continue
        try:
            raw = open(src, "rb").read()
            if not raw.strip():
                continue
            token = SU.encrypt_payload(raw.decode("utf-8", "replace"), mk)
            dst = _enc_path(rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            with open(dst, "w", encoding="utf-8") as f:
                f.write(token)
            subprocess.run(["git", "rm", "--cached", "-q", "--ignore-unmatch", rel],
                           cwd=ROOT, check=False)
            subprocess.run(["git", "add", "-f", rel + ENC_SUFFIX], cwd=ROOT, check=False)
            print("  [锁] %-34s -> %s（%d 字节）" % (rel, rel + ENC_SUFFIX, len(raw)))
            n += 1
        except Exception as e:                       # noqa: BLE001
            print("  [失败] %-34s %s" % (rel, e))
    print("已加密 %d 个文件（明文仍在工作区、但已被 git 排除）" % n)
    return n


def unlock() -> int:
    """密文（.enc）→ 明文（供程序运行）。"""
    try:
        mk = _master_key()
    except Exception as e:                           # noqa: BLE001
        print("✗", e)
        return 0
    n = 0
    for rel in SECURE_FILES:
        src = _enc_path(rel)
        if not os.path.exists(src):
            continue
        try:
            token = open(src, encoding="utf-8").read().strip()
            plain = SU.decrypt_payload(token, mk)
            dst = _plain_path(rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            with open(dst, "w", encoding="utf-8") as f:
                f.write(plain)
            print("  [解锁] %-34s <- %s（%d 字符）" % (rel, rel + ENC_SUFFIX, len(plain)))
            n += 1
        except Exception as e:                       # noqa: BLE001
            print("  [失败] %-34s 解密失败: %s（主密钥不匹配？）" % (rel, e))
    print("已还原 %d 个文件" % n)
    return n


def status() -> None:
    print("%-36s %-10s %-10s %s" % ("文件", "明文", "密文", "git 跟踪"))
    for rel in SECURE_FILES:
        has_p = os.path.exists(_plain_path(rel))
        has_e = os.path.exists(_enc_path(rel))
        tracked = subprocess.run(["git", "ls-files", "--error-unmatch", rel],
                                 cwd=ROOT, capture_output=True, text=True).returncode == 0
        enc_tracked = subprocess.run(["git", "ls-files", "--error-unmatch", rel + ENC_SUFFIX],
                                     cwd=ROOT, capture_output=True, text=True).returncode == 0
        print("%-36s %-10s %-10s %s" % (
            rel,
            "有" if has_p else "-",
            "有" if has_e else "-",
            ("⚠明文被跟踪" if tracked else "") + ("密文✓" if enc_tracked else "")))
    print("\n提示：明文应始终为「不被跟踪」；密文（.enc）才入库。")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="敏感文件选择性加密入库")
    ap.add_argument("--lock", action="store_true", help="加密并入库")
    ap.add_argument("--unlock", action="store_true", help="解密还原到工作区")
    ap.add_argument("--status", action="store_true", help="查看状态")
    a = ap.parse_args()
    if a.lock:
        sys.exit(0 if lock() >= 0 else 1)
    elif a.unlock:
        sys.exit(0 if unlock() >= 0 else 1)
    else:
        status()
