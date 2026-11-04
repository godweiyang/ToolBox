"""可复现的小区户型聚合；规则权重是工具启发式，不是行业评级。"""
import collections
import math
import re
import statistics


def finite(value):
    return isinstance(value, (int, float)) and math.isfinite(value)


def bucket(row):
    area = row.get('area')
    band = '未知面积' if not finite(area) else ('<70㎡' if area < 70 else '70–90㎡' if area < 90 else '90–120㎡' if area < 120 else '120–160㎡' if area < 160 else '160–200㎡' if area < 200 else '≥200㎡')
    return str(row.get('rooms') or '未知') + '室 / ' + band


def summary(rows):
    groups = collections.defaultdict(list)
    for row in rows:
        groups[bucket(row)].append(row)
    result = []
    for key, items in sorted(groups.items(), key=lambda x: (-len(x[1]), x[0])):
        def med(k):
            vals = [x[k] for x in items if finite(x.get(k)) and x[k] > 0]
            return round(statistics.median(vals), 2) if vals else None
        result.append({'name': key, 'count': len(items), 'share': round(len(items) / len(rows), 4),
                       'areaMedian': med('area'), 'totalMedian': med('totalPrice'),
                       'unitMedian': med('unitPrice'),
                       'northSouthCount': sum('南' in x.get('orientation','') and '北' in x.get('orientation','') for x in items)})
    return result


def representatives(rows, limit=12):
    groups = collections.defaultdict(list)
    for row in rows:
        groups[bucket(row)].append(row)
    chosen = []
    # 按挂牌构成排序，但每组轮流取代表，避免大量同房型挤占请求预算。
    ordered = sorted(groups.values(), key=lambda g: (-len(g), bucket(g[0])))
    for groupset in ordered:
        groupset.sort(key=lambda x: (x.get('area') or 0, x.get('url','')))
    for turn in range(2):
        for group in ordered:
            if len(chosen) >= limit:
                return chosen
            row = group[len(group)//2] if turn == 0 else group[0]
            if row not in chosen:
                chosen.append(row)
    return chosen


def aggregate_layout(rows, details):
    """按房型×面积段挂牌权重聚合；仅一个小区户型专项分，不输出每套评分。"""
    by_url = {d.get('url'): d for d in details if not d.get('error')}
    grouped = collections.defaultdict(list)
    for row in rows:
        grouped[bucket(row)].append(row)
    weights = {'daylight': .35, 'bedroomSouth': .2, 'wetWindows': .2, 'circulation': .15, 'bathroom': .1}
    components, contributing = {}, set()
    def metric(d, key):
        rr = d.get('rooms') or []
        living = [r for r in rr if re.search('客厅|卧室|书房', r.get('name',''))]
        bedrooms = [r for r in rr if '卧室' in r.get('name','')]
        if key == 'daylight':
            # 未知窗口不能当无窗，也不能假定有窗；分母不完整则不计该套指标。
            if not living or any(not r.get('window') or '未知' in r['window'] for r in living): return None
            return sum(r['window'] != '无窗' for r in living) / len(living)
        if key == 'bedroomSouth':
            if not bedrooms or any(r.get('orientation') in (None, '', '无', '未知') for r in bedrooms):return None
            return sum('南' in r['orientation'] for r in bedrooms) / len(bedrooms)
        if key == 'wetWindows':
            wet = [r for r in rr if re.search('厨房|卫生间', r.get('name',''))]
            if not wet or any(not r.get('window') or '未知' in r['window'] for r in wet):return None
            return sum(r['window'] != '无窗' for r in wet) / len(wet)
        if key == 'circulation':
            if not rr or any(not finite(r.get('area')) or r['area'] <= 0 for r in rr):return None
            total = sum(r['area'] for r in rr)
            corridor = sum(r['area'] for r in rr if re.search('过道|走廊',r.get('name','')))
            # 可核对的实测空间占比，不声称得房率或全动线优劣。
            return max(0., min(1., (0.20 - corridor/total) / .15))
        if key == 'bathroom':
            layout = d.get('attributes',{}).get('房屋户型','')
            m = re.search(r'(\d+)室\s*\d+厅\s*(\d+)卫', layout)
            if not m:return None
            beds,baths=map(int,m.groups())
            if beds<=0 or baths<=0:return None
            return min(1., baths / max(1., math.ceil(beds/2)))
    for key,w in weights.items():
        earned,represented,count = 0.,0.,0
        for group in grouped.values():
            found=[]
            for row in group:
                d=by_url.get(row.get('url'))
                if not d:continue
                value=metric(d,key)
                if value is not None:
                    found.append(value);contributing.add(row['url']);count+=1
            if found:
                group_weight=len(group)/len(rows)
                represented+=group_weight;earned+=statistics.mean(found)*group_weight
        if represented:
            components[key]={'factor':round(earned/represented,4),'coverage':round(represented,4),
                             'samples':count,'weight':w}
    coverage=sum(x['coverage']*x['weight'] for x in components.values())
    denom=sum(x['coverage']*x['weight'] for x in components.values())
    factor=sum(x['factor']*x['coverage']*x['weight'] for x in components.values())/denom if denom else None
    eligible=len(contributing)>=3 and coverage>=.60
    return {'factor':round(factor,4) if factor is not None and eligible else None,
            'components':components,'coverage':round(coverage,4),'detailCount':len(by_url),
            'scoredSamples':len(contributing),'eligible':eligible,
            'reason':'' if eligible else '至少3套有效分间样本、加权覆盖率≥60%才自动计入户型专项分',
            'policy':'按房型×面积段挂牌构成加权，不等于全小区户型占比；代表样本不是全量实勘。'}
