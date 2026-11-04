import json
import socket
import unittest
import urllib.request
import urllib.error
from amenity import server

class EmbeddedLifecycleTests(unittest.TestCase):
    def tearDown(self):
        server.stop_embedded()
    def test_bind_stop_reopen_and_no_other_imports(self):
        import sys
        port=server.start_embedded(0)
        self.assertEqual(port,server.start_embedded(0))
        def get(path,origin):
            return urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:'+str(port)+path,
                headers={'Host':'127.0.0.1:17532','Origin':origin}),timeout=3)
        data=json.load(get('/api/health','https://appassets.androidplatform.net'))['data']
        self.assertEqual(data['service'],'amenity-local')
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            get('/api/health','https://sh.ke.com')
        self.assertEqual(ctx.exception.code,403)
        self.assertEqual(get('/api/housing/bridge.js','https://sh.ke.com').status,200)
        old_token=server.HOUSING.token
        server.stop_embedded()
        with socket.socket() as sock:self.assertNotEqual(sock.connect_ex(('127.0.0.1',port)),0)
        port=server.start_embedded(port)
        self.assertNotEqual(old_token,server.HOUSING.token)
        self.assertEqual(json.load(get('/api/health','https://appassets.androidplatform.net'))['ok'],True)
        self.assertNotIn('engine',sys.modules)
        self.assertNotIn('lolzjcx',sys.modules)
    def test_similar_asset_origin_rejected(self):
        for origin in ['https://appassets.androidplatform.net.evil.test','https://evil@appassets.androidplatform.net','https://appassets.androidplatform.net:444']:
            self.assertFalse(server.allowed_origin(origin))
