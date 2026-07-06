# 數據層架構設計

## 總體架構

數據層採用 **多源並發 + 分級緩存** 策略，通過 StockDataFacade 統一封裝，對外提供單一接口，內部並行請求多個數據源取最快返回。

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         數據層總體架構                                       │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    StockDataFacade（統一入口）                       │   │
│  │                                                                     │   │
│  │  getAnalysisData(stockCode)                                         │   │
│  │    ├─ getRealtimeQuotes()  ──→ 5源並發行情                          │   │
│  │    ├─ getFundamental()     ──→ 基本面數據                           │   │
│  │    ├─ getIndicators()      ──→ 技術指標                             │   │
│  │    ├─ getSectorHeat()      ──→ 板塊熱度                            │   │
│  │    └─ getNews()            ──→ 相關新聞                            │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│              ┌───────────────┼───────────────┐                             │
│              ▼               ▼               ▼                             │
│  ┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐               │
│  │   實時行情層     │ │   基本面數據層   │ │   板塊/新聞層   │               │
│  │                 │ │                 │ │                 │               │
│  │ RawRealtime     │ │ FactorData      │ │ EastMoney       │               │
│  │ F10StockData    │ │ Institutional   │ │ NewsService     │               │
│  │ SmartStockCache │ │ RatingProvider  │ │ SectorHeatSource│               │
│  └─────────────────┘ └─────────────────┘ └─────────────────┘               │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │              HttpClientProvider（網絡層）                            │   │
│  │                                                                     │   │
│  │  realtimeClient ──→ OkHttpClient(15s超時) 實時行情請求             │   │
│  │  aiClient       ──→ OkHttpClient(60s超時)  AI模型請求              │   │
│  │  downloadClient ──→ OkHttpClient(30s超時)  文件下載                │   │
│  │                                                                     │   │
│  │  baseUrl: DataConfig.eastmoneyDatacenter / eastmoney / sina / xueqiu│   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

## 5源並發實時行情

```
┌─────────────────────────────────────────────────────────────┐
│                    5源並發行情獲取                            │
│                                                             │
│  RawRealtimeProvider.getRealtime(codes, scope)              │
│       │                                                     │
│       ├── EastMoneyRawQuoteSource ──→ 東方財富實時行情       │
│       │      url: push2.eastmoney.com/api/qt/stock/get      │
│       │      特點: 毫秒級延遲，最權威                        │
│       │                                                     │
│       ├── SinaQuoteSource ──→ 新浪行情                      │
│       │      url: hq.sinajs.cn/list=sh600519                │
│       │      特點: 簡潔快速，備用源                          │
│       │                                                     │
│       ├── XueqiuQuoteSource ──→ 雪球行情                    │
│       │      url: stock.xueqiu.com/v5/stock/batch/quote     │
│       │      特點: 含漲跌停統計                              │
│       │                                                     │
│       ├── NetEaseQuoteSource ──→ 網易行情                   │
│       │      特點: 備用源                                   │
│       │                                                     │
│       └── SohuQuoteSource ──→ 搜狐行情                      │
│              特點: 備用源                                   │
│                                                             │
│       └─→ 取最先返回的 3 個結果合併                          │
│           各字段採用置信度優先策略（交易所 > 綜合門戶）       │
└─────────────────────────────────────────────────────────────┘
```

## 基本面數據

```
┌─────────────────────────────────────────────────────────────┐
│                    基本面數據獲取                             │
│                                                             │
│  F10StockDataProvider.getF10Data(stockCode)                 │
│       │                                                     │
│       ├── 實時行情數據                                       │
│       │      ├─ 當前價格、漲跌幅、市值、PE、PB              │
│       │      └─ 52週高點/低點、成交額、換手率               │
│       │                                                     │
│       ├── 機構評級數據（InstitutionalRatingProvider）        │
│       │      ├─ 機構評級分佈（買入/增持/中性/減持）          │
│       │      ├─ 平均目標價                                  │
│       │      └─ 盈利預測數據                                │
│       │                                                     │
│       └── 東方財富數據中心                                   │
│              ├─ F10主財務數據 (MAINFINADATA)               │
│              ├─ 利潤表數據 (INCOMESTMT)                    │
│              ├─ 資產負債表 (BALANCE SHEET)                 │
│              └─ 現金流量表 (CASHFLOW)                      │
│                                                             │
│       └─→ 合併為 FundamentalData 對象返回                   │
└─────────────────────────────────────────────────────────────┘
```

