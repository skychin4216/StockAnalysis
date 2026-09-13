# 环境感知 Pipeline 改造规划 v1.0（2026-08-22）

> 核心命题：**不同大盘环境（上升/震荡/下跌）必须用不同的选股 pipeline**。
> 三年统一回测会把不同环境胜率平均化（天花板 68%），按环境分层后出现 80%+ 组合。

## 一、回测证据（三年窗口，_idea_scan.py 环境分层）

### 各周期 × 环境最优组合

| 周期 | 环境 | 最优策略 | 胜率 | n | avg/笔 | 卖出规则 | 说明 |
|---|---|---|---|---|---|---|---|
| 超短 | BULLISH | S1均线粘合突破+涨停基因(zt20≥1) | **82.4%** | 17 | +0.39% | tp0.8/sl-1.5/hold1 | 频率极低（三年17笔），精选少做 |
| 超短 | OSCILLATION | S1+ZT | **81.8%** | 11 | +0.11% | tp0.8/sl-3.0/hold2 | 同上 |
| 超短 | BEARISH | 无≥75%组合 | — | — | — | — | **应空仓/降频** |
| 短线 | BULLISH | S1+MFI/CMF | 67.3% | 309 | +0.08% | tp2.0/sl-4.0/hold8 | 大样本 |
| 短线 | OSCILLATION | S1+ZT | 72.0% | 25 | +0.32% | tp2.0/sl-4.0/hold3 | — |
| 短线 | BEARISH | S2资金流+ZT | 62.4% | 885 | +0.37% | tp3.0/sl-4.0/hold8 | 大样本，防御 |
| 中线 | BULLISH | S5深回踩+MFI/CMF | 68.7% | 281 | +0.97% | tp5.0/sl-8.0/hold20 | 大样本 |
| 中线 | OSCILLATION | S5+MFI/CMF | 61.3% | 336 | +0.02% | tp5.0/sl-8.0/hold20 | — |
| 中线 | **BEARISH** | **S5深回踩+MFI/CMF** | **80.5%** | **195** | **+2.61%** | tp5.0/sl-8.0/hold10 | **大样本高胜率** |

### 关键结论
1. **超短/短线只在 BULLISH/OSCILLATION 有效**（82%/72%），BEARISH 无信号价值 → 空仓。
2. **中线 BEARISH 反而是黄金环境**（80.5%，n=195）：逆势深回踩+资金流确认，avg 最高 +2.61%。
3. **涨停基因（近20日涨停≥1次）是超短/短线 82%/72% 的关键因子**（无 ZT 仅 64%）。
4. 一年窗口 90.9% 与三年 BULLISH 82.4% 一致 → 2026 结构性牛市中该信号真实有效，非纯过拟合（但样本小，频率低）。

## 二、Kotlin 现状盘点（已有 vs 缺失）

| 能力 | 现状 | 位置 |
|---|---|---|
| 环境分类 BULLISH/BEARISH/OSCILLATION | ✅ 已有 | MarketAnalyzer + market_ma_unified |
| 超短/短线牛市趋势跟随切换 | ✅ 已有 | StockEvaluationNode.resolveMode（TREND_FOLLOW/CONVERGENCE） |
| AI 层环境自适应（阈值/数量/止盈止损/空仓） | ✅ 已有 | MarketAdaptiveStrategy |
| 主力资金过滤 | ✅ 已有 | n_smart（smart_money_filter） |
| 新闻拦截 | ✅ 已有 | n_newsguard |
| 中线熊市防守 | ⚠️ 部分 | n_defensive（高息防守）但**未切换选股逻辑** |
| **涨停基因过滤** | ❌ 缺失 | 回测 82%/72% 的关键因子 |
| **中线 BEARISH 深回踩+资金流低吸模式** | ❌ 缺失 | 回测 80.5%（n=195）核心组合 |
| **超短 BEARISH 强制降频/空仓开关** | ⚠️ 需确认 | forceEmpty 存在但依赖 AI 高分股<2 |

## 三、改造方案（超短/短线/中线，长线不动）

