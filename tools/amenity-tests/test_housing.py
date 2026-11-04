# -*- coding: utf-8 -*-
import json
import sys
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'app/src/main/python'))
from amenity.housing import HousingBridge, HousingError, parse_card, parse_listings, summarize, safe_url

CARD = {'communityId': 5011000014068, 'communityName': '华润置地橡树湾',
        'communityDesc': '板楼 | 共87栋 1433户 | 2008-2015年', 'avgUnitPrice': 85985,
        'actionUrl': 'https://sh.ke.com/xiaoqu/5011000014068/'}
ROW = {'desc':'4室2厅/222.4㎡/南/华润置地橡树湾', 'title':'挂牌文字',
       'priceStr':'2180万', 'unitPriceStr':'98022元/平',
       'actionUrl':'https://sh.ke.com/ershoufang/107106229193.html', 'tags':[]}
HIT = {'id':'5011000014068','cityId':'310000','bdLng':121.52631,'bdLat':31.343877}

class Tests(unittest.TestCase):
    def test_actual_observed_card(self):
        r = parse_card(CARD)
        self.assertEqual((r['year'],r['yearRange'],r['buildings'],r['households'],r['price']),
                         (2008,'2008–2015',87,1433,85985))
    def test_empty_card_has_no_defaults(self):
        r=parse_card({})
        self.assertIsNone(r['year']);self.assertIsNone(r['price'])
    def test_sale_only_and_deduplicate(self):
        rows=parse_listings([ROW,ROW,{**ROW,'actionUrl':'https://sh.zu.ke.com/zufang/1.html'}])
        self.assertEqual(len(rows),1);self.assertEqual(rows[0]['area'],222.4)
        self.assertEqual(rows[0]['layout'],'4室2厅');self.assertEqual(rows[0]['totalPrice'],2180)
    def test_missing_fields(self):
        r=parse_listings([{'actionUrl':ROW['actionUrl']}])[0]
        self.assertIsNone(r['area']);self.assertIsNone(r['totalPrice']);self.assertEqual(r['layout'],'')
    def test_reject_urls(self):
        for url in ['javascript:alert(1)','https://ke.com.evil.test/a','http://sh.ke.com/a']:
            self.assertEqual(safe_url(url),'')
    def test_summary(self):
        rows=parse_listings([ROW,{**ROW,'desc':'2室1厅/62.63㎡/南 北/华润置地橡树湾','actionUrl':'https://sh.ke.com/ershoufang/2.html'}])
        s=summarize(rows);self.assertEqual(s['sampleCount'],2);self.assertEqual(s['northSouthCount'],1)
        self.assertEqual((s['areaMin'],s['areaMax']),(62.63,222.4))
    def test_identity_rejected(self):
        bridge=HousingBridge()
        with patch.object(bridge,'request',return_value={'resblockCard':{**CARD,'communityId':123},'list':[]}):
            with self.assertRaises(HousingError):bridge.detail(HIT)
    def test_paginated_deduplication(self):
        bridge=HousingBridge()
        pages=[{'resblockCard':CARD,'list':[ROW],'total':2,'hasMore':1},
               {'resblockCard':CARD,'list':[ROW],'total':2,'hasMore':0}]
        with patch.object(bridge,'request',side_effect=pages):r=bridge.detail(HIT)
        self.assertEqual(r['pages'],2);self.assertEqual(r['summary']['sampleCount'],1)
        self.assertFalse(r['truncated']);self.assertNotIn('layout',r['card'])
    def test_invalid_id(self):
        with self.assertRaises(HousingError):HousingBridge().detail({**HIT,'id':'x'})
    def test_requires_connection(self):
        with self.assertRaises(HousingError):HousingBridge().search({'name':'橡树湾','cityId':'310000'})
    def test_pairing(self):
        bridge=HousingBridge()
        with self.assertRaises(HousingError):bridge.poll('wrong','310000')
        bridge.poll(bridge.token,'310000');self.assertTrue(bridge.status()['connected'])
    def test_city_guard(self):
        bridge=HousingBridge();bridge.poll(bridge.token,'310000')
        with self.assertRaises(HousingError):bridge.search({'name':'橡树湾','cityId':'110000'})

if __name__=='__main__':unittest.main(verbosity=2)
