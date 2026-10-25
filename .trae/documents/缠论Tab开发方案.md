# 缠论 Tab 开发方案（chan.py 融合）

> 目标：在 `SsaAnalysisFragment` 顶部「三层稳态做T」旁新增 **缠论** 模式按钮，融合 GitHub `Vespa314/chan.py` 缠论模型，实现 **荐股 / 单股 / 持仓池 / 回测 / 预测 / 验证** 六大功能。
> 编写日期：2026-10-20
> 状态：方案稿（待评审）

---

## 一、背景与目标

### 1.1 现有结构
- 主入口 [SsaAnalysisFragment.kt](file:///d:/Stock/StockAnalysis/app/src/main/java/com/chin/stockanalysis/ui/SsaAnalysisFragment.kt) 顶部已有 3 个模式按钮：**情景风控回测 / 三层稳态做T / 混合策略建议**，通过 `switchMode(m)` 切换宿主 `FrameLayout` 并懒加载子 Fragment。
- `三层稳态做T` 宿主加载 [SsaT0Fragment.kt](file:///d:/Stock/StockAnalysis/app/src/main/java/com/chin/stockanalysis/ui/SsaT0Fragment.kt)，内部再分 **单票建议 / 持仓池 / 每小时简报 / 预警 / ER分位** 五个子模式。
- K 线单一事实源：`data/kline_store.json`（688 只全历史 1H，2008 起），Android 侧由 `IntradayKlineFetcher` 增量拉取 1H。

### 1.2 chan.py 选型
- 仓库：`https://github.com/Vespa314/chan.py`（开源缠论框架，star 1k+）
- 能力：分型 → 笔 → 线段 → 中枢 → 一/二/三类买卖点，支持多级别联立、增量 `trigger_load`、回测用 `step_load`。
- **不包含策略**（作者明确不开放策略/ML），策略需自行组装。
- 依赖：Python ≥ 3.11。
- 许可证：GPLv3（需注意）。

### 1.3 集成原则
- **计算在 PC，展示在 Android**：chan.py 跑在 `smalltools/`，输出 JSON 到 `app/src/main/assets/` 或 `data/`，Android 只负责渲染。这与项目既有「XML DAG + smalltools 产出 + APK 消费」的单事实源模式一致，避免在 APK 内嵌 Python（Chaquopy 体积/复杂度高）。
- **零 LLM**：缠论结构计算纯规则，不消耗积分。
- **复用 1H K 线**：直接读 `data/kline_store.json`，不重复拉取。

---

## 二、chan.py 核心 API 摘要（已实测）

### 2.1 入口类 `CChan`
```python
from Chan import CChan
from ChanConfig import CChanConfig
from Common.CEnum import KL_TYPE, AUTYPE, DATA_SRC

chan = CChan(
    code="600188",
    begin_time="2024-01-01",
    end_time=None,
    data_src="custom:LocalKlineAPI.CLocalKline",  # 自定义数据源
    lv_list=[KL_TYPE.K_DAY, KL_TYPE.K_60M],          # 级别从高到低
    config=CChanConfig({"trigger_step": True}),      # 回测/增量模式必开
    autype=AUTYPE.QFQ,
)
```

### 2.2 关键配置 `CChanConfig`
| 参数 | 默认 | 说明 |
|------|------|------|
| `trigger_step` | False | **回测/增量必须设 True**，否则一次性算完 |
| `bi_strict` | True | 严格笔（顶底分形间隔） |
| `bi_fx_check` | "strict" | 笔顶底分形校验 |
| `divergence_rate` | inf | 一买背驰比例（出中枢笔 MACD / 进中枢笔） |
| `min_zs_cnt` | 1 | 一买至少经历中枢数 |
| `max_bs2_rate` | 0.9999 | 二买回撤最大比例（0.618 较经典） |
| `macd_algo` | "peak" | 背驰 MACD 算法 |
| `bs_type` | "1,1p,2,2s,3a,3b" | 启用的买卖点类型 |
| `zs_combine` | True | 中枢合并 |
| `mean_metrics`/`trend_metrics` | [] | 均线/趋势指标周期（如 `[20,60]`） |

### 2.3 取出缠论元素
```python
kl = chan.kl_datas[KL_TYPE.K_60M]   # CKLine_List
kl.lst           # List[CKLine] 合并K线
kl.bi_list.bi_list        # List[CBi] 笔
kl.seg_list.lst          # List[CSeg] 线段
kl.zs_list.zs_lst        # List[CZS] 中枢
# 买卖点：注意 quick_guide 写的 lst 已过时，实际用 bsp_store_flat_dict（Dict[bi_idx, CBS_Point]）
bsp_lst = list(kl.bs_point_lst.bsp_store_flat_dict.values())
# 或按 bi.idx 正序迭代：sorted(bsp_lst, key=lambda b: b.bi.idx)
```

**买卖点 `CBS_Point` 关键字段**：
- `klu`：所在 K 线（含 `.time / .close / .idx`）
- `is_buy`：True=买点，False=卖点
- `type: List[BSP_TYPE]`：`T1 / T1P / T2 / T2S / T3A / T3B`
- `bi.is_sure`：该笔是否已确定（False=虚笔，信号可能消失）
- `bi.parent_seg`：所属线段

### 2.4 外部喂 K 线（实盘增量）
```python
from KLine.KLine_Unit import CKLine_Unit
from Common.CTime import CTime
from Common.CEnum import DATA_FIELD

klu = CKLine_Unit({
    DATA_FIELD.FIELD_TIME: CTime(2026, 10, 20, 14, 0),
    DATA_FIELD.FIELD_OPEN: 10.5, DATA_FIELD.FIELD_HIGH: 10.8,
    DATA_FIELD.FIELD_LOW: 10.4, DATA_FIELD.FIELD_CLOSE: 10.7,
    DATA_FIELD.FIELD_VOLUME: 100000.0,
})
chan.trigger_load({KL_TYPE.K_60M: [klu]})
```

### 2.5 回测模式
```python
for chan_snapshot in chan.step_load():  # 逐根K线推进
    bsp_lst = chan_snapshot.kl_datas[KL_TYPE.K_60M].bs_point_lst.lst
    # 在每一帧（无未来信息）判断信号并下单
```

### 2.6 坑与注意
1. **Python ≥ 3.11**（计算密集，3.11 提速 16%）。
2. **递归深度**：序列化/deepcopy 需 `sys.setrecursionlimit(0x100000)`。
3. **`is_sure=False`**：尾部信号会随新 K 线消失，策略必须过滤或打上「待确认」标签。
4. **日期字段**：`CTime` 需手动构造 `(year,month,day,hour,minute)`，不能直接传字符串。
5. **多级别顺序**：`lv_list` 必须从高到低（`K_DAY` 在前，`K_60M` 在后）。
6. **GPLv3**：仅在 PC 端 smalltools 调用，不进 APK，不污染 APK 许可证。

---

## 三、整体架构

```
┌────────────────────────── PC 端 (smalltools) ──────────────────────────┐
│                                                                          │
│  data/kline_store.json (1H全历史)                                        │
│        │                                                                 │
│        ▼                                                                 │
│  smalltools/_chanlun_engine.py   ← 包装 chan.py                          │
│   ├─ compute_structure(code, lv) → {笔/段/中枢/买卖点 JSON}              │
│   ├─ scan_pool()              → 全池一/二/三买信号榜                     │
│   ├─ backtest(code, rule)      → 买卖点策略回测报告                      │
│   ├─ predict(code)             → 基于当前结构的走势推演                  │
│   └─ verify(prediction_id)     → 预测事后验证                            │
│        │                                                                 │
│        ▼                                                                 │
│  assets/data/chanlun/                                                     │
│   ├─ pool_signals.json        (荐股榜：每小时刷新)                        │
│   ├─ structure_{code}.json    (单股结构：按需/缓存)                       │
│   ├─ pool_structure.json      (持仓池结构快照)                            │
│   ├─ backtest/{code}_{rule}.json (回测结果)                              │
│   ├─ predictions.jsonl        (预测记录 + 验证结果)                       │
│   └─ chanlun_config.json      (参数配置：divergence_rate 等)             │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼  (assets 随 APK 打包 / 或 PcBridge 拉取)
┌────────────────────────── Android 端 (APK) ─────────────────────────────┐
│                                                                          │
│  SsaAnalysisFragment  →  新增第 4 个模式按钮「📐 缠论」                    │
│        │                                                                 │
│        ▼  lazy load                                                      │
│  ChanlunFragment (新增)                                                  │
│   ├─ 子模式按钮行：荐股 / 单股 / 持仓池 / 回测 / 预测 / 验证              │
│   └─ 共用 K 线图渲染层（MPAndroidChart CandleData + 笔/中枢叠加）         │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

### 3.1 PC→Android 数据通道
复用现有两条通路：
1. **静态资产**：`app/src/main/assets/data/chanlun/*.json` 随 APK 打包，离线可用。
2. **增量更新**：通过 `PcBridgeClient`（`cmd("chanlun.fetch")`）在持仓池/单股页按需拉取最新结构，避免 APK 频繁打包。

### 3.2 不采用的方案
- ❌ Chaquopy 在 APK 内跑 chan.py：APK 体积 +50MB，Python 3.11 兼容性、性能差。
- ❌ Kotlin 重写缠论：工程量巨大（笔段中枢买卖点上万行），且无法与 chan.py 结果对齐。
- ❌ 直接把 chan.py 当库 import 到 DAG：chan.py 是独立包，需放 `smalltools/chan_py/` 并加 `sys.path`。

---

## 四、六大功能模块设计

### 4.1 荐股（Pool Scan）

**目标**：扫描**核心池**（`app/src/main/assets/core_stock_pool.json`），找出最近出现缠论买点（一买/二买/三买）的股票，按信号强度排序。全 688 只按需可选。

**数据流**：
```
smalltools/_chanlun_scan.py
  → 读取 core_stock_pool.json（核心池）+ 可选全池 data/kline_store.json
  → 对每只调用 compute_structure(code, 级别)
  → 筛选最近 N 根 K 线内出现的 is_buy 信号
  → 按信号类型加权（一买>二买>三买）+ is_sure 加分 + 背驰度 排序
  → 写 assets/data/chanlun/pool_signals.json
```

**输出 JSON 结构**：
```json
{
  "scan_time": "2026-10-20 14:00:00",
  "pool_size": 688,
  "signals": [
    {
      "code": "600188",
      "name": "兖矿能源",
      "bsp_type": "T2",
      "is_sure": true,
      "price": 10.7,
      "signal_idx": -1,
      "zs_cnt": 2,
      "divergence": 0.85,
      "seg_dir": "up",
      "score": 87
    }
  ]
}
```

**刷新频率**：交易时段每小时一次（与 `_market_scan` 同频），非交易时段不推送只采集。

---

### 4.2 单股功能（Single Stock）

**目标**：输入单只股票，展示完整缠论结构图（K 线 + 笔 + 中枢 + 买卖点标注）+ 当前状态解读。

**UI 分区**：
1. **顶部**：代码输入框 + 级别选择（**1H线 / 日线** 两个选项，**默认 1H线**）+ 「分析」按钮。日线由 1H 聚合或单独拉取。
2. **主图**：CandleData K 线 + 折线叠加笔（`bi.get_begin_val/get_end_val`）+ 中枢矩形（`zs.low/high`，半透明填充）+ 买卖点圆点（红买绿卖，标注 1/2/3）。
3. **状态卡**：当前所在笔方向、是否在中枢内、最近买卖点、背驰状态、下一类买卖点预判。
4. **结构列表**：最近 10 笔 / 5 中枢 / 最近 5 买卖点（可点选跳转主图位置）。

**数据源**：`structure_{code}.json`（PC 预计算缓存，单股页通过 PcBridge 按需触发 `compute_structure`）。

---

### 4.3 持仓池（Position Pool）

**目标**：监控持仓股的缠论状态，预警卖点（一卖/二卖/三卖）和背驰。

**数据流**：
- 持仓来源：**共享三层稳态做T持仓池** `SsaT0PoolStore`（2026-10-23 改口，不再用独立 `chanlun_pool.json`），增删在做T页操作，缠论页只读+算状态。
- Android 端：读 `SsaT0PoolStore.load()` → 对每只调本地 `ChanlunEngine.compute` → 风险分级表格。
- Android 展示：表格列 = 代码 / 名称 / 当前笔方向 / 中枢位置 / 最近卖点 / 风险等级。
- 卖点预警：出现一卖且 `is_sure=True` 时高亮红色；三卖（跌破中枢）标「离场」。**先只在 Tab 内展示，不接推送。**

**风险分级**：
- 🟢 安全：上升笔 + 中枢上方 + 无卖点
- 🟡 观察：盘整中 / 虚卖点
- 🔴 警戒：一卖已确认 / 顶背驰
- ⛔ 离场：三卖（跌破中枢下沿）

---

### 4.4 回测（Backtest）

**目标**：基于缠论买卖点策略的历史回测，输出胜率/收益/回撤。

**策略规则（可配置）**：
```
买入条件（可多选）：
  □ 一买（is_sure=True）
  □ 二买
  □ 三买
  □ 底背驰确认
卖出条件：
  □ 一卖
  □ 二卖
  □ 三卖（强制止损）
  □ 固定止盈 X%
  □ 固定止损 -Y%
级别：日 / 60M / 30M
回测区间：[start, end]
```

**实现**：
```python
chan = CChan(code, config=CChanConfig({"trigger_step": True}), lv_list=[K_60M])
for snap in chan.step_load():
    bsp = snap.kl_datas[K_60M].bs_point_lst.lst
    if not bsp: continue
    last = bsp[-1]
    # 每帧只看最后一个信号（无未来信息）
    if last.is_buy and last.bi.is_sure and last.type==T1 and not in_position:
        buy(last.klu.close, last.klu.time)
    elif (not last.is_buy and last.bi.is_sure) or stop_loss_hit():
        sell(...)
```

**输出**：
```json
{
  "code": "600188",
  "rule": "T1_buy_T1sell",
  "period": "2023-01-01~2026-10-01",
  "trades": 42,
  "win_rate": 0.57,
  "total_return": 0.234,
  "max_drawdown": -0.18,
  "avg_hold_days": 12,
  "trades_detail": [{...}]
}
```

**UI**：回测参数表单 + 结果卡（胜率/收益/回撤/交易次数）+ 收益曲线图。

---

### 4.5 预测（Prediction）

**目标**：基于当前缠论结构推演未来走势，记录预测供事后验证。**手动选择批量刷新**：用户在列表勾选若干票 → 点「批量生成预测」，不做定时批量。

**预测逻辑（规则型，非 ML）**：
1. 取当前最后一笔方向 + 所在中枢位置。
2. 推演 3 种情景：
   - **延续**：当前笔方向不变，目标位 = 中枢上/下沿 ± 中枢高度。
   - **转折**：若出现顶/底背驰 + 二买/二卖，则预判反向笔，目标 = 前中枢中轨。
   - **盘整**：在中枢内震荡，高抛低吸。
3. 给出每个情景的概率（基于历史同类结构胜率）。

**输出**：
```json
{
  "pred_id": "P20261020_600188_001",
  "code": "600188",
  "time": "2026-10-20 14:00",
  "current_bi_dir": "down",
  "in_zs": true,
  "scenarios": [
    {"name": "底背驰反弹", "prob": 0.55, "target": 11.2, "horizon": "5根K线"},
    {"name": "跌破中枢下沿", "prob": 0.30, "target": 9.8, "horizon": "3根K线"},
    {"name": "中枢震荡", "prob": 0.15, "range": [10.2, 10.8], "horizon": "8根K线"}
  ]
}
```

记录到 `predictions.jsonl`，等待验证模块消费。

---

### 4.6 验证（Verify）

**目标**：对历史预测做事后验证，计算预测准确率，持续修正情景概率。

**逻辑**：
1. 从 `predictions.jsonl` 读取未验证预测。
2. 取预测时刻之后的 K 线（最多 `horizon` 根）。
3. 判断哪个情景先命中（价格触及 target）。
4. 写回验证结果：
```json
{
  "pred_id": "P20261020_600188_001",
  "verified": true,
  "hit_scenario": "底背驰反弹",
  "hit_time": "2026-10-21 10:30",
  "actual_return": 0.046
}
```
5. 滚动统计：各情景历史命中率 → 反哺预测概率（贝叶斯更新）。

**UI**：预测列表 + 验证状态标签（✅命中/❌未中/⏳验证中）+ 准确率统计卡。

---

## 五、UI 界面设计

### 5.1 入口改造

在 [SsaAnalysisFragment.kt](file:///d:/Stock/StockAnalysis/app/src/main/java/com/chin/stockanalysis/ui/SsaAnalysisFragment.kt) 顶部 `modeRow` 增加第 4 个按钮（或换行），新增宿主 `chanlunHost`：

```
┌─────────────────────────────────────────────────────────┐
│ [📊 情景风控] [🔄 三层稳态做T] [🧭 混合策略] [📐 缠论]   │  ← 模式行
└─────────────────────────────────────────────────────────┘
```

点击「缠论」→ `switchMode(3)` → 懒加载 `ChanlunFragment()`。

### 5.2 ChanlunFragment 布局

```
┌─────────────────────────────────────────────────────────┐
│  [🎯 荐股] [🔍 单股] [📋 持仓池] [📈 回测] [🔮 预测] [✅ 验证] │ ← 子模式行（横向滚动）
├─────────────────────────────────────────────────────────┤
│                                                         │
│                  （子模式内容区）                        │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

6 个子模式按钮放 `HorizontalScrollView`，避免挤压。复用 `SsaT0Fragment` 的 `modeBtn` + `paintMode` 高亮逻辑。

### 5.3 各子模式界面

#### 5.3.1 荐股页
```
┌─────────────────────────────────────────┐
│ 刷新于 14:00  |  池688只  |  信号32只    │
├─────────────────────────────────────────┤
│ 排名 代码 名称  类型  价格  中枢数 背驰  得分 │
│  1  600188 兖矿 T2✔ 10.7   2    0.85  87  │ ← 点击进单股
│  2  000858 五粮 T1✔ 156.2  1    0.92  85  │
│  3  300750 宁德 T3✗ 198.5  1    —     72  │
│  ...                                     │
└─────────────────────────────────────────┘
  类型: T1一买 T2二买 T3三买   ✔已确认 ✗待确认
```

#### 5.3.2 单股页
```
┌─────────────────────────────────────────┐
│ [600188____] 级别:(1H线▾)/日线 [▶ 分析]  │ ← 默认1H线
├─────────────────────────────────────────┤
│  状态卡: 当前↓笔 | 中枢[10.2,10.8]内     │
│          最近: 二买@10.35(已确认)        │
│          预判: 底背驰→目标11.2(55%)      │
├─────────────────────────────────────────┤
│  📈 K线+笔+中枢+买卖点 叠加图（可缩放）    │
│    10.8 ┤    ┌──┐                       │
│         │    │  │  ← 中枢矩形           │
│    10.2 ┤  ┌─┘  └──●二买               │
│         │  │       ／                   │
│         └──┴──────┴────────            │
├─────────────────────────────────────────┤
│  笔列表 | 中枢列表 | 买卖点列表 (Tab)     │
└─────────────────────────────────────────┘
```

#### 5.3.3 持仓池页
```
┌─────────────────────────────────────────┐
│ [添加:600188] [删除] | 持仓 5 | 警戒 1 | 离场 0 │ ← 手动增删
├─────────────────────────────────────────┤
│ 代码  名称  笔方向  中枢位置  卖点  风险   │
│ 600188 兖矿  ↑    中枢上方  无    🟢     │
│ 000858 五粮  →    中枢内    虚一卖 🟡    │
│ 300750 宁德  ↓    跌破下沿  三卖  ⛔     │
│ ...                                     │
└─────────────────────────────────────────┘
```

#### 5.3.4 回测页
```
┌─────────────────────────────────────────┐
│ 策略规则                                │
│ 买入: ☑一买 ☑二买 ☐三买                 │
│ 卖出: ☑一卖 ☐二卖 ☑三卖(止损)          │
│ 级别: [60M▾]  区间:[2023-01-01~2026-10-01]│
│ [▶ 开始回测]                            │
├─────────────────────────────────────────┤
│ 胜率: 57% | 收益: +23.4% | 回撤: -18%   │
│ 交易: 42 笔 | 均持: 12 天               │
│ 收益曲线图                               │
└─────────────────────────────────────────┘
```

#### 5.3.5 预测页
```
┌─────────────────────────────────────────┐
│ ☑600188 ☑000858 ☐300750 [批量生成预测]   │ ← 手动勾选+批量
├─────────────────────────────────────────┤
│ 当前: ↓笔 | 中枢[10.2,10.8]内            │
│ 情景1: 底背驰反弹 55% → 11.2 (5根)       │
│ 情景2: 跌破下沿   30% → 9.8  (3根)       │
│ 情景3: 中枢震荡   15% → [10.2,10.8] (8根)│
├─────────────────────────────────────────┤
│ 历史预测列表 (滚动)                       │
└─────────────────────────────────────────┘
```

#### 5.3.6 验证页
```
┌─────────────────────────────────────────┐
│ 验证准确率: 62% (12/19) | 待验证: 3     │
├─────────────────────────────────────────┤
│ 预测ID          股票  情景        结果   │
│ P20261020_001  600188 底背驰反弹 ✅命中  │
│ P20261019_002  000858 跌破下沿   ❌未中  │
│ P20261019_003  300750 中枢震荡   ⏳验证中│
└─────────────────────────────────────────┘
```

---

## 六、数据格式契约

### 6.1 单股结构 JSON（`structure_{code}.json`）
```json
{
  "code": "600188",
  "lv": "K_60M",
  "update_time": "2026-10-20 14:00:00",
  "klines": [
    {"idx":0,"time":"2026-09-01 10:30","o":10.1,"h":10.3,"l":10.0,"c":10.2}
  ],
  "bi": [
    {"idx":0,"dir":"up","is_sure":true,"begin_idx":0,"end_idx":5,"begin_val":10.1,"end_val":11.0}
  ],
  "zs": [
    {"idx":0,"begin_idx":5,"end_idx":20,"low":10.2,"high":10.8}
  ],
  "bsp": [
    {"idx":0,"klu_idx":25,"is_buy":true,"type":"T2","is_sure":true,"price":10.35}
  ]
}
```

### 6.2 荐股榜 JSON（`pool_signals.json`）
见 §4.1。

### 6.3 回测结果 JSON（`backtest/{code}_{rule}.json`）
见 §4.4。

### 6.4 预测/验证 JSONL（`predictions.jsonl`）
见 §4.5 / §4.6。

### 6.5 持仓池说明（已废弃独立池）

~~`chanlun_pool.json` 独立池~~（2026-10-23 用户改口）：缠论持仓池**共享 `SsaT0PoolStore`**，增删在做T页操作，缠论页只读。

---

## 七、开发计划与里程碑

### 阶段 0：环境与 chan.py 落地（0.5 天）
- [ ] 将 `Vespa314/chan.py` 克隆到 `smalltools/chan_py/`（或作为 submodule），验证 Python 3.11+ 可用。
- [ ] 写最小验证脚本：对 `kline_store.json` 中 600188 跑 `compute_structure`，输出笔/中枢/买卖点，人工校验。
- [ ] 确认无外部联网（`trigger_load` 喂本地 K 线）。

### 阶段 1：PC 端引擎层（1.5 天）
- [ ] `smalltools/_chanlun_engine.py`：`compute_structure(code, lv)` 封装，输出统一 JSON；`lv` 支持 1H 与日线（日线由 1H 聚合或单独拉取）。
- [ ] `smalltools/_chanlun_scan.py`：**核心池**扫描（`core_stock_pool.json`）→ `pool_signals.json`；全池为可选开关。
- [ ] `smalltools/_chanlun_backtest.py`：回测引擎（规则可配）。
- [ ] `smalltools/_chanlun_predict.py` + `_chanlun_verify.py`：预测与验证（手动批量触发）。
- [ ] 接入 `_market_scan` 定时调度（盘中每小时跑 scan，盘前 08:00 跑持仓池结构）。

### 阶段 2：Android UI 骨架（1 天）
- [ ] `SsaAnalysisFragment` 增加第 4 模式按钮 + `chanlunHost`。
- [ ] 新建 `ChanlunFragment.kt`：子模式按钮行 + 内容宿主。
- [ ] 复用 MPAndroidChart 渲染层：封装 `ChanlunChartRenderer`（K 线 + 笔折线 + 中枢矩形 + 买卖点圆点）。

### 阶段 3：Android 六大功能页（2 天）
- [ ] 荐股页：读 `pool_signals.json`（核心池），列表 + 点击跳单股。
- [ ] 单股页：结构 JSON 渲染 + 状态卡 + 结构列表 Tab；**级别选择 1H线（默认）/日线**。
- [ ] 持仓池页：读 `SsaT0PoolStore.load()`（共享做T池），本地引擎算状态，风险分级表格。
- [ ] 回测页：参数表单 + 结果卡（调用 `_chanlun_backtest`）。
- [ ] 预测页：**手动勾选批量生成** + 历史列表。
- [ ] 验证页：验证结果列表 + 准确率统计。

### 阶段 4：联调与验证（1 天）
- [ ] PC 端对 171 只 1H 池跑 scan，人工抽检 5 只结构正确性。
- [ ] Android 安装验证：6 个子模式均可打开、图表渲染、数据刷新。
- [ ] 回测对 3 只代表票（趋势/震荡/下跌）跑通，输出报告。

**合计约 6 人日**。

---

## 八、风险与对策

| 风险 | 影响 | 对策 |
|------|------|------|
| chan.py `is_sure=False` 信号消失 | 荐股/持仓预警误报 | UI 明确标「待确认」，策略默认只信 `is_sure=True` |
| 全池扫描性能（688只×1H几千根K线） | 盘中延迟 | 缓存每只结构，只对最新 K 线 `trigger_load` 增量；多进程并行 |
| GPLv3 传染 | APK 许可证风险 | chan.py 仅跑 PC 端 smalltools，不进 APK；输出 JSON 是数据不受 GPL 约束 |
| 买卖点主观性（参数敏感） | 结果不一致 | 固化默认参数到 `chanlun_config.json`，提供 2~3 套预设（激进/稳健/经典） |
| 预测准确率低 | 功能可信度差 | 预测明确标「情景推演非投资建议」，验证模块持续校准概率 |
| Python 3.11 环境 | PC 端依赖 | smalltools 已有 Python 环境，验证版本即可；不满足时提示 |

---

## 九、已确认口径（2026-10-20 用户拍板）

1. **级别**：单股页级别选择分两个选项 **1H线 / 日线**，**默认 1H线**（与现有 1H K 线池一致；日线按需从 1H 聚合或单独拉取）。
2. **荐股池范围**：先荐 **核心池**（`app/src/main/assets/core_stock_pool.json`），全 688 只按需。
3. **持仓来源**：**共享三层稳态做T持仓池**（`SsaT0PoolStore`，2026-10-23 用户改口，原为独立池 → 改为共享，避免两套持仓维护）。缠论持仓池页直接读 `SsaT0PoolStore.load()`，本地引擎算缠论状态+风险分级，增删在做T页操作。
4. **预测刷新**：**手动选择批量刷新**（用户勾选若干票 → 点「批量生成预测」），不做定时批量。
5. **卖点预警**：**先只在 Tab 内展示**，不接推送通道；稳定后再评估是否接 `_publish_candidates`。

---

*本方案基于 chan.py 开源版（Vespa314/chan.py）API 实测整理，策略部分为自研组装。*