### 3.1 中线 BEARISH：新增「深回踩+资金流低吸」模式（最高优先级）
- **依据**：回测 80.5% n=195 avg+2.61%，最大样本的高胜率组合。
- **实现**：`StockEvaluationNode` 增加 `AnalysisMode.DEEP_PULLBACK`（深回踩低吸模式），
  条件 = 60日线上翘 + 距20日高跌幅≥12% + 缩量(量比≤0.9) + CMF>0 + 当日不大跌。
  中线的 `n_direction`（剔除 OSCILLATION/DOWNTREND）在 BEARISH 时**不剔除**深回踩信号，
  或新增环境路由：BEARISH 时走低吸模式、跳过粘合追高。
- **入口开关**：XML 参数 `pullbackModeWhenBearish=true`（仅中线配置）。

### 3.2 超短/短线：加「涨停基因」过滤（sector_boost 后、smart 前）
- **依据**：回测 S1+ZT 82%/72% vs 无 ZT 64%/58%。
- **实现**：`strict_selection`（StockEvaluationNode）的 TREND_FOLLOW 模式追加
  `近20日内涨停(≥9.5%)≥1次` 条件（或新增轻量节点 `limit_up_gene`）。
- **周期**：仅超短/短线启用（`requireLimitUpGene=true`），中线/长线不动。

### 3.3 超短 BEARISH：强制降频
- 确认 `MarketAdaptiveStrategy.shouldForceEmpty` 生效；BEARISH 时超短 `maxStockCount=0~1`，
  避免无效信号（回测 BEARISH 无≥75% 组合）。

### 3.4 环境感知开关接线
- `n_adaptive`（adaptive_params）已将 direction 写入 context；
  新增 `n_env_switch`（环境路由节点）或复用现有 `resolveMode` 机制，
  按 direction 分发策略模式：BULLISH→进攻 / OSCILLATION→震荡 / BEARISH→防御。

## 四、数据增强方案（用户要求：科创50/美股/外围前排消息）

### 4.1 科创50 指数（sh000688）
- 腾讯行情接口 `qt.gtimg.cn/q=sh000688` 可取日K，加入 `_kline_cache.json`，
  环境判断从 3 指数升级为 4 指数投票（上证/深成/创业板/科创50）。
- Kotlin 端 `IndexSnapshot` 增加科创50。

### 4.2 美股/外围板块指数（纳指/标普/道指）
- 腾讯美股接口：`usNDX`(纳指100) / `usIXIC`(纳指综指) / `usINX`(标普500) / `usDJI`(道指)。
- 作为「外围环境」因子：美股近5日趋势 → 影响 A 股开盘情绪，纳入环境评分。

### 4.3 前排公司消息（高盛/英伟达/苹果等）
- 已有 `n_newsguard` 处理个股新闻拦截；前排公司消息影响板块情绪，
  纳入 `SectorTrendForecaster`（新闻情绪因子，已有框架）。

### 4.4 历史公告数据（已完成抓取）
- `_announce_cache.json`：126 只股票三年公告已抓取，可做公告事件因子（重组/业绩预告/减持）。
- 待接入：信号日前 N 日公告事件 → 加分/排除。

## 五、执行顺序（M1→M5）

- [ ] M1 数据增强：抓科创50+美股指数 → 扩展环境判断（Python 侧验证）
- [ ] M2 回测补验：科创50+美股加入 market_state 后重跑环境分层，确认稳定性
- [ ] M3 Kotlin：StockEvaluationNode 加 DEEP_PULLBACK 模式 + 涨停基因条件
- [ ] M4 Kotlin：中线 XML 接熊市低吸开关；超短/短线 XML 加涨停基因参数
- [ ] M5 构建 + 全周期回归（超短/短线/中线），长线不动

## 六、风险与边界
- 超短 BULLISH 样本 n=17 偏小：频率低是「精选」性质，需结合 AI/新闻层保持高确定性。
- 中线 BEARISH 80.5% 大样本可靠，但 hold=10 天需要止盈止损纪律（tp5/sl-8）。
- 长线 pipeline 不修改（用户明确）。
