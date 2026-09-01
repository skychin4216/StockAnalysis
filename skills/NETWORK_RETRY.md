# 网络问题自动重试约定（全局 Skill）

> **适用范围**：本项目中所有涉及外部 HTTP/API 请求的任务。凡是拉取行情、财务、新闻、公告、研报、F10、数据中心、COS 上传下载、微信推送等，遇到网络异常必须自动重试，不允许一次失败就直接放弃或报错给用户。

## 触发条件

执行任务过程中出现以下任意一种情况，即视为「网络问题」，必须自动重试：

| 类型 | 特征 |
|------|------|
| 连接超时 | `TimeoutError` / `socket.timeout` / 连接超时 |
| 连接重置 | `ConnectionResetError` / `ConnectionError` / 对端重置 |
| DNS 失败 | `socket.gaierror` / 无法解析主机名 |
| HTTP 5xx | 服务端错误（500/502/503/504） |
| 限流 | 429 Too Many Requests |
| 请求被拒绝 | 408 Request Timeout / 触发反爬验证 |
| 空数据 | 接口成功但返回空列表，且该数据源刚在其他请求中可用（可疑屏蔽） |

## 重试策略（默认）

```
GET 请求（幂等）：
  重试 3 次，指数退避：0.5s → 1.0s → 2.0s（各加 0~0.3s 随机抖动）
POST 请求：仅当接口明确幂等（如查询类 POST）才重试，否则只报错不重试

超时默认值：
  连接超时 5s，读取超时 15s

不重试的错误：
  - HTTP 4xx 业务错误（400/401/403/404）——重试无意义，直接记录
  - 数据解析错误（JSON 结构不符）——不重试，但要打印原始响应前 200 字符辅助排查
```

## 多数据源回退（行情类）

按以下顺序回退，每个源失败按重试策略重试后再切换：

```
腾讯 K 线 (qt.gtimg.cn) → 东方财富 K 线 (push2his.eastmoney.com) → 新浪 K 线 (money.finance.sina.com.cn)
东财实时 (push2.eastmoney.com) → 腾讯实时 (qt.gtimg.cn)
```

参考实现：`smalltools/backtest_guangmo.py` 的 `fetch_tencent` / `fetch_east` / `fetch_kline`（已内置重试+回退，新脚本应复用，不要另起炉灶）。

## 落地位置（已内置/待补强）

| 位置 | 说明 |
|------|------|
| `smalltools/backtest_guangmo.py` | K 线抓取已含重试+回退，**新抓取脚本优先 import 复用** |
| `smalltools/cos_utils.py` | COS 签名 PUT/GET 需带重试（网络抖动常见） |
| `smalltools/_publish_candidates.py` | pushplus/serverchan 推送失败重试 3 次再放弃 |
| `smalltools/_news_fetch.py` / `_announce_fetch.py` | 新闻/公告抓取需重试 |
| `app/.../stock/data/sources/*.kt` | APK 行情源 Kotlin，OkHttp 失败重试 |
| `app/.../strategy/data/FactorDataProvider.kt` | 财务/资金流/股东户数/北向等接口，网络异常自动重试 |
| `AutoQuant/autoquant/data.py` | exe 数据源拉取重试 |

## 执行要求

1. **跑批量脚本时**：单只股票失败不中断整体，记录后继续下一只；最后汇总失败清单。
2. **给用户报告时**：重试成功则正常输出；若重试 3 次仍失败，说明「已自动重试 3 次仍失败 + 失败数据源 + 建议」，而不是直接报错。
3. **新写脚本**：默认带上 `retry(times=3, backoff=[0.5,1,2])` 装饰器或等价逻辑。
