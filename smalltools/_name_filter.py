# -*- coding: utf-8 -*-
"""名称级排除（2026-09-19 用户需求：所有 ST / *ST / 退市整理票一律剔除）。

用户口径：「所有 ST 都有剔除」——选股、订单、复盘巡诊、工具台扫描全部生效。
判定只看名称（不依赖财报/接口），离线可用、零成本：
  · 名称含 ST / *ST / S*ST / SST / PT（老三板）
  · 名称含「退」（退市整理期，如「XX退」）
注意：A股 ST 票名称必定带 ST 标记（*ST 或 ST 前缀），故名称判定可靠。
"""


def is_excluded(name):
    """名称命中 ST / 退市 → True（应剔除）。"""
    n = str(name or "").strip().upper().replace(" ", "").replace("＊", "*")
    if not n:
        return False
    if "ST" in n:            # ST / *ST / S*ST / SST
        return True
    if "PT" in n:            # 老三板（特别转让）
        return True
    if "退" in n:            # 退市整理期（名称以「退」结尾）
        return True
    return False


def excluded_reason(name):
    """命中时返回原因文案，未命中返回空串。"""
    n = str(name or "").strip().upper().replace(" ", "")
    if "ST" in n:
        return "ST/*ST 股剔除"
    if "PT" in n:
        return "PT 老三板剔除"
    if "退" in n:
        return "退市整理期剔除"
    return ""
