#!/usr/bin/env python3
"""
Chat Completions API 模型验证脚本
═══════════════════════════════════════════════

App 通过 OpenAiCompatibleProvider 发送请求到 /chat/completions
端点，与 test_api.py 测试的 /responses 端点是不同的 API。

本脚本专门测试 /chat/completions 上的模型可用性，用于：
1. 验证哪些模型名在 Chat Completions API 真正可用
2. 更新 ApiConfigManager 的 supportedModels 列表
3. 确保 App 用户看到的模型都是可用的

用法: python test_chat_models.py
python app/src/test/java/com/chin/stockanalysis/test_chat_models.py

"""
import json, os, ssl, sys, urllib.request, urllib.error

# ── 定位配置文件 ──
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, "..", "..", "..", "..", "..", "..", ".."))
CONFIG_FILE = os.path.join(PROJECT_ROOT, "api_keys_local.properties")

def load_api_keys():
    keys = {}
    if not os.path.exists(CONFIG_FILE):
        print(f"⚠️  未找到 {CONFIG_FILE}")
        return keys
    try:
        with open(CONFIG_FILE, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                if "=" in line:
                    k, v = line.split("=", 1)
                    keys[k.strip()] = v.strip()
    except Exception as e:
        print(f"⚠️  读取失败: {e}")
    return keys

API_KEYS = load_api_keys()
KEY = API_KEYS.get("DOUBAO_KEY", "")
BASE_URL = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"

# ── 候选模型 ──
CANDIDATES = {
    "豆包 Seed 2.0": [
        "doubao-seed-2-0-lite-260428",
        "doubao-seed-2-0-mini-260428",
        "doubao-seed-2-0-mini-260215",
        "doubao-seed-2-0-code-preview-260215",
    ],
    "豆包 Seed 1.x": [
        "doubao-seed-1-6-251015",
        "doubao-seed-1-8-251228",
        "doubao-seed-1-6-flash-250828",
        "doubao-seed-code-preview-251028",
    ],
    "Chat Completions 不支持（需 /responses）": [
        "deepseek-ai/DeepSeek-V3",
        "deepseek-ai/DeepSeek-R1",
        "Pro/deepseek-ai/DeepSeek-V3",
        "Qwen/Qwen2.5-72B-Instruct-128K",
    ],
}

def test_one(model: str) -> bool:
    body = {
        "model": model,
        "messages": [{"role": "user", "content": "hi"}],
        "max_tokens": 20,
        "stream": False,
    }
    req = urllib.request.Request(
        BASE_URL,
        data=json.dumps(body).encode("utf-8"),
        headers={"Authorization": f"Bearer {KEY}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=30, context=ssl.create_default_context()) as resp:
            d = json.loads(resp.read().decode("utf-8"))
            c = d.get("choices", [{}])[0].get("message", {}).get("content", "")[:40]
            print(f"  ✅ 200 — {c}")
            return True
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")[:200]
        try:
            msg = json.loads(body).get("error", {}).get("message", body)
        except Exception:
            msg = body
        print(f"  ❌ {e.code} — {msg[:100]}")
        return False
    except Exception as e:
        print(f"  ❌ {type(e).__name__}: {e}")
        return False

if __name__ == "__main__":
    print("=" * 60)
    print("  Chat Completions API 模型验证")
    print(f"  URL: {BASE_URL}")
    if not KEY:
        print("  ⚠️ DOUBAO_KEY 为空"); sys.exit(1)
    print(f"  Key: {KEY[:8]}...{KEY[-4:]}")
    print("=" * 60)

    total = passed = 0
    verified = []

    for cat, models in CANDIDATES.items():
        print(f"\n{'─' * 60}\n  📂 {cat}\n{'─' * 60}")
        for m in models:
            total += 1
            print(f"\n  [{m}]")
            if test_one(m):
                passed += 1
                verified.append(m)

    print(f"\n{'=' * 60}\n  📊 结果: ✅ {passed}/{total} 通过\n{'=' * 60}")
    if verified:
        print("\n📋 已验证通过的 Chat Completions 模型:")
        for m in verified:
            print(f'    "{m}",')