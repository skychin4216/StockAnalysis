# -*- coding: utf-8 -*-
"""
从 3 个基准周期 pipeline 生成 9 个「周期×环境」专属 pipeline（smalltool usecase_matrix 落地）。

生成规则：
  1. 复制基准 pipeline（ultra_short / short_term / mid_term）
  2. 按环境替换 n_strict（strict_selection）config 参数
  3. 超短：替换 n_t1sell 的 stopLossPct/takeProfitPct
  4. 短线/中线：替换 n_smart 的 minScore；中线另替换 n_direction 的 exclude
  5. 修改 DagPipeline id / name / description 尾部环境标注

参数来源：smalltools/_records/usecase_matrix.json（每格形态/确认/卖出规则）。
"""
import io
import os
import re
import sys

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.normpath(os.path.join(BASE_DIR, "..", "app", "src", "main", "assets", "usecases"))

ENV_CN = {"bullish": "牛市", "oscillation": "震荡", "bearish": "熊市"}
ENV_REF = {"bullish": "usecase_matrix 超短BULLISH", "oscillation": "usecase_matrix 超短OSC", "bearish": "usecase_matrix 超短BEARISH"}


def strict_block(config_params: list[tuple[str, str]]) -> str:
    """由 (name, value) 列表生成 <Node id="n_strict"> 完整 XML 块"""
    lines = ['    <Node id="n_strict" name="均线粘合严选" module="strict_selection">', '      <config>']
    for name, value in config_params:
        lines.append(f'        <param name="{name}" value="{value}" />')
    lines.append('      </config>')
    lines.append('    </Node>')
    return "\n".join(lines)


