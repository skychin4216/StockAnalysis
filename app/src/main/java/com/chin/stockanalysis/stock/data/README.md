# data/ — 多源数据仓储

## 文件

| 文件 | 说明 |
|------|------|
| `MultiSourceStockRepository.kt` | **核心仓储**：并发请求5个数据源，取最快返回 + 智能缓存 |
| `StockDataSource.kt` | 数据源接口（fetchRealtime / isAvailable / priority） |
| `StockDataSourceFactory.kt` | 工厂：创建默认5源仓储（新浪/腾讯/东方财富/聚宽/AKShare） |
| `StockRepository.kt` | 旧版单源仓储（按优先级顺序降级） |
| `StockCache.kt` | 基础缓存 |
| `SmartStockCache.kt` | 智能缓存：交易中 TTL=1s，盘后 TTL=5min，预取 TTL=10min |
| `EnhancedStockDataProvider.kt` | 增强数据提供者 |
| `HttpClientProvider.kt` | OkHttp 客户端单例（连接池复用） |
| `sources/` | 各数据源实现（新浪、腾讯、东方财富、聚宽、AKShare） |

## 工作流程

```
MultiSourceStockRepository.getRealtime(["sh600519"])
  ├─ 1. 查 SmartStockCache（智能 TTL）
  ├─ 2. 并发请求所有健康源
  │    ├─ async { SinaSource }
  │    ├─ async { TencentSource }
  │    ├─ async { EastMoneySource }
  │    ├─ async { JoinQuantSource }
  │    └─ async { AKShareSource }
  ├─ 3. 取第一个有数据的（按历史平均响应时间排序）
  ├─ 4. 写入缓存
  └─ 5. 返回