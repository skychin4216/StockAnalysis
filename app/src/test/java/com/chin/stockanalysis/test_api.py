#!/usr/bin/env python3
"""
AI API 直连测试脚本
支持：豆包(火山引擎Ark)、硅基流动(免费)、DeepSeek 官方、阿里云 MaaS

所有 API Key 从项目根目录 api_keys_local.properties 读取，无硬编码。

用法: python test_api.py [category]
  category: doubao | siliconflow | deepseek | aliyun | all(默认)
  python test_api.py doubao     # 只测豆包
  python test_api.py all        # 测全部
  python app/src/test/java/com/chin/stockanalysis/test_api.py doubao     # 只测豆包
"""
import json, os, ssl, sys, urllib.request, urllib.error

# ═══════════════════════════════════════════════════════════════
# 定位配置文件
# ═══════════════════════════════════════════════════════════════
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
# 从 app/src/test/java/com/chin/stockanalysis/ 向上4级回到项目根目录
PROJECT_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, "..", "..", "..", "..", "..", "..", ".."))
CONFIG_FILE = os.path.join(PROJECT_ROOT, "api_keys_local.properties")


def load_api_keys():
    """从 api_keys_local.properties 读取 key=value"""
    keys = {}
    if not os.path.exists(CONFIG_FILE):
        print(f"⚠️  未找到 {CONFIG_FILE}，请先创建该文件并填入 API Key")
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
        print(f"⚠️  读取配置文件失败: {e}")
    return keys


API_KEYS = load_api_keys()


def get_key(name: str, default: str = "") -> str:
    """获取 API Key，不存在时返回 default"""
    return API_KEYS.get(name, default).strip().strip('"').strip("'")


# ═══════════════════════════════════════════════════════════════
# 模型配置（分类）
# ═══════════════════════════════════════════════════════════════

DOUBAO_CHAT = {
    "name": "豆包 Chat API",
    "base_url": "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
    "api_key": get_key("DOUBAO_KEY"),
    "format": "chat_completions",
    "models": [
        "ep-20260515024618-chcvf",
    ]
}

DOUBAO_RESPONSES = {
    "name": "豆包 Responses API",
    "base_url": "https://ark.cn-beijing.volces.com/api/v3/responses",
    "api_key": get_key("DOUBAO_KEY"),
    "format": "responses",
    "models": [
        "doubao-seed-2-0-lite-260428",
        "doubao-seed-2-0-mini-260428",
        "doubao-seed-2-0-mini-260215",
        "doubao-seed-2-0-code-preview-260215",
        "doubao-seed-1-6-251015",
        "doubao-seed-1-8-251228",
        "doubao-seed-1-6-flash-250828",
        "doubao-seed-1-6-vision-250815",
        "doubao-seed-code-preview-251028",
    ]
}

DOUBAO_THIRD_PARTY = {
    "name": "火山引擎-第三方模型",
    "base_url": "https://ark.cn-beijing.volces.com/api/v3/responses",
    "api_key": get_key("DOUBAO_KEY"),
    "format": "responses",
    "models": [
        "deepseek-v3-2-251201",
        "deepseek-v3-1-terminus",
        "glm-4-7-251222",
        "kimi-k2-thinking-251104",
    ]
}

SILICONFLOW = {
    "name": "硅基流动",
    "base_url": "https://api.siliconflow.cn/v1/chat/completions",
    "api_key": get_key("SILICONFLOW_KEY"),
    "format": "chat_completions",
    "models": [
        "Pro/deepseek-ai/DeepSeek-V3",
        "deepseek-ai/DeepSeek-V3",
        "deepseek-ai/DeepSeek-R1",
        "Qwen/Qwen2.5-72B-Instruct-128K",
    ]
}

DEEPSEEK = {
    "name": "DeepSeek 官方",
    "base_url": "https://api.deepseek.com/v1/chat/completions",
    "api_key": get_key("DEEPSEEK_KEY"),
    "format": "chat_completions",
    "models": [
        "deepseek-chat",
    ]
}

ALIYUN_MAAS = {
    "name": "阿里云 MaaS",
    "base_url": "https://llm-kowojoaryb0hq5ik.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions",
    "api_key": get_key("ALIYUN_MAAS_KEY"),
    "format": "chat_completions",
    "models": [
        "qwen-max",
        "qwen-plus",
        "qwen-turbo",
    ]
}

# ═══════════════════════════════════════════════════════════════
# 测试方法
# ═══════════════════════════════════════════════════════════════

