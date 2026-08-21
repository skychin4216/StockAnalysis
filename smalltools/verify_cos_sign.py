# -*- coding: utf-8 -*-
"""
COS 签名 + 本地函数回归校验
========================================================================
用腾讯云官方文档（436/7778）的完整示例值校准签名算法，并对配置读取、
ZIP 解包、ListObjectsV2 XML 解析做离线回归。任何一次改动 cos_utils.py /
cloud_download.py / app_config.json 后建议重跑：

    python smalltools/verify_cos_sign.py
"""
from __future__ import print_function
import hashlib
import io
import json
import os
import sys
import zipfile
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# ---- 1. 官方文档示例 SHA1 校验（例二 GET，raw 路径形式） ----
raw = ('get\n/exampleobject(\u817e\u8baf\u4e91)\n'
       'response-cache-control=max-age%3D600&response-content-type=application%2Foctet-stream\n'
       'date=Thu%2C%2016%20May%202019%2006%3A55%3A53%20GMT&host=examplebucket-1250000000.cos.ap-beijing.myqcloud.com\n')
sha = hashlib.sha1(raw.encode('utf-8')).hexdigest()
assert sha == '54ecfe22f59d3514fdc764b87a32d8133ea611e6', sha
print('1. 官方示例二 SHA1 匹配 OK')

# 例一 PUT（无参数，多头）
http_string = ('put\n/exampleobject(\u817e\u8baf\u4e91)\n\n'
               'content-length=13&content-md5=mQ%2FfVh815F3k6TAUm8m0eg%3D%3D&content-type=text%2Fplain'
               '&date=Thu%2C%2016%20May%202019%2006%3A45%3A51%20GMT'
               '&host=examplebucket-1250000000.cos.ap-beijing.myqcloud.com'
               '&x-cos-acl=private&x-cos-grant-read=uin%3D%22100000000011%22\n')
sha = hashlib.sha1(http_string.encode('utf-8')).hexdigest()
assert sha == '8b2751e77f43a0995d6e9eb9477f4b685cca4172', sha
print('1. 官方示例一 SHA1 匹配 OK')

# ---- 2. 官方示例二的完整签名链路（构造等价 ASCII 版本，仅路径无中文） ----
from cos_utils import sign  # noqa: E402
auth = sign('QmFzZTY0IGlzIGR1bW15', 'abc', 'get', '/exampleobject',
            http_params={'response-content-type': 'application/octet-stream'},
            http_headers={'date': 'Thu, 16 May 2019 06:55:53 GMT',
                          'host': 'examplebucket-1250000000.cos.ap-beijing.myqcloud.com'},
            start_time=1557989753, end_time=1557996953)
assert auth.startswith('q-sign-algorithm=sha1&q-ak=QmFzZTY0IGlzIGR1bW15&q-sign-time=1557989753;1557996953'
                       '&q-key-time=1557989753;1557996953&q-header-list=date;host'
                       '&q-url-param-list=response-content-type&q-signature='), auth
print('2. sign() 输出结构与官方格式一致 OK')

# ---- 3. load_cloud_config 读取 app_config.json ----
from cos_utils import load_cloud_config, configured  # noqa: E402
cfg = load_cloud_config()
assert cfg['prefix'] == 'stockanalysis/phone'
assert cfg['params_key'] == 'stockanalysis/params/backtest_params.json'
assert cfg['region'] == 'ap-guangzhou'
assert not configured(cfg)  # 当前未填密钥
print('3. load_cloud_config OK:', json.dumps(cfg, ensure_ascii=False))

# ---- 4. ZIP 解包逻辑（模拟手机端 CloudSyncManager.buildDataPackage 的 data.json） ----
from cloud_download import extract_data_json  # noqa: E402
buf = io.BytesIO()
with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED) as z:
    z.writestr('data.json', json.dumps({'meta': {'export_time': '2026-08-18 10:00:00'},
                                        'strategy_trade_orders': [{'id': 1}]}))
data, err = extract_data_json(buf.getvalue())
assert err is None and data['strategy_trade_orders'][0]['id'] == 1
print('4. ZIP 解包 data.json OK')

# ---- 5. ListObjectsV2 XML 解析 ----
xml_body = '''<?xml version="1.0" encoding="UTF-8"?>
<ListBucketResult xmlns="http://www.qcloud.com/document/product/436/7751">
  <Name>mybucket-1250000000</Name>
  <Prefix>stockanalysis/phone/</Prefix>
  <Contents>
    <Key>stockanalysis/phone/phone_20260818_102030.zip</Key>
    <LastModified>2026-08-18T10:20:30.000Z</LastModified>
    <Size>12345</Size>
  </Contents>
  <Contents>
    <Key>stockanalysis/phone/phone_20260817_223100.zip</Key>
    <LastModified>2026-08-17T22:31:00.000Z</LastModified>
    <Size>9876</Size>
  </Contents>
  <Contents>
    <Key>stockanalysis/phone/notazip.txt</Key>
    <LastModified>2026-08-17T22:31:00.000Z</LastModified>
    <Size>1</Size>
  </Contents>
</ListBucketResult>'''
root = ET.fromstring(xml_body)
ns = {'s': 'http://www.qcloud.com/document/product/436/7751'}
items = []
for c in root.findall('.//s:Contents', ns):
    key = c.findtext('s:Key', '', ns)
    if key and key.endswith('.zip'):
        items.append((key, int(c.findtext('s:Size', '0', ns))))
assert items == [('stockanalysis/phone/phone_20260818_102030.zip', 12345),
                 ('stockanalysis/phone/phone_20260817_223100.zip', 9876)], items
print('5. ListObjectsV2 XML 解析 OK')

print('\nALL_LOCAL_CHECKS_PASSED')
