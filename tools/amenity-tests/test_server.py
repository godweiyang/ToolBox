import unittest
from amenity import server
class ServerTests(unittest.TestCase):
 def test_origins(self):
  for o in ['https://godweiyang.com','http://127.0.0.1:4000','https://tools.godweiyang.com','https://appassets.androidplatform.net']:
   self.assertTrue(server.allowed_origin(o))
  for o in ['https://evilgodweiyang.com','https://godweiyang.com.evil.test','null','file://','https://sh.ke.com','https://map.ke.com']:
   self.assertFalse(server.allowed_origin(o))
 def test_beike_only_bridge(self):
  self.assertTrue(server.allowed_origin('https://sh.ke.com',True))
  self.assertTrue(server.allowed_origin('https://map.ke.com',True))
  self.assertFalse(server.allowed_origin('https://sh.ke.com.evil.test',True))
 def test_independent(self):
  self.assertEqual(server.PORT,17532)
  self.assertEqual(server.SERVICE,'amenity-local')
  self.assertEqual(server.SCHEME,'amenity')
if __name__=='__main__':unittest.main(verbosity=2)