def test_responses_api(base_url: str, api_key: str, model: str):
    body = {
        "model": model,
        "input": [{
            "role": "user",
            "content": [{"type": "input_text", "text": "你好，请用一句话介绍你自己。"}]
        }]
    }
    req = urllib.request.Request(
        base_url,
        data=json.dumps(body).encode("utf-8"),
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        method="POST"
    )
    try:
        ctx = ssl.create_default_context()
        with urllib.request.urlopen(req, timeout=60, context=ctx) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            output_text = ""
            for item in data.get("output", []):
                if item.get("type") == "message":
                    for c in item.get("content", []):
                        if c.get("type") == "output_text":
                            output_text = c.get("text", "")
                            break
            resp_id = data.get("id", "N/A")[:45]
            print(f"  ✅ HTTP 200 | ID: {resp_id}...")
            print(f"     Reply: {output_text[:100]}")
            return True
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8")
        try:
            err = json.loads(err_body)
            msg = err.get("error", {}).get("message", err_body)
        except:
            msg = err_body
        print(f"  ❌ HTTP {e.code} | {msg[:120]}")
        return False
    except Exception as e:
        print(f"  ❌ Error: {type(e).__name__}: {str(e)[:120]}")
        return False


def test_chat_completions_api(base_url: str, api_key: str, model: str):
    body = {
        "model": model,
        "messages": [
            {"role": "system", "content": "你是一个有用的AI助手。"},
            {"role": "user", "content": "你好，请用一句话介绍你自己。"}
        ],
        "temperature": 0.7,
        "max_tokens": 200,
        "stream": False
    }
    req = urllib.request.Request(
        base_url,
        data=json.dumps(body).encode("utf-8"),
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        method="POST"
    )
    try:
        ctx = ssl.create_default_context()
        with urllib.request.urlopen(req, timeout=60, context=ctx) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            content = ""
            choices = data.get("choices", [])
            if choices:
                content = choices[0].get("message", {}).get("content", "")
            resp_id = data.get("id", "N/A")[:45]
            print(f"  ✅ HTTP 200 | ID: {resp_id}...")
            print(f"     Reply: {content[:100]}")
            return True
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8")
        try:
            err = json.loads(err_body)
            msg = err.get("error", {}).get("message", err_body)
        except:
            msg = err_body
        print(f"  ❌ HTTP {e.code} | {msg[:120]}")
        return False
    except Exception as e:
        print(f"  ❌ Error: {type(e).__name__}: {str(e)[:120]}")
        return False


def test_group(cfg: dict):
    name = cfg["name"]
    base_url = cfg["base_url"]
    api_key = cfg["api_key"]
    fmt = cfg["format"]
    models = cfg["models"]

    print(f"\n{'─' * 70}")
    print(f"  📂 {name}")
    print(f"{'─' * 70}")

    if not api_key:
        print(f"  ⚠️  跳过：API Key 为空，请在 {CONFIG_FILE} 中配置")
        return 0, len(models)

    masked = api_key[:8] + "..." + api_key[-4:] if len(api_key) > 12 else "***"
    print(f"  URL:  {base_url}")
    print(f"  Key:  {masked}")
    print()

    passed = 0
    for model in models:
        print(f"  [{model}]")
        if fmt == "responses":
            ok = test_responses_api(base_url, api_key, model)
        else:
            ok = test_chat_completions_api(base_url, api_key, model)
        if ok:
            passed += 1
        print()
    return passed, len(models)


# ═══════════════════════════════════════════════════════════════
# 命令行入口
# ═══════════════════════════════════════════════════════════════

CATEGORY_MAP = {
    "doubao":       [DOUBAO_RESPONSES, DOUBAO_THIRD_PARTY, DOUBAO_CHAT],
    "siliconflow":  [SILICONFLOW],
    "deepseek":     [DEEPSEEK],
    "aliyun":       [ALIYUN_MAAS],
}

if __name__ == "__main__":
    print("=" * 70)
    print("  AI API 直连测试工具")
    print(f"  配置文件: {CONFIG_FILE}")
    print("  用法: python test_api.py [doubao|siliconflow|deepseek|aliyun|all]")
    print("=" * 70)

    target = sys.argv[1] if len(sys.argv) > 1 else "all"

    groups_to_test = []
    if target == "all":
        for g in CATEGORY_MAP.values():
            groups_to_test.extend(g)
    elif target in CATEGORY_MAP:
        groups_to_test = CATEGORY_MAP[target]
    else:
        print(f"❌ 未知分类: {target}")
        print(f"   可用: {', '.join(CATEGORY_MAP.keys())}, all")
        sys.exit(1)

    total_models = 0
    total_passed = 0

    for group in groups_to_test:
        p, t = test_group(group)
        total_passed += p
        total_models += t

    print("=" * 70)
    print(f"  📊 结果: ✅ {total_passed}/{total_models} 通过")
    print("=" * 70)