# -*- coding: utf-8 -*-
"""贝壳资料桥：只通过用户主动连接的 map.ke.com 页面读取，不保存登录凭据。"""
import collections
import html
import json
import math
import re
import secrets
import threading
import time
from pathlib import Path
from urllib.parse import urlparse


import statistics
from html.parser import HTMLParser
from .housing_scoring import summary as layout_summary, representatives, aggregate_layout, bucket


class FieldParser(HTMLParser):
    """只提取已观察的公开基础字段与户型分间，不保存页面原文或人员资料。"""
    def __init__(self):
        super().__init__(); self.stack=[]; self.fields={}; self.rooms=[]; self.base={}; self.room=[]
        self.label=''; self.value=''; self.base_text=''; self.room_col=''
    def handle_starttag(self,tag,attrs):
        a=dict(attrs); classes=a.get('class','').split()
        parent=self.stack[-1][1] if self.stack else ''
        mode=parent
        if 'xiaoquInfoLabel' in classes: mode='label'; self.label=''
        elif 'xiaoquInfoContent' in classes: mode='value'; self.value=''
        elif a.get('id')=='infoList':mode='rooms'
        elif mode=='rooms' and 'row' in classes:mode='room';self.room=[]
        elif mode=='room' and 'col' in classes:mode='col';self.room_col=''
        self.stack.append((tag,mode))
    def handle_data(self,data):
        if not self.stack:return
        mode=self.stack[-1][1]
        if mode=='label':self.label+=data
        elif mode=='value':self.value+=data
        elif mode=='col':self.room_col+=data
    def handle_endtag(self,tag):
        if not any(t==tag for t,_ in self.stack):return
        while self.stack:
            t,mode=self.stack.pop()
            if mode=='col' and (not self.stack or self.stack[-1][1]!='col'):
                self.room.append(self.room_col.strip())
            if mode=='room' and (not self.stack or self.stack[-1][1]!='room') and len(self.room)>=4:
                self.rooms.append(dict(zip(['name','area','orientation','window'],self.room[:4])))
                self.room=[]
            if mode=='value' and (not self.stack or self.stack[-1][1]!='value'):
                if self.label.strip():self.fields[self.label.strip()]=self.value.strip()
            if t==tag:break


def parse_profile_fields(fields):
    out={'far':None,'green':None,'fee':None,'feeMin':None,'feeMax':None,
         'feeText':str(fields.get('物业费') or ''),'propertyCompany':str(fields.get('物业公司') or '')}
    for key,label,maximum in [('far','容积率',30),('green','绿化率',100)]:
        text=str(fields.get(label) or '').strip()
        # 区间和缺失不擅自取首个数；绿化率要求明确百分比。
        if re.fullmatch(r'\d+(?:\.\d+)?\s*%?',text) and (key!='green' or '%' in text):
            v=number(text)
            if v is not None and 0<=v<=maximum:out[key]=v
    text=out['feeText'].split('，')[0].strip()
    m=re.fullmatch(r'(\d+(?:\.\d+)?)(?:\s*(?:至|到|[-–~])\s*(\d+(?:\.\d+)?))?\s*元/(?:平米|平方米|㎡)/(?:月)',text)
    if m:
        lo,hi=float(m.group(1)),float(m.group(2) or m.group(1))
        if 0<=lo<=hi<=100:
            out['feeMin'],out['feeMax']=lo,hi
            if lo==hi:out['fee']=lo
    return out


def analyze_floorplan(data):
    rooms=[]
    for r in data.get('rooms',[]):
        v=number(r.get('area'))
        rooms.append({'name':str(r.get('name') or ''),'area':v,
                      'orientation':str(r.get('orientation') or ''),'window':str(r.get('window') or '')})
    living=[r for r in rooms if re.search(r'客厅|卧室|书房',r['name'])]
    known=[r for r in living if r['window'] and '未知' not in r['window']]
    wet=[r for r in rooms if re.search(r'厨房|卫生间',r['name'])]
    return {'rooms':rooms,'livingRoomCount':len(living),'windowKnownCount':len(known),
            'livingWithoutWindow':[r['name'] for r in known if r['window']=='无窗'],
            'unknownWindows':[r['name'] for r in living if not r['window'] or '未知' in r['window']],
            'wetWithoutWindow':[r['name'] for r in wet if r['window']=='无窗'],
            'southBedrooms':sum('南' in r['orientation'] for r in rooms if '卧室' in r['name']),
            'bedroomCount':sum('卧室' in r['name'] for r in rooms),
            'corridorArea':round(sum(r['area'] or 0 for r in rooms if re.search(r'过道|走廊',r['name'])),2),
            'measuredTotal':round(sum(r['area'] or 0 for r in rooms),2),
            'attributes':data.get('attributes',{}),
            'note':'分间面积为户型图实测地板面积，不能当套内面积、得房率或房本面积；未知窗户不算无窗。'}


class HousingError(Exception):
    pass


def safe_url(value):
    value = str(value or '')
    p = urlparse(value)
    if p.scheme == 'https' and (p.hostname == 'ke.com' or (p.hostname or '').endswith('.ke.com')):
        return value
    return ''


def number(value):
    m = re.search(r'(\d+(?:\.\d+)?)', str(value or '').replace(',', ''))
    return float(m.group(1)) if m else None


def bd2gcj(lng, lat):
    x, y = lng - .0065, lat - .006
    z = math.hypot(x, y) - .00002 * math.sin(y * math.pi * 3000 / 180)
    t = math.atan2(y, x) - .000003 * math.cos(x * math.pi * 3000 / 180)
    return round(z * math.cos(t), 6), round(z * math.sin(t), 6)


def parse_card(card):
    text = str(card.get('communityDesc') or '')
    years = [int(v) for v in re.findall(r'(?<!\d)((?:19|20)\d{2})(?!\d)', text)]
    years = [v for v in years if 1800 <= v <= time.localtime().tm_year]
    out = {'description': text, 'year': min(years) if years else None,
           'yearRange': '–'.join(map(str, sorted(set(years)))),
           'buildingType': text.split('|')[0].strip() if text else '',
           'buildings': None, 'households': None, 'price': None,
           'url': safe_url(card.get('actionUrl'))}
    for key, pattern in [('buildings', r'(\d+)\s*栋'), ('households', r'(\d+)\s*户')]:
        m = re.search(pattern, text)
        if m:
            out[key] = int(m.group(1))
    price = number(card.get('avgUnitPrice'))
    if price and price > 0:
        out['price'] = price
    return out


def parse_listings(rows):
    out, seen = [], set()
    for row in rows:
        url = safe_url(row.get('actionUrl'))
        desc = str(row.get('desc') or '')
        title = str(row.get('title') or '')
        # 本工具是买房对比，租房/品牌公寓不能混进买房统计。
        if '/ershoufang/' not in url:
            continue
        if url in seen:
            continue
        seen.add(url)
        layout = re.search(r'(\d+)室\s*(\d+)厅(?:\s*(\d+)卫)?', desc)
        area = re.search(r'(\d+(?:\.\d+)?)\s*(?:㎡|m²|平方米)', desc)
        parts = desc.split('/')
        total = number(row.get('priceStr'))
        # priceStr 明确标万才按万元展示，不猜测币种/单位。
        total = total if '万' in str(row.get('priceStr')) else None
        unit = number(row.get('unitPriceStr'))
        out.append({'url': url, 'title': title, 'description': desc,
                    'layout': layout.group(0) if layout else '',
                    'rooms': int(layout.group(1)) if layout else None,
                    'area': float(area.group(1)) if area else None,
                    'orientation': parts[2].strip() if len(parts) > 2 else '',
                    'totalPrice': total, 'unitPrice': unit,
                    'tags': [str(t.get('desc') or '') for t in row.get('tags', []) if isinstance(t, dict)]})
    return out