# ────────────────────────────────────────────────────────────────────────────
# 12 格参数表（4 周期 × 3 环境）：strict = n_strict 完整 config；t1sell/smart/direction = 局部替换
# 形态映射参考 smalltool：
#   S1 粘合严选   → convergenceThreshold 收紧 + 放量/涨幅要求 + requireThreeDayConfirm
#   S3 深跌低吸   → minDrawdownPct 放大 + requireVolumeShrink + 取消涨幅/均线要求
#   S4 K线反转    → 放宽粘合 + 低涨幅门槛 + requireThreeDayConfirm=false（吞没当日即买）
#   S5 深回踩     → minDrawdownPct 放大 + requireVolumeShrink + requireMA60Rising
# ────────────────────────────────────────────────────────────────────────────
PARAMS = {
    # ══════════ 超短（tp/sl/hold = +0.4 / -0.8 / 1）══════════
    "ultra_short_bullish": {
        "base": "ultra_short",
        "comment": "超短·牛市：S4强反转（看涨吞没+涨幅≥1%+量比≥1.5），tp=+0.4/sl=-0.8/hold=1",
        "strict": [
            ("convergenceThreshold", "3.5"),     # 放宽粘合，容纳反转形态
            ("useMA60", "true"),                  # ma60_up（S4 条件）
            ("convergenceDurationDays", "5"),
            ("convergenceDurationRatio", "0.8"),
            ("volumeBreakoutRatio", "1.5"),       # vr≥1.5
            ("minChangePct", "1.0"),              # chg≥1.0
            ("requireChangePct", "true"),
            ("minDrawdownPct", "10.0"),
            ("requireCloseAboveConvergenceTop", "false"),  # 反转形态不要求突破粘合区顶部
            ("requireOpenBelowMAs", "true"),
            ("requireThreeDayConfirm", "false"),  # 吞没当日即买
            ("swingLeftN", "5"),
            ("swingRightN", "2"),
            ("lookbackDays", "30"),
            ("minPassCount", "6"),
            ("period", "ultra_short"),
        ],
        "t1sell": {"stopLossPct": "-0.8", "takeProfitPct": "0.4"},
    },
    "ultra_short_oscillation": {
        "base": "ultra_short",
        "comment": "超短·震荡：S4K线反转（看涨吞没+涨幅≥0.5%）+ 基因/资金流/板块≥5确认，tp=+0.4/sl=-0.8/hold=1",
        "strict": [
            ("convergenceThreshold", "3.5"),
            ("useMA60", "true"),
            ("convergenceDurationDays", "5"),
            ("convergenceDurationRatio", "0.8"),
            ("volumeBreakoutRatio", "1.5"),       # vr≥1.5
            ("minChangePct", "0.5"),              # chg≥0.5
            ("requireChangePct", "true"),
            ("minDrawdownPct", "10.0"),
            ("requireCloseAboveConvergenceTop", "false"),
            ("requireOpenBelowMAs", "true"),
            ("requireThreeDayConfirm", "false"),
            ("swingLeftN", "5"),
            ("swingRightN", "2"),
            ("lookbackDays", "30"),
            ("minPassCount", "6"),
            ("period", "ultra_short"),
        ],
        "t1sell": {"stopLossPct": "-0.8", "takeProfitPct": "0.4"},
        "smart": {"minScore": "55"},              # 资金流强（mfi≥60/cmf>0 近似）
    },
    "ultra_short_bearish": {
        "base": "ultra_short",
        "comment": "超短·熊市：S1严选（粘合≤2.0+量比≥2.0+涨幅≥2.0+3日确认）+ 环境持续≥5，tp=+0.4/sl=-0.8/hold=1",
        "strict": [
            ("convergenceThreshold", "2.0"),      # spread≤2.0 严选
            ("useMA60", "true"),                  # ma60_up
            ("convergenceDurationDays", "5"),
            ("convergenceDurationRatio", "0.8"),
            ("volumeBreakoutRatio", "2.0"),       # vr≥2.0
            ("minChangePct", "2.0"),              # chg≥2.0
            ("requireChangePct", "true"),
            ("minDrawdownPct", "10.0"),
            ("requireCloseAboveConvergenceTop", "true"),
            ("requireOpenBelowMAs", "true"),
            ("requireThreeDayConfirm", "true"),   # 熊市只买确认形态
            ("swingLeftN", "5"),
            ("swingRightN", "2"),
            ("lookbackDays", "30"),
            ("minPassCount", "7"),
            ("period", "ultra_short"),
        ],
        "t1sell": {"stopLossPct": "-0.8", "takeProfitPct": "0.4"},
    },

    # ══════════ 短线（牛市/震荡 tp=+1.5/sl=-1.5/hold=3；熊市 tp=+1.5/sl=-5.0/hold=5）══════════
    "short_term_bullish": {
        "base": "short_term",
        "comment": "短线·牛市：S1严选（粘合≤2.5+量比≥2.0+涨幅≥1.5）+ 涨停基因zt20，tp=+1.5/sl=-1.5/hold=3",
        "strict": [
            ("convergenceThreshold", "2.5"),      # spread≤2.5
            ("useMA60", "true"),
            ("convergenceDurationDays", "10"),
            ("convergenceDurationRatio", "0.8"),
            ("volumeBreakoutRatio", "2.0"),       # vr≥2.0
            ("minDrawdownPct", "20.0"),
            ("minChangePct", "1.5"),              # chg≥1.5
            ("requireChangePct", "true"),
            ("requireAboveAllMAs", "true"),
            ("requireThreeDayConfirm", "true"),
            ("swingLeftN", "5"),
            ("swingRightN", "2"),
            ("lookbackDays", "60"),
            ("minPassCount", "6"),
            ("period", "short"),
        ],
        "smart": {"minScore": "55"},
    },
    "short_term_oscillation": {
        "base": "short_term",
        "comment": "短线·震荡：S4K线反转（看涨吞没+涨幅≥0.5%）+ 基因/资金流/板块≥5确认，tp=+1.5/sl=-1.5/hold=3",
        "strict": [
            ("convergenceThreshold", "3.5"),      # 放宽粘合
            ("useMA60", "true"),
            ("convergenceDurationDays", "10"),
            ("convergenceDurationRatio", "0.8"),
            ("volumeBreakoutRatio", "1.5"),
            ("minDrawdownPct", "20.0"),
            ("minChangePct", "0.5"),              # chg≥0.5
            ("requireChangePct", "true"),
            ("requireAboveAllMAs", "true"),
            ("requireThreeDayConfirm", "false"),  # 吞没当日即买
            ("swingLeftN", "5"),
            ("swingRightN", "2"),
            ("lookbackDays", "60"),
            ("minPassCount", "6"),
            ("period", "short"),
        ],
        "smart": {"minScore": "55"},
    },
    "short_term_bearish": {
        "base": "short_term",
        "comment": "短线·熊市：S3龙头深跌低吸（跌幅≥15%+缩量≤0.8+资金流>0）+ 环境持续≥3+资金流强，tp=+1.5/sl=-5.0/hold=5",
        "strict": [
            ("convergenceThreshold", "3.0"),
            ("useMA60", "false"),                 # 深跌低吸不要求 MA60
            ("convergenceDurationDays", "10"),
            ("convergenceDurationRatio", "0.8"),
            ("minDrawdownPct", "15.0"),           # dd20≤-15
            ("requireVolumeShrink", "true"),      # 缩量
            ("volumeShrinkRatio", "0.8"),         # vr≤0.8
            ("minChangePct", "0.0"),              # 不要求当日涨幅
            ("requireChangePct", "false"),
            ("requireAboveAllMAs", "false"),      # 深跌股不在均线上方
            ("requireThreeDayConfirm", "false"),
            ("swingLeftN", "5"),
            ("swingRightN", "2"),
            ("lookbackDays", "60"),
            ("minPassCount", "4"),
            ("period", "short"),
        ],
        "smart": {"minScore": "55"},              # 资金流强（cmf>0 近似）
    },

    # ══════════ 中线（tp=+3.0/sl=-8.0/hold=10）══════════
    "mid_term_bullish": {
        "base": "mid_term",
        "comment": "中线·牛市：S5深回踩严（MA60升+跌幅≥15%+缩量≤0.7+资金流>0+当日跌≤-3%），tp=+3.0/sl=-8.0/hold=10",
        "strict": [
            ("convergenceThreshold", "2.5"),
            ("useMA60", "true"),
            ("convergenceDurationDays", "15"),
            ("convergenceDurationRatio", "0.8"),
            ("minDrawdownPct", "15.0"),           # dd20≤-15
            ("requireVolumeShrink", "true"),      # 缩量
            ("volumeShrinkRatio", "0.7"),         # vr≤0.7
            ("requireMA60Rising", "true"),        # ma60_up
            ("requireAboveAllMAs", "false"),      # 回踩股不在均线上方
            ("requireThreeDayConfirm", "false"),
            ("swingLeftN", "5"),
            ("swingRightN", "2"),
            ("lookbackDays", "120"),
            ("minPassCount", "4"),
        ],
        "smart": {"minScore": "60"},              # 资金流强
        "direction": {"exclude": "DOWNTREND,OSCILLATION"},
    },
    "mid_term_oscillation": {
        "base": "mid_term",
        "comment": "中线·震荡：S1粘合突破（粘合≤4.0+量比≥1.5+涨幅≥1.0）+ 基因/资金流确认，tp=+3.0/sl=-8.0/hold=10",
        "strict": [
            ("convergenceThreshold", "4.0"),      # spread≤4.0
            ("useMA60", "true"),
            ("convergenceDurationDays", "15"),
            ("convergenceDurationRatio", "0.8"),
            ("volumeBreakoutRatio", "1.5"),       # vr≥1.5
            ("minDrawdownPct", "30.0"),
            ("minChangePct", "1.0"),              # chg≥1.0
            ("requireChangePct", "true"),
            ("requireMA60Rising", "true"),
            ("requireAboveAllMAs", "true"),
            ("requireThreeDayConfirm", "true"),
            ("swingLeftN", "5"),
            ("swingRightN", "2"),
            ("lookbackDays", "120"),
            ("minPassCount", "6"),
        ],
        "smart": {"minScore": "55"},
        "direction": {"exclude": "DOWNTREND,OSCILLATION"},
    },
    "mid_term_bearish": {
        "base": "mid_term",
        "comment": "中线·熊市：S5深回踩+资金流（MA60升+跌幅≥12%+缩量≤0.9+资金流>0+当日跌≤-4%）+ 环境持续≥3，tp=+3.0/sl=-8.0/hold=10",
        "strict": [
            ("convergenceThreshold", "2.5"),
            ("useMA60", "true"),
            ("convergenceDurationDays", "15"),
            ("convergenceDurationRatio", "0.8"),
            ("minDrawdownPct", "12.0"),           # dd20≤-12
            ("requireVolumeShrink", "true"),      # 缩量
            ("volumeShrinkRatio", "0.9"),         # vr≤0.9
            ("requireMA60Rising", "true"),        # ma60_up
            ("requireAboveAllMAs", "false"),
            ("requireThreeDayConfirm", "false"),
            ("swingLeftN", "5"),
            ("swingRightN", "2"),
            ("lookbackDays", "120"),
            ("minPassCount", "4"),
        ],
        "smart": {"minScore": "60"},              # 资金流强
        "direction": {"exclude": "DOWNTREND"},    # 熊市深回踩允许 OSCILLATION 票
    },

    # ══════════ 长线（牛市 tp=+50/sl=-8/hold=40；震荡 tp=+40/sl=-12/hold=40；熊市 tp=+50/sl=-12/hold=40）══════════
    # 长线核心约束保留：requireMA250Rising / useMA250InBullish / requireAboveYearLine / lookbackDays=250
    "long_term_bullish": {
        "base": "long_term",
        "comment": "长线·牛市：S5深回踩严（MA60升+跌幅≥15%+缩量≤0.7+资金流>0+站上年线），tp=+50/sl=-8/hold=40",
        "strict": [
            ("convergenceThreshold", "2.5"),
            ("useMA60", "true"),
            ("convergenceDurationDays", "20"),
            ("convergenceDurationRatio", "0.8"),
            ("minDrawdownPct", "15.0"),           # dd20≤-15（牛市深回踩严）
            ("requireVolumeShrink", "true"),      # 缩量
            ("volumeShrinkRatio", "0.7"),         # vr≤0.7
            ("requireMA60Rising", "true"),        # ma60_up
            ("maRisingDays", "10"),
            ("requireMA250Rising", "true"),       # 年线上行
            ("useMA250InBullish", "true"),
            ("requireAboveYearLine", "true"),     # 站上年线
            ("requireThreeDayConfirm", "false"),
            ("swingLeftN", "5"),
            ("swingRightN", "2"),
            ("lookbackDays", "250"),
            ("minPassCount", "4"),
        ],
        "smart": {"minScore": "60"},              # 资金流强
        "direction": {"exclude": "DOWNTREND,OSCILLATION"},
    },
    "long_term_oscillation": {
        "base": "long_term",
        "comment": "长线·震荡：S1粘合突破（粘合≤4.0+量比≥1.5+涨幅≥1.0+站上年线）+ 基因/资金流确认，tp=+40/sl=-12/hold=40",
        "strict": [
            ("convergenceThreshold", "4.0"),      # spread≤4.0
            ("useMA60", "true"),
            ("convergenceDurationDays", "20"),
            ("convergenceDurationRatio", "0.8"),
            ("volumeBreakoutRatio", "1.5"),       # vr≥1.5
            ("minDrawdownPct", "30.0"),
            ("minChangePct", "1.0"),              # chg≥1.0
            ("requireChangePct", "true"),
            ("requireMA60Rising", "true"),
            ("maRisingDays", "10"),
            ("requireMA250Rising", "true"),
            ("useMA250InBullish", "true"),
            ("requireAboveYearLine", "true"),
            ("requireAboveAllMAs", "true"),
            ("requireThreeDayConfirm", "true"),
            ("swingLeftN", "5"),
            ("swingRightN", "2"),
            ("lookbackDays", "250"),
            ("minPassCount", "6"),
        ],
        "smart": {"minScore": "55"},
        "direction": {"exclude": "DOWNTREND,OSCILLATION"},
    },
    "long_term_bearish": {
        "base": "long_term",
        "comment": "长线·熊市：S5深回踩+资金流（MA60升+跌幅≥12%+缩量≤0.9+资金流>0+站上年线）+ 环境持续≥3，tp=+50/sl=-12/hold=40",
        "strict": [
            ("convergenceThreshold", "2.5"),
            ("useMA60", "true"),
            ("convergenceDurationDays", "20"),
            ("convergenceDurationRatio", "0.8"),
            ("minDrawdownPct", "12.0"),           # dd20≤-12
            ("requireVolumeShrink", "true"),      # 缩量
            ("volumeShrinkRatio", "0.9"),         # vr≤0.9
            ("requireMA60Rising", "true"),        # ma60_up
            ("maRisingDays", "10"),
            ("requireMA250Rising", "true"),
            ("useMA250InBullish", "true"),
            ("requireAboveYearLine", "true"),
            ("requireThreeDayConfirm", "false"),
            ("swingLeftN", "5"),
            ("swingRightN", "2"),
            ("lookbackDays", "250"),
            ("minPassCount", "4"),
        ],
        "smart": {"minScore": "60"},              # 资金流强
        "direction": {"exclude": "DOWNTREND"},    # 熊市深回踩允许 OSCILLATION 票
    },
}

