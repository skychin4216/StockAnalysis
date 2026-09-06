---
name: tmp-file-hygiene
description: 一次性/探针脚本（smalltools/_tmp*.py、_probe*.py）的收尾处置策略：任务做完先判定“转正 or 删除”，需要转正的改名为正式脚本并登记 SCRIPTS.md，一次性脚本直接删除、无需征求用户同意。当用户说“清理临时文件”“一次性文件怎么处理”“_tmp 要不要留”“帮我删探针脚本”等时使用。
---

# 一次性/探针脚本收尾策略

## 规则（2026-09-06 用户确认）

1. **命名即声明**：只跑一次、任务性、验证性的脚本必须用前缀 `_tmp*` / `_probe*`，放 `smalltools/`。
2. **任务收尾必审**：每轮改动结束前，遍历本次产生的 `_tmp*.py`/`_probe*.py`，逐个判定：
   - **后续还要用（含要复用的采集能力）→ 转正**：改名为正式名（去前缀），补完整 docstring，并在
     `smalltools/SCRIPTS.md`（及对应 `skills/<模块>/README.md`）登记用法。仓库既有先例：
     `_tmp_dbschema.py → inspect_market_db.py`、`_tmp_dbverify.py → verify_market_db.py`（SCRIPTS.md 内注明“由 _tmp_xxx.py 转正”）。
   - **一次性/已被正式代码取代 → 直接删除，不用问用户**。
3. **本环境删除受限**：`delete_file`/`Remove-Item` 会触发审批且常超时。删除失败不算“已删”，收尾时若删不掉，
   给用户一条汇总的 PowerShell 删除命令并写明待删清单，不要把文件留在原地当已处理。
4. 数据产物（json/db/图片）不是脚本，不在本策略内。

## 2026-09-06 清点记录

**转正 1 件**：`_holder_signals.py`（社保/国家队/北向股东信号采集，已登记 SCRIPTS.md）——
由同思路的探针验证（`_probe_holders.py`）直接升级为正式工具。

**转正先例回顾**：`_tmp_dbschema.py → inspect_market_db.py`、`_tmp_dbverify.py → verify_market_db.py`。

待删清单（均确认无代码/skills 引用、已被正式实现取代）：

```
smalltools/_tmp_dry_lowbuy.py        # 低吸干跑(仅验证用)
smalltools/_tmp_dry2.py              # 低吸通过量统计(仅验证用)
smalltools/_tmp_ekb.py               # _event_kb 冒烟
smalltools/_tmp_extend10y.py         # 已被 market_data.db 2008 全史取代
smalltools/_tmp_names.py             # 缓存池名称分桶浏览
smalltools/_tmp_news_probe.py        # 新闻接口探测
smalltools/_tmp_probe2008.py         # qfq/hfq 量一致验证
smalltools/_tmp_probe_daginter.py    # DAG 交叉命中探测
smalltools/_tmp_probe_etf.py         # fundmob/腾讯K线探测(已进正式代码)
smalltools/_tmp_probe_longfetch.py   # 长区间拉取验证
smalltools/_tmp_probe_lowdag.py      # 低吸+DAG 探测
smalltools/_tmp_probe_replay.py      # 回放探测
smalltools/_tmp_push_check.py        # 推送链路验证
smalltools/_tmp_resend904.py         # 9/4 重发(一次性动作)
smalltools/_tmp_verify_pos.py        # 60日位置口径验证
smalltools/_probe_board_rank.py _probe_east.py _probe_east2.py _probe_fflow.py
smalltools/_probe_indices.py _probe_ranks.py _probe_tencent_board.py
smalltools/_probe_tencent_rank.py _probe_tmp.py _probe_boards_tmp.json
# 2026-09-06 本轮新增（数据源/拟合可行性验证，均被正式实现取代）
smalltools/_probe_holders.py _probe_nb.py _probe_ss_fields.py
smalltools/_probe_t5span.py _probe_fitcost.py _holder_progress.json
smalltools/_tmp_dsmsg.py          # 09-06 ds精选名单/微信消息(一次性, 已推送完成)
smalltools/_tmp_fitmulti.py       # 2008→今 多变体单扫拟合(一次性, 结果已落 _etf_top5_fit_resonance.json)
```

批量删除命令（在仓库根目录执行）：

```powershell
cd e:\Android\work\dev\StockAnalysis\smalltools
Remove-Item _tmp_*.py, _probe_*.py, _holder_progress.json, _probe_boards_tmp.json -Force -ErrorAction SilentlyContinue
```
