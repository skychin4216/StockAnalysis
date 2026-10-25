---
name: ssa-t0-variant-backtest
description: Run SSA-T0 strategy variant backtests and compare against baseline. Use when the user proposes a new UP/WINDOW/FLAT rule, asks to test a parameter change, or wants to evaluate a trading-logic modification on the 171-stock 1H pool. Do not use for BBI12 or non-T0 strategies.
---

# SSA-T0 策略变体回测对比

为 `_ssa_t0_engine.py` 的三态机做T引擎添加新规则/参数，在 171 只 1H 池上回测，与基线对比。数据驱动决策，不凭主观判断。

## 核心文件

- 引擎：`smalltools/_ssa_t0_engine.py`（Kotlin SsaT0Engine.kt 的 Python 忠实移植）
- 数据：`smalltools/data/_kalman_1h_cache.json`（171 只，每股 1023 根 1H，2025-09-19→2026-10-16）
- 基线结果：`smalltools/data/_t0_pool_results.json`
- 报告输出：`reports/t0_vs_bbi12.html`，变体用 `t0_vs_bbi12_<tag>.html`

## 基线口径（不要改动）

- 三态机：`regimes(cy)`，cy = mid − trend；1=UP 2=FLAT 3=WINDOW
- UP：核心仓=权益×80%；收>eUp(中线+2σ)卖持仓30%；收<eDn买回
- WINDOW：上穿中位满仓买、触 nUp(中线+1.5σ)全卖
- FLAT：空仓，破 eDn 满仓买T、超 eUp 卖T
- 费用：买(1+slip)(1+comm) 卖(1−slip)(1−comm−stamp)；comm=2.5e-4 stamp=5e-4 slip=5e-4
- reduce_mode=2（日线下行核心仓折半）已并入默认，即"基线"=red2
- 基线池级：收益 -6.29%、回撤 -25.41%、交易 107.2、胜率 54.3%、盈利票 50/171

## 工作流（5 步）

### 1. 加参数开关

在 `Params.__init__` 末尾加开关，默认关（不影响基线）：
```python
self.new_switch = False
```

### 2. 改 simulate() 分支

在对应状态分支（UP/WINDOW/FLAT）里加 `if p.new_switch:` 逻辑。注意：
- 决策在 t 收盘、成交在 t+1 开盘（`fill = hbars[t+1][2]`）
- 无未来函数：mid/nUp/eUp/eDn 是 t-1 推断、供 t 使用
- 整手 100 股，FIFO 配对成 T 单

### 3. 加 CLI 标签

在 `main()` 解析参数，输出文件名带 tag：
```python
newflag = "--new" in args
...
parts.append("new") if newflag else None
tag = "_".join(parts) if parts else "base"
...
p.new_switch = newflag
```
输出自动写到 `_t0_pool_results_<tag>.json` 和 `t0_vs_bbi12_<tag>.html`。

### 4. 单票验证

```powershell
cd d:\Stock\StockAnalysis\smalltools
python _ssa_t0_engine.py 600176 --new
```
检查：不报错、数值量级合理（基线 600176 = +195.7%/-15.2%/127笔/69.3%胜率）。

### 5. 全池跑 + 对比基线

```powershell
python _ssa_t0_engine.py --new   # 用 run_in_background
```
完成后对比：
```python
import json
base = {r['code']: r for r in json.load(open('data/_t0_pool_results.json'))}
new  = {r['code']: r for r in json.load(open('data/_t0_pool_results_new.json'))}
codes = [c for c in base if c in new]; n = len(codes)
def s(d,k): return sum(d[c][k] for c in codes)/n
print('ret base/new: %.2f / %.2f' % (s(base,'t0_ret'), s(new,'t0_ret')))
print('mdd base/new: %.2f / %.2f' % (s(base,'t0_mdd'), s(new,'t0_mdd')))
print('profit base/new: %d / %d' % (sum(1 for c in codes if base[c]['t0_ret']>0), sum(1 for c in codes if new[c]['t0_ret']>0)))
print('new beats base: %d/%d  new smaller mdd: %d/%d' % (
    sum(1 for c in codes if new[c]['t0_ret']>base[c]['t0_ret']), n,
    sum(1 for c in codes if new[c]['t0_mdd']>base[c]['t0_mdd']), n))
```

