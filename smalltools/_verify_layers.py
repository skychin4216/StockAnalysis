# -*- coding: utf-8 -*-
# [参考归档] 一次性验证脚本（2026-09-03 用户确认留档，勿删/勿当正式流程运行）
#   用途: 校验 缓存池(_kline_cache) vs 全市场快照(_market_snapshot) vs 分层清单(_pool_layers) 一致性
#   用法: 在 smalltools 目录下运行（依赖 ../data/_market_snapshot.json、_pool_layers.json）
#   转正: 无 —— 分层清单正式产出在 _market_snapshot.py
import json, os

snap = json.load(open(os.path.join('..', 'data', '_market_snapshot.json'), encoding='utf-8'))
layers = json.load(open(os.path.join('..', 'data', '_pool_layers.json'), encoding='utf-8'))
cache = json.load(open('_kline_cache.json', encoding='utf-8'))

miss = [c for c in cache if c not in snap and not c.startswith(('sh000', 'sz399'))]
print('缓存 %d 只, 不在快照 %d 只' % (len(cache), len(miss)))
print('  缺失样例:', miss[:10])

def board_of(secid):
    n = secid[2:]
    if n.startswith(('688', '689')): return '科创'
    if n.startswith(('300', '301')): return '创业'
    return '主板'

for ind in ['通用设备', '半导体', '软件开发', '光学光电子']:
    v = layers['sectors'].get(ind)
    if not v:
        continue
    small_ok = [s for s in v['small'] if s['mv_total_yi'] <= 100]
    print('\n[%s] 总数%d 小票候选(<=100亿) %d 只' % (ind, v['n_total'], len(small_ok)))
    for s in sorted(small_ok, key=lambda x: x['mv_total_yi'])[:6]:
        print('   %s %-6s %s 价%.2f 市值%.0f亿 换手%.1f%%' % (
            board_of(s['secid']), s['name'], s['secid'], s['price'], s['mv_total_yi'], s['turnover']))
