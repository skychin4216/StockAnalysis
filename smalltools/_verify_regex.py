# -*- coding: utf-8 -*-
"""模板表达式解析正则校验（Kotlin UseCaseLoader.evalStepIf 镜像）

验证 Pipeline XML if 条件 `${nodeId}.fieldName == 'value'` 的 Python 侧解析
是否与 APK 端 `UseCaseLoader.kt` 的 evalStepIf 正则一致：

    Regex("\\$\\{([^}]+)\\}(?:\\.([A-Za-z_][A-Za-z0-9_]*))?")

兼容两种写法：
  1) 点号在花括号外：${n_adaptive}.direction   （现行写法）
  2) 点号在花括号内：${n_adaptive.direction}   （兼容旧写法）

改动 APK 侧条件解析（UseCaseLoader / PipelineXmlParser）后重跑本脚本确认镜像同步。

用法:
    python _verify_regex.py
退出码: 0 全部用例通过 / 1 有失败
"""
import re
import sys

_EXPR_RE = re.compile(r'\$\{([^}]+)\}(?:\.([A-Za-z_][A-Za-z0-9_]*))?')


def eval_parse(expr):
    """复刻 Kotlin 解析：返回 (nodeId, fieldName)；无法解析返回 None；裸节点无字段返回 ('ERR', expr)。"""
    m = _EXPR_RE.search(expr)
    if not m:
        return None
    ref = m.group(1)
    field = m.group(2) or ''
    if not field:
        dot = ref.find('.')
        if dot <= 0:
            return ('ERR', expr)
        field = ref[dot + 1:]
        ref = ref[:dot]
    return (ref, field)


# (用例, 期望结果)
CASES = [
    ("${n_adaptive}.direction == 'BULLISH'", ('n_adaptive', 'direction')),
    ("${n_adaptive}.direction == 'OSCILLATION'", ('n_adaptive', 'direction')),
    ("${n_adaptive.direction} == 'BEARISH'", ('n_adaptive', 'direction')),
    ("${n_style_rotation}.suggestedPeriod == 'ultra_short'", ('n_style_rotation', 'suggestedPeriod')),
    ("${n_adaptive} == 'x'", ('ERR', "${n_adaptive} == 'x'")),
]


def main():
    failed = 0
    for expr, expected in CASES:
        got = eval_parse(expr)
        ok = got == expected
        failed += 0 if ok else 1
        print(f"{'PASS' if ok else 'FAIL'}  {expr!r:60s} -> {got}")
    print(f'\n{len(CASES) - failed}/{len(CASES)} 通过')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
