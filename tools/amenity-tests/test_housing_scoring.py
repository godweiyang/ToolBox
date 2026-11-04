import unittest
from amenity.housing import parse_profile_fields,analyze_floorplan
from amenity.housing_scoring import aggregate_layout,representatives,summary

class ScoringTests(unittest.TestCase):
    def test_profile_numbers_and_range(self):
        x=parse_profile_fields({'容积率':'1.6','绿化率':'45%','物业费':'3至5元/平米/月，实际以物业公示为准'})
        self.assertEqual((x['far'],x['green'],x['feeMin'],x['feeMax']),(1.6,45,3,5));self.assertIsNone(x['fee'])
    def test_invalid_profile_not_guessed(self):
        x=parse_profile_fields({'容积率':'1.2–2.5','绿化率':'未知','物业费':'暂无'})
        self.assertIsNone(x['far']);self.assertIsNone(x['green']);self.assertIsNone(x['fee'])
    def test_unknown_not_without_window(self):
        d=analyze_floorplan({'rooms':[{'name':'卧室A','area':'15平米','orientation':'无','window':'未知窗户类型'}]})
        self.assertEqual(d['livingWithoutWindow'],[]);self.assertEqual(d['unknownWindows'],['卧室A'])
    def data(self,count=3):
        rows=[{'url':str(i),'rooms':2,'area':80,'orientation':'南 北','totalPrice':400,'unitPrice':50000} for i in range(count)]
        details=[{'url':str(i),'attributes':{'房屋户型':'2室1厅1卫'},'rooms':[
          {'name':'卧室A','area':12,'orientation':'南','window':'普通窗'},
          {'name':'卧室B','area':10,'orientation':'北','window':'普通窗'},
          {'name':'客厅','area':25,'orientation':'南','window':'落地窗'},
          {'name':'厨房','area':6,'orientation':'北','window':'普通窗'},
          {'name':'卫生间','area':4,'orientation':'无','window':'无窗'},
          {'name':'过道','area':8,'orientation':'无','window':'无窗'}]} for i in range(count)]
        return rows,details
    def test_one_house_not_whole_community_score(self):
        rows,details=self.data(1);x=aggregate_layout(rows,details)
        self.assertFalse(x['eligible']);self.assertIsNone(x['factor'])
    def test_no_details_missing_score(self):
        rows,_=self.data();x=aggregate_layout(rows,[])
        self.assertIsNone(x['factor']);self.assertEqual(x['coverage'],0)
    def test_sufficient_samples_score_and_reproducibility(self):
        rows,details=self.data();a=aggregate_layout(rows,details);b=aggregate_layout(list(reversed(rows)),list(reversed(details)))
        self.assertTrue(a['eligible']);self.assertEqual(a['factor'],b['factor']);self.assertTrue(0<=a['factor']<=1)
        self.assertEqual(a['components']['wetWindows']['factor'],.5)
    def test_missing_window_not_penalty(self):
        rows,details=self.data()
        for d in details:d['rooms'][0]['window']='未知窗户类型'
        x=aggregate_layout(rows,details)
        self.assertNotIn('daylight',x['components']);self.assertTrue(x['eligible']);self.assertLess(x['coverage'],1)
    def test_weight_by_groups_not_sample_count(self):
        rows,details=self.data(3);rows.extend([{'url':'extra'+str(i),'rooms':4,'area':180} for i in range(10)])
        x=aggregate_layout(rows,details);self.assertFalse(x['eligible']);self.assertLess(x['coverage'],.6)
    def test_budget_and_representatives(self):
        rows,_=self.data(40);chosen=representatives(rows,12)
        self.assertLessEqual(len(chosen),12);self.assertEqual(len({r['url'] for r in chosen}),len(chosen))
    def test_stats_calculated(self):
        rows,_=self.data();x=summary(rows)
        self.assertEqual(x[0]['count'],3);self.assertEqual(x[0]['areaMedian'],80);self.assertEqual(x[0]['share'],1)

if __name__=='__main__':unittest.main(verbosity=2)