# 基准 pipeline 的 n_strict 需删除的参数（S3/S5 深跌低吸：取消涨幅/均线上方/3日确认要求）
REMOVE_STRICT_KEYS = {
    "short_term_bearish": {"volumeBreakoutRatio"},
    "mid_term_bullish": {"volumeBreakoutRatio", "moderateVolumeLower", "moderateVolumeUpper"},
    "mid_term_bearish": {"volumeBreakoutRatio", "moderateVolumeLower", "moderateVolumeUpper"},
}

# 中线基准 n_strict 额外保留的参数
MID_EXTRA_KEEP = {
    "mid_term_bullish": [("moderateVolumeLower", "1.2"), ("moderateVolumeUpper", "1.8")],
    "mid_term_oscillation": [("moderateVolumeLower", "1.2"), ("moderateVolumeUpper", "1.8")],
    "mid_term_bearish": [("moderateVolumeLower", "1.2"), ("moderateVolumeUpper", "1.8")],
}


def replace_node_block(xml: str, node_id: str, new_block: str) -> str:
    """按 Node id 整体替换节点块"""
    pattern = re.compile(
        rf'<Node id="{re.escape(node_id)}".*?</Node>', re.DOTALL
    )
    new_xml, n = pattern.subn(new_block, xml, count=1)
    if n != 1:
        raise RuntimeError(f"未找到或匹配多次 <Node id={node_id}>（匹配 {n} 次）")
    return new_xml