## 已测试变体参考

| 变体 | 开关 | 收益 | 回撤 | 结论 |
|---|---|---|---|---|
| 下穿中位减仓至20% | `--midcut` | -10.86% | -25.48% | 不采纳（中位线无价差，磨损大） |
| cycle拐点驱动 | `--cy` | -17.52% | -31.86% | 不采纳（拐点滞后+无摆动） |
| 波动自适应卖T量 | `--volt` | -6.26% | -25.44% | 不采纳（1H上σ变化小，缩放接近1） |
| 日线下行核心仓折半 | `--red2` | **-6.29%** | **-25.41%** | 采纳，已并入默认基线（回撤降1.9pp，盈利票+6） |
| 破eDn买回需趋势上行 | `--tfilter` | -6.29% | -25.41% | 无效（与基线完全相同） |
| RSI70确认卖T | `--rsi` | -5.58% | -25.64% | 采纳（收益+0.7pp，交易数107→69） |
| ADX动态仓位 | `--adx` | -6.55% | -24.74% | 单用不采纳（收益-0.3pp换回撤-0.7pp） |
| RSI+ADX组合 | `--rsi --adx` | **-5.52%** | **-25.01%** | 采纳（盈利票54，57%票跑赢基线） |
| BBI12空仓少做T | `--bbi1` | -6.17% | -25.47% | 微正面，不单独采纳 |
| BBI12空仓则空仓 | `--bbi2` | -11.35% | -27.54% | 弃（收益-5pp，交易反增至148） |
| ER70+Δ5ER入场闸 | `--rsi --adx --ergate` | **-4.28%** | **-22.06%** | 采纳（交易45，盈利票59，67%票跑赢基线，77%票回撤更小） |
| ER闸阈值扫参 | `--ergate=60/65/75/80` | 65最优-4.02% | 80最优-21.50% | 阈值不敏感（60~80收益差仅0.6pp），鲁棒；维持默认70（盈利票最多59） |
| ER自身时序分位闸W250 | `--rsi --adx --erq` | **-4.09%** | **-22.05%** | 微优横截面闸（93/171票更优，盈利票60）；工程更优：单票自洽无需全池数据，推荐口径 |
| ER时变路由切BBI12波段 | `--rsi --adx --route` | -6.94% | -26.62% | 弃（ER<70时段切BBI12原始=接飞刀；600176上+265%是离群点，全池证伪；静态按票ER分组路由也已证伪，corr≈-0.11） |

## BBI12 系参照（`_bbi_er_vs_ssa` 口径，FEE=0.13%，long-only 拐点）

同池 171 只，供跨策略对比（胜率10%是止损记账假象）：

| 变体 | 收益 | 回撤 | 备注 |
|---|---|---|---|
| BBI12 原始 | -4.87% | -29.40% | — |
| BBI12+ER70 | -2.40% | -12.57% | 5笔 |
| BBI12+ER70+止损 | -1.67% | -6.86% | — |
| BBI12+ER70+Δ5ER | -1.08% | -10.76% | 盈利票50 |
| BBI12+ER70+Δ5ER+止损 | **-0.88%** | **-5.90%** | 全场最优：3.8笔/盈利票38，双维度碾压T0系 |
| 组合版+低仓试错0.2/0.3 | -1.81% / -2.18% | -13.22% / -14.93% | 弃（ER<70开仓=费用陷阱，交易3.8→23.2） |
| 组合版+移动止损8% | -0.73% | -10.32% | 收益微优但回撤差于固定止损，备选 |

## 最终推荐配置（2026-10-20 收敛）

| 用途 | 策略 | ER 口径 | 全池指标 |
|---|---|---|---|
| 新仓入场（选股） | BBI12+ER70+Δ5ER+固定成本止损 | 横截面分位（选池内最强趋势票） | -0.88% / -5.90% / 3.8笔 |
| 持仓做T（择时） | T0 `--rsi --adx --erq` | 自身时序分位 W250（单票自洽） | -4.09% / -22.05% / 60票盈利 |