def summarize(rows):
    layouts = collections.Counter(r['layout'] for r in rows if r['layout'])
    areas = [r['area'] for r in rows if r['area'] is not None]
    return {'sampleCount': len(rows), 'layoutSamples': sum(layouts.values()),
            'layouts': [{'name': k, 'count': v} for k, v in layouts.most_common()],
            'areaMin': min(areas) if areas else None, 'areaMax': max(areas) if areas else None,
            'southCount': sum('南' in r['orientation'] for r in rows),
            'northSouthCount': sum('南' in r['orientation'] and '北' in r['orientation'] for r in rows)}


class HousingBridge:
    def __init__(self):
        self.token = secrets.token_urlsafe(32)
        self.lock = threading.Lock()
        self.jobs = collections.deque()
        self.waiting = {}
        self.last_seen = 0
        self.city = ''
        self.profile_seen = 0
        self.profile_host = ''
        self.collect_lock = threading.Lock()

    def status(self):
        return {'supported': True, 'connected': time.time() - self.last_seen < 12,
                'profileConnected': time.time() - self.profile_seen < 12,
                'profileHost': self.profile_host, 'version': '1.0.0',
                'cityId': self.city, 'source': 'beike-authorized-page'}

    def poll(self, token, city, surface='map', host=''):
        self.authorize(token)
        if not re.fullmatch(r'\d{6}', str(city or '')):
            raise HousingError('贝壳页面城市无效')
        if surface not in ('map','profile'):
            raise HousingError('贝壳页面类型无效')
        if surface=='profile' and not re.fullmatch(r'[a-z]{2,12}\.ke\.com',str(host)):
            raise HousingError('贝壳详情域名无效')
        with self.lock:
            if surface=='map':self.last_seen, self.city = time.time(), str(city)
            else:self.profile_seen,self.profile_host=time.time(),host
            for job in list(self.jobs):
                if job['id'] not in self.waiting or job['expires'] <= time.time():
                    self.jobs.remove(job);continue
                wanted='profile' if job['action'] in ('profile','floorplan') else 'map'
                if wanted==surface and (wanted=='map' or urlparse(job['params']['url']).hostname==host):
                    self.jobs.remove(job);return {'job':job}
        return {'job': None}

    def authorize(self, token):
        if not isinstance(token, str) or not secrets.compare_digest(token, self.token):
            raise HousingError('连接已失效，请重新连接贝壳页面')

    def response(self, token, job_id, payload):
        self.authorize(token)
        with self.lock:
            item = self.waiting.get(str(job_id))
            if item:
                item['payload'] = payload
                item['event'].set()
        return True

    def request(self, action, params):
        profile = action in ('profile','floorplan')
        state=self.status()
        if profile:
            if not state['profileConnected'] or urlparse(params['url']).hostname!=self.profile_host:
                raise HousingError('未连接当前城市的小区详情页：请在该页点击连接书签后刷新资料')
        elif not state['connected']:
            raise HousingError('请先连接已登录的贝壳地图页面')
        if str(params.get('cityId')) != self.city:
            raise HousingError('贝壳页面城市与查询城市不一致，请切换贝壳地图城市后重新连接')
        event, job_id = threading.Event(), secrets.token_urlsafe(16)
        item = {'event': event, 'payload': None}
        with self.lock:
            if len(self.waiting) >= 4:
                raise HousingError('查询进行中，请稍后重试')
            self.waiting[job_id] = item
            self.jobs.append({'id': job_id, 'action': action, 'params': params, 'expires': time.time() + 23})
        try:
            if not event.wait(24):
                raise HousingError('贝壳页面查询超时，请保持贝壳标签页打开并重试')
            payload = item['payload'] or {}
            if payload.get('error'):
                raise HousingError(str(payload['error'])[:240])
            body = payload.get('body')
            if not isinstance(body, dict) or body.get('errno') != 0:
                raise HousingError('贝壳接口拒绝或格式变化：请在贝壳页面完成登录/验证后重试')
            data = body.get('data')
            if not isinstance(data, dict):
                raise HousingError('贝壳响应缺少数据，不作为空结果处理')
            if data.get('isShowLoginBtn'):
                raise HousingError('请在贝壳页面完成登录后重新连接')
            return data
        finally:
            with self.lock:
                self.waiting.pop(job_id, None)

    def search(self, body):
        name = str(body.get('name') or '').strip()
        city = str(body.get('cityId') or '')
        if not name or len(name) > 80 or not re.fullmatch(r'\d{6}', city):
            raise HousingError('请填写小区名称与有效城市代码')
        data = self.request('sug', {'cityId': city, 'dataSource': 'ESF', 'query': name,
                                    'pageSize': 30, 'hlsPreTag': '', 'hlsPostTag': ''})
        out = []
        for r in data.get('list', []):
            if r.get('itemType') != 'resblock' or str(r.get('cityId')) != city:
                continue
            lng, lat = r.get('pointLng'), r.get('pointLat')
            if not isinstance(lng, (int, float)) or not isinstance(lat, (int, float)):
                continue
            glng, glat = bd2gcj(lng, lat)
            out.append({'id': str(r.get('itemId')), 'name': str(r.get('itemName') or ''),
                        'alias': str(r.get('itemAlias') or ''), 'address': str(r.get('desc') or ''),
                        'cityId': city, 'lng': glng, 'lat': glat, 'bdLng': lng, 'bdLat': lat,
                        'count': number(r.get('count')), 'coordinateSource': '贝壳 BD-09 转 GCJ-02'})
        return {'items': out, 'source': 'https://map.ke.com/map/' + city + '/ESF/'}

    def detail(self, body):
        if not self.collect_lock.acquire(blocking=False):
            raise HousingError('已有小区资料采集中，请等待完成后再查询其他小区')
        try:
            return self._detail(body)
        finally:
            self.collect_lock.release()

    def _detail(self, body):
        cid, city = str(body.get('id') or ''), str(body.get('cityId') or '')
        if not re.fullmatch(r'\d{6,20}', cid) or not re.fullmatch(r'\d{6}', city):
            raise HousingError('小区 ID 或城市代码无效')
        try:
            lng, lat = float(body['bdLng']), float(body['bdLat'])
        except (KeyError, TypeError, ValueError):
            raise HousingError('缺少已确认的贝壳小区坐标')
        if not math.isfinite(lng) or not math.isfinite(lat) or not 70 <= lng <= 140 or not 0 <= lat <= 60:
            raise HousingError('小区坐标超出有效范围')
        rows, card, total, has_more = [], None, None, False
        pages = 0
        for page in range(1, 6):
            params = {'cityId': city, 'dataSource': 'ESF', 'curPage': page, 'condition': '',
                      'type': 'resblock', 'resblockId': cid, 'maxLatitude': lat + .015,
                      'minLatitude': lat - .015, 'maxLongitude': lng + .02, 'minLongitude': lng - .02}
            data = self.request('houselist', params)
            got_card = data.get('resblockCard')
            if not isinstance(got_card, dict) or str(got_card.get('communityId')) != cid:
                raise HousingError('贝壳返回的小区与选择项不一致，已停止补录')
            if card is None:
                card, total = got_card, number(data.get('total'))
            listings = data.get('list')
            if not isinstance(listings, list):
                raise HousingError('贝壳房源列表格式变化')
            rows.extend(listings)
            pages = page
            has_more = bool(data.get('hasMore'))
            if not has_more or not listings:
                break
        parsed = parse_listings(rows)
        result={'id':cid,'cityId':city,'name':str(card.get('communityName') or ''),
                'card':parse_card(card),'listings':parsed,'summary':summarize(parsed),
                'total':total,'pages':pages,'truncated':has_more,
                'fetchedAt':time.strftime('%Y-%m-%dT%H:%M:%S%z'),
                'source':safe_url(card.get('actionUrl')),'warnings':[]}
        result['summary']['groups']=layout_summary(parsed)
        result['profileStatus']='not_connected'
        details=[]
        if self.status()['profileConnected'] and urlparse(result['source']).hostname==self.profile_host:
            try:
                profile=self.request('profile',{'cityId':city,'url':result['source'],'id':cid})
                if str(profile.get('id'))!=cid:raise HousingError('基础资料小区ID不一致')
                result['card'].update(parse_profile_fields(profile.get('fields',{})))
                result['profileStatus']='ok'
            except HousingError as e:
                result['profileStatus']='failed';result['warnings'].append(str(e))
            attempted=0
            for row in representatives(parsed,12):
                attempted+=1
                try:
                    d=self.request('floorplan',{'cityId':city,'url':row['url'],'id':cid})
                    if str(d.get('id'))!=cid:raise HousingError('户型来源小区ID不一致')
                    details.append({'url':row['url'],'group':bucket(row),**analyze_floorplan(d)})
                except HousingError as e:
                    result['warnings'].append('代表户型采集停止：'+str(e));break
            result['detailAttempted']=attempted
        result['floorplanDetails']=details
        result['layoutAnalysis']=aggregate_layout(parsed,details)
        result['missing']=[k for k,label in [('far','容积率'),('green','绿化率'),('fee','物业费单值')] if result['card'].get(k) is None]
        result['policy']='最多5页挂牌、12套按房型×面积段选择的代表分间资料；仅小区专项和综合分，缺失不默认补值'
        return result

    def connect_page(self):
        # 只授权窄化的贝壳桥，不允许任意网站或任意 URL 代理。
        js = "javascript:(()=>{if(!/^(?:map|[a-z]{2,12})\\.ke\\.com$/.test(location.hostname)){alert('请在贝壳地图或小区详情页面使用');return}fetch('http://127.0.0.1:17532/api/housing/bridge.js').then(r=>r.text()).then(s=>(0,eval)(s)).catch(()=>alert('本地服务未连接，请先启动'))})()"
        return '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>连接贝壳 · 博客本地服务</title><style>body{font-family:Microsoft YaHei,sans-serif;max-width:780px;margin:48px auto;padding:0 24px;background:#eef2f5;color:#1c2b36;line-height:1.9}main{padding:28px;background:white;border-radius:12px}a{color:#14688f}button,.bookmark{padding:12px 18px;border:1px solid #14688f;border-radius:8px;background:#f6fafb}code{word-break:break-all}textarea{width:100%;height:100px;font-size:13px}#state{font-weight:bold}</style><main><h1>连接已登录的贝壳页面</h1><p>独立贝壳本地服务，与其他工具互不影响。登录凭据始终留在贝壳页面，本地服务仅接收本次小区和房源响应。</p><ol><li>打开 <a href="https://map.ke.com/map/310000/ESF/" target="_blank" rel="noopener">贝壳地图找房</a>，选择查询城市，完成登录。</li><li>在电脑浏览器中将「连接博客工具」拖到书签栏；安卓推荐在贝壳服务 App 内打开地图和小区详情，自动连接，无需书签。</li><li>在贝壳地图与该城市任一小区详情页分别点击此书签；地图提供挂牌，详情提供容积率、绿化率及代表户型。两个标签保持打开。服务重启后再次连接。</li></ol><p><a class="bookmark" href="''' + html.escape(js, quote=True) + '''">连接博客工具</a></p><details><summary>无法拖放时查看书签地址</summary><textarea readonly>''' + html.escape(js) + '''</textarea></details><p id="state">正在检测…</p><p>保持贝壳标签页打开。遇登录、验证码或访问拒绝，请在贝壳自行完成，不会绕过验证。每次最多读取5页二手房，不采集联系人资料。</p><a href="https://godweiyang.com/amenity/" target="_blank" rel="noopener">返回小区对比</a></main><script>setInterval(async()=>{try{const r=await fetch('/api/housing/status');const j=await r.json();document.getElementById('state').textContent=j.data.connected?'已连接贝壳（城市 '+j.data.cityId+'）':'等待在贝壳标签页点击连接书签'}catch(e){}},1500)</script></html>'''

    def bridge_script(self):
        import sys
        root = Path(getattr(sys, '_MEIPASS', Path(__file__).resolve().parent))
        import os
        path=os.environ.get('AMENITY_BRIDGE_PATH')
        script=Path(path).read_text(encoding='utf-8') if path else (root / 'beike_bridge.js').read_text(encoding='utf-8')
        return script.replace('__PAIR_TOKEN__',self.token).replace('__CITY_ID__',self.city)


HOUSING = HousingBridge()
