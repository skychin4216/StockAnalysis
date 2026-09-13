# Leader Monitor Skill — 板块龙头异动监测

## 模块职责
后台周期扫描热门板块的龙头股涨跌幅，当龙头异动（大涨/领涨）时：
1. 推送系统通知（`TradeNotifier`）
2. 更新内存信号缓存 `SectorSignalStore`，供选股 Pipeline 实时查询（顺势加分/弱势过滤）

## 触发条件
用户提到以下任一关键词时，优先查阅本 Skill：
- 龙头异动 / 龙头监测 / 板块异动 / 龙头股通知
- 为什么没收到/多次收到龙头异动通知
- 监测哪只龙头 / 板块第一名 / 只监测前三名
- 一键建仓中的龙头信号 / 板块强势评分

## 关键文件
| 文件 | 职责 |
|------|------|
| `app/src/main/java/com/chin/stockanalysis/strategy/monitor/SectorLeaderMonitor.kt` | 核心监测逻辑（`object` 单例），含 `SectorSignalStore` 信号缓存 |
| `app/src/main/java/com/chin/stockanalysis/stock/data/sources/EastMoneyHotSectorSource.kt` | 东方财富热门板块/板块龙头数据源（网络请求） |
| `app/src/main/java/com/chin/stockanalysis/notification/TradeNotifier.kt` | 通知推送（发送到系统通知栏 + 主界面广播） |
| `app/src/main/java/com/chin/stockanalysis/stock/database/AppBackgroundRunner.kt` | 应用后台启动入口（第 120~124 行调用 `startMonitor`） |
| `app/src/main/java/com/chin/stockanalysis/strategy/topology/pipelines/QuantTradingPipeline.kt` | 选股时消费 `SectorSignalStore`（约第 1645 行板块龙头异动信号融合） |

## 核心常量（SectorLeaderMonitor 顶部）
| 常量 | 默认值 | 含义 |
|------|--------|------|
| `LEADER_SURGE_THRESHOLD` | 5.0 | 龙头涨幅达到此百分比触发"大涨"信号 |
| `LEADER_TOP_N` | 3 | 每板块监测前 N 名龙头（用户要求保持 3 不改） |
| `LEADER_SCAN_SECTOR_LIMIT` | 30 | 单次扫描的最大板块数量 |
| `SCAN_INTERVAL_TRADING_MS` | 10 分钟 | 交易时段扫描间隔 |
| `SCAN_INTERVAL_IDLE_MS` | 30 分钟 | 非交易时段扫描间隔 |
| `SECONDARY_LEADER_THRESHOLD` | 3.0 | 次龙头（第 2、3 名）触发信号阈值 |

## 工作原理 / 数据流
```
AppBackgroundRunner (应用启动)
  └─> SectorLeaderMonitor.startMonitor(appContext, scope)   // 周期调度
        └─> scanOnce(appContext)                            // 单次扫描
              ├─> EastMoneyHotSectorSource.fetchHotSectors()   // 获取热门板块列表(≤30个)
              ├─> for 每个板块: fetchSectorLeaders(code, 3, "f3")  // 串行拉取前三名龙头
              ├─> 比对 SectorSignalStore 上次状态，判断异动
              ├─> 满足阈值 → TradeNotifier.send(...)           // 推送通知
              └─> 更新 SectorSignalStore                       // 供选股实时查询
选股时: QuantTradingPipeline → StockDataCenter.getSectorsByStock → SectorSignalStore.getSignal(...)
```

## 耗时分析（用户关注点）
- **耗时瓶颈**：`scanOnce` 对最多 30 个板块**串行**发 HTTP 请求，每请求约 200ms~1s，最坏约 10~30 秒（`Dispatchers.IO`，不卡 UI）。
- `LEADER_TOP_N` 从 3 改 1 对耗时**几乎无影响**（请求次数由板块数决定，与 `pz` 参数无关）。
- 优化手段：改为并发拉取（如 8~10 并发 async）可降到约 1~3 秒；或减少板块扫描数量。

## 常见任务指引
### 1. 通知合并（同一次扫描只发一条）
当前 `scanOnce` 循环内**每个板块**异动都会单独 `TradeNotifier.send`，一次扫描多个板块异动 = 多条通知。
修复：在 `scanOnce` 内先收集所有异动到列表，循环结束后**合并成一条**通知发送（body 列出所有板块龙头）。

### 2. 只监测第一名
把 `fetchSectorLeaders(sector.code, LEADER_TOP_N, "f3")` 的第二个参数改为 `1`，并删除次龙头判定分支。

### 3. 修改扫描频率
调 `SCAN_INTERVAL_TRADING_MS` / `SCAN_INTERVAL_IDLE_MS`。

### 4. 手动触发一次扫描
`SectorLeaderMonitor.scanOnce(appContext)`（可在 adb shell 或调试入口调用）。

## 文件清单
- `skills/leader-monitor/README.md` — 本文件（skill 定义）