## 季度环比數據（新增）

```
┌─────────────────────────────────────────────────────────────┐
│                   季度环比數據獲取                            │
│                                                             │
│  QuarterlyComparisonProvider.fetch(stockCode)               │
│       │                                                     │
│       ├── 東方財富 API                                      │
│       │      reportName=RPT_F10_FINANCE_MAINFINADATA       │
│       │      pageSize=6（覆蓋1年+）                         │
│       │      sortColumns=REPORT_DATE&sortTypes=-1           │
│       │                                                     │
│       ├── 解析響應                                          │
│       │      ├─ REPORT_TYPE: 一季報/中報/三季報/年報        │
│       │      ├─ PARENTNETPROFIT: 歸母淨利潤                 │
│       │      ├─ TOTALOPERATEREVE: 營業收入                  │
│       │      ├─ DJD_DPNP_QOQ: 單季淨利潤環比(%)            │
│       │      ├─ DJD_DEDUCTDPNP_QOQ: 扣非淨利潤環比(%)      │
│       │      └─ DJD_TOI_QOQ: 單季營收環比(%)               │
│       │                                                     │
│       ├── 篩選最近兩個非年報季度                             │
│       │                                                     │
│       ├── 計算/提取環比增速                                  │
│       │      優先使用 API 返回值，缺失時手動計算             │
│       │                                                     │
│       └── 趨勢判定 + 評分調整                                │
│                                                             │
│       5分鐘內存緩存，key=stockCode                          │
└─────────────────────────────────────────────────────────────┘
```

## 智能緩存策略

```
┌─────────────────────────────────────────────────────────────┐
│                  SmartStockCache 緩存策略                     │
│                                                             │
│  根據 A股交易時段動態計算 TTL：                               │
│                                                             │
│  交易中 (09:30-15:00)  → TTL = 1秒                          │
│  盤後   (15:00-22:00)  → TTL = 5分鐘                       │
│  夜間   (22:00-09:30)  → TTL = 30分鐘                      │
│  週末/節假日           → TTL = 1小時                        │
│                                                             │
│  緩存淘汰: LRU (Least Recently Used)                        │
│  最大容量: 200 條                                           │
└─────────────────────────────────────────────────────────────┘
```

## 數據配置中心

```
┌─────────────────────────────────────────────────────────────┐
│                     DataConfig 配置中心                       │
│                                                             │
│  eastmoneyDatacenter: "datacenter-web.eastmoney.com"        │
│  eastmoney: "push2.eastmoney.com"                           │
│  sina: "hq.sinajs.cn"                                       │
│  xueqiu: "stock.xueqiu.com"                                 │
│  netease: "api.money.126.net"                               │
│  sohu: "q.stock.sohu.com"                                   │
│  openai: "api.openai.com"                                   │
│  deepseek: "api.deepseek.com"                               │
│  siliconflow: "api.siliconflow.cn"                          │
│                                                             │
│  deepseekKey / siliconflowKey / doubaoKey / aliyunKey       │
│  （從 assets/api_keys_local.properties 讀取）                │
└─────────────────────────────────────────────────────────────┘
```

## 文件清單

| 文件 | 路徑 | 說明 |
|------|------|------|
| StockDataFacade | `stock/data/StockDataFacade.kt` | 數據層統一入口 |
| HttpClientProvider | `stock/data/HttpClientProvider.kt` | OkHttp 客戶端管理 |
| DataConfig | `config/DataConfig.kt` | API 配置與密鑰管理 |
| RawRealtimeProvider | `stock/data/RawRealtimeProvider.kt` | 5源並發行情 |
| F10StockDataProvider | `stock/data/F10StockDataProvider.kt` | F10 基本面數據 |
| FactorDataProvider | `strategy/data/FactorDataProvider.kt` | 因子數據 |
| InstitutionalRatingProvider | `strategy/data/InstitutionalRatingProvider.kt` | 機構評級 |
| QuarterlyComparisonProvider | `agent/pipeline/QuarterlyComparisonProvider.kt` | 季度环比數據 |
| SmartStockCache | `stock/cache/SmartStockCache.kt` | 智能緩存 |
