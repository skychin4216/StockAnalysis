---
name: tmp-file-hygiene
description: 一次性/探针脚本（smalltools/_tmp*.py、_probe*.py）的收尾处置策略：任务做完先判定“转正 or 删除”，需要转正的改名为正式脚本并登记 SCRIPTS.md，一次性脚本由 AI 自行判断并直接删除，禁止等待用户审批。当用户说“清理临时文件”“一次性文件怎么处理”“_tmp 要不要留”“帮我删探针脚本”等时使用。
---

# 一次性/探针脚本收尾策略

## 规则（2026-09-06 制定，2026-09-11 修订）

1. **命名即声明**：只跑一次、任务性、验证性的脚本必须用前缀 `_tmp*` / `_probe*`，放 `smalltools/`。
2. **任务收尾必审**：每轮改动结束前，遍历本次产生的 `_tmp*.py`/`_probe*.py`，逐个判定：
   - **后续还要用（含要复用的采集能力）→ 转正**：改名为正式名（去前缀），补完整 docstring，并在
     `smalltools/SCRIPTS.md`（及对应 `skills/<模块>/README.md`）登记用法。仓库既有先例：
     `_tmp_dbschema.py → inspect_market_db.py`、`_tmp_dbverify.py → verify_market_db.py`（SCRIPTS.md 内注明“由 _tmp_xxx.py 转正”）。
   - **一次性/已被正式代码取代 → 直接删除，不用问用户**。
3. **删除自行决定，不得等待审批（2026-09-11 用户明确授权）**：
   删除临时文件是**收尾动作的默认一部分**，AI 必须自行判断并直接执行，**不要**先问用户“要不要删”、
   **不要**列清单等用户批准、**不要**把“等审批”当成收尾方式。
   - 优先用 `delete_file` 工具删除；被拒绝或超时就改用 `Remove-Item -Force -ErrorAction SilentlyContinue` 批量删。
   - 删除前只需自查一件事：该文件是否被正式代码 / skills / SCRIPTS.md 引用（`search_content` 一次即可）。
     无引用 → 删；有引用 → 说明它其实已在用，走“转正”路径而不是删。
   - 只有**确实删不掉**（权限/审批超时都失败）时，才在回复末尾附一条汇总 PowerShell 命令，
     并明确说明“删除未成功”，不要含糊带过。
4. 数据产物（json/db/图片）不是脚本，不在本策略内；但**由一次性脚本生成、且已被正式实现取代的中间产物**
   可随脚本一并清理（如 `_probe_boards_tmp.json`、`_holder_progress.json`）。

## 流程速查

```
改动收尾 → 列出本轮新增/遗留的 _tmp* / _probe*
        → 逐个 search_content 查引用
        → 无引用 → delete_file（失败则 Remove-Item）
        → 有复用价值 → 转正 + 登记 SCRIPTS.md
        → 在总结里一句话交代“删了什么 / 转正了什么”
```

## 2026-09-11 清点记录

**已删除 10 件**（均经 `search_content` 自查无代码/skills 引用）：

```
smalltools/_tmp_probe_global.py   # 全球指数/商品/汇率接口探针 → 能力已被 _daily_intel.py 吸收
smalltools/_probe_chart.py        # 历史遗留探针
smalltools/_probe_db.py           # 历史遗留探针
smalltools/_probe_hs300.py        # 历史遗留探针
smalltools/_probe_log.py          # 历史遗留探针
smalltools/_probe_review.py       # 历史遗留探针
smalltools/_probe_run.py          # 历史遗留探针
smalltools/_probe_run2.py         # 历史遗留探针
smalltools/_probe_run3.py         # 历史遗留探针
smalltools/_probe_sel.py          # 历史遗留探针
```

**保留 1 件**：`smalltools/_probe_indices.py` —— 文件头已注明
`[参考归档] 探测脚本（2026-09-03 用户确认留档，勿删）`，属用户点名留档，不在清理范围。
**教训**：删除前除查引用外，还要看文件头是否有「留档/勿删」标注。

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