- ER 口径刻意不同：入场是"选股"（横截面比），做T是"择时"（自身比），各配其义。
- 实时告警：1H bar 收盘后刷新（10:30/11:30/14:00/15:00），增量算单窗口 SSA <10s/171只；盘中不刷（bar 未收盘的信号与回测口径不一致）。
- 已证伪不再投入：按票静态路由、弱时段切波段（--route）、闸下低仓试错、ER 阈值调优（60~80 不敏感，定 70）。

## 路由探索结论（2026-10-20，两轮验证）

- **静态按票路由**：按个股平均 ER 分位分 4 组，组内 T0 vs BBI12 无单调性；corr(ER均分位, T0超额)=-0.109。证伪。
- **时变切换路由**（`--route`：ER<70 时段切 BBI12 原始波段）：全池 -6.94%/-26.62%，跑输 ergate（-4.28%/-22.06%）——专挑弱时段接飞刀。600176 上 +265% 是离群点，不可推广。
- **最终口径**：ER 的正确用法是"闸"（弱时段不交易），不是"路由"（分票/切换）。弱时段最优动作=空仓等待。

## SSA 混合策略对照（2026-10-21 补测，非 T0/BBI 系）

SsaHybridEngine（trend 斜率 L10/L20 + cycle 拐点 L60 + EMA2；买=斜率转正/谷底，卖=峰顶/死叉）：

| 口径 | 收益 | 回撤 | 交易 | 盈利票 | 结论 |
|---|---|---|---|---|---|
| 1H 同 bar（vs T0/BBI 冠军） | -8.63% | -33.22% | 35.8 | 48/171 | 全场垫底——参数是日线校准的，1H 上错配 |
| 日线主场（vs BBI12 日线 -1.76%） | **-0.52%** | -26.64% | 10.3 | 65/170 | 超基准(-8.94%) +8.4pp，日线级择时可用 |
| 1H 网格寻优最优 ts20/tl30/cy30/s2/bf0 | -4.83% | -24.88% | 38.4 | 53/171 | 仍输 T0 冠军(-4.09/-22.05)与 BBI12 组合(-0.88/-5.90)，不采纳 |
| +快通道 ER10≥0.6/don15/both（默认） | -4.33% | -24.82% | 39.5 | 58/171 | 微正：收益+0.5pp、回撤略好、盈利票+5、交易仅+1.1，采纳为默认 |
| +快通道 ER10≥0.4/don15/both（收益最优） | -4.02% | -25.12% | 40.7 | 58/171 | 收益最高但回撤略差，可选档 |

1H 网格（156 组合，存档 `data/_hybrid_grid_1h.json`）规律：Top20 几乎全是 ts=20+span=2（慢趋势+平滑），快参数（ts=5/10）全面落后；胜率恒 ~35%（机制缺陷：峰顶卖=盈利了结快、死叉卖=亏损拖得长）。寻优救不回机制，1H 通道维持双通道不扩。

**快通道（2026-10-21，存档 `data/_hybrid_fast_1h.json`）**：Kaufman ER10（`|c[t]-c[t-10]|/Σ|Δc|`，衡量单边强度）+ 唐奇安收盘通道（前 N 根高/低点），ER≥阈值时突破即买、跌破即卖，覆盖慢通道而不替换。18 组合 14 组跑赢基线，但幅度仅 0.2~0.8pp；**buy-only 全部垫底（快卖才是主要贡献）**，胜率几乎不变（34.5~35.8）→ 只解决"急涨急跌晚 1~2 根"，未改机制缺陷。默认 thr=0.6/donN=15/both，参数见 `Params.fastErThr/fastDonN/fastSell`。

定位：**日线通道**（APK「混合策略建议」页即日线页 + 每日荐股增量引擎 + 票性识别分档配参），与 1H 双通道不竞争，**不加入 1H 预警线**。结果存档 `data/_hybrid_pool_results.json`。

## 判定原则

- 回测数据优先，不凭视觉/直觉
- 收益 + 回撤 + 盈利票数 + 跑赢比例综合看，不看单一指标
- 交易数暴涨通常是磨损信号，警惕
- 胜率下降但收益上升可接受（盈亏比改善），胜率+收益双降必弃
