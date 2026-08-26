# -*- coding: utf-8 -*-
import re

def eval_parse(expr):
    m = re.search(r'\$\{([^}]+)\}(?:\.([A-Za-z_][A-Za-z0-9_]*))?', expr)
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

cases = [
    "${n_adaptive}.direction == 'BULLISH'",
    "${n_adaptive}.direction == 'OSCILLATION'",
    "${n_adaptive.direction} == 'BEARISH'",
    "${n_style_rotation}.suggestedPeriod == 'ultra_short'",
    "${n_adaptive} == 'x'",
]
for c in cases:
    print(f'{c!r:60s} -> {eval_parse(c)}')