def replace_param(xml: str, node_id: str, param_name: str, new_value: str) -> str:
    """在指定节点块内替换单个 param 值"""
    node_pattern = re.compile(rf'(<Node id="{re.escape(node_id)}".*?</Node>)', re.DOTALL)
    m = node_pattern.search(xml)
    if not m:
        raise RuntimeError(f"未找到 <Node id={node_id}>")
    block = m.group(1)
    param_pattern = re.compile(rf'(<param name="{re.escape(param_name)}" value=")[^"]*(" />)')
    new_block, n = param_pattern.subn(rf"\g<1>{new_value}\g<2>", block, count=1)
    if n != 1:
        raise RuntimeError(f"在 {node_id} 中未找到或匹配多次 param {param_name}（匹配 {n} 次）")
    return xml[: m.start(1)] + new_block + xml[m.end(1):]


def generate() -> None:
    for key, spec in PARAMS.items():
        base = spec["base"]
        env = key.split("_")[-1]
        base_path = os.path.join(ASSETS, f"{base}_pipeline.xml")
        with io.open(base_path, "r", encoding="utf-8") as f:
            xml = f.read()

        # 1. 替换 n_strict
        xml = replace_node_block(xml, "n_strict", strict_block(spec["strict"]))

        # 2. 超短：替换 n_t1sell
        if "t1sell" in spec:
            for pname, pval in spec["t1sell"].items():
                xml = replace_param(xml, "n_t1sell", pname, pval)

        # 3. 短线/中线：替换 n_smart.minScore
        if "smart" in spec:
            xml = replace_param(xml, "n_smart", "minScore", spec["smart"]["minScore"])

        # 4. 中线：替换 n_direction.exclude
        if "direction" in spec:
            xml = replace_param(xml, "n_direction", "exclude", spec["direction"]["exclude"])

        # 5. 修改 DagPipeline id / name / description
        new_id = f"{base}_dag_{env}"
        new_name = f"{base}_pipeline_{env}"
        xml = re.sub(r'(<DagPipeline id=")[^"]*(" name=")[^"]*(")',
                     rf"\g<1>{new_id}\g<2>{new_name}\g<3>", xml, count=1)

        # 6. description 尾部加环境标注
        xml = re.sub(r'(description="[^"]*)"', rf'\g<1>（环境：{ENV_CN[env]}）"', xml, count=1)

        # 7. 在首个注释块之后（</pipeline 前的 description）已标注环境，无需额外注释

        out_path = os.path.join(ASSETS, f"{key}_pipeline.xml")
        with io.open(out_path, "w", encoding="utf-8", newline="\n") as f:
            f.write(xml)
        print(f"✔ {out_path}  ({spec['comment']})")


if __name__ == "__main__":
    generate()
