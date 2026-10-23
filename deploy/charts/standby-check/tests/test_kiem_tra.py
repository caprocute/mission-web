"""Kiểm thử kiem_tra.py với Prometheus giả (http.server): xanh khi mọi điều kiện đạt; đỏ khi độ trễ WAL vượt ngưỡng,
khi MirrorMaker 2 không có thông điệp mới (đường nhân bản bị ngắt), khi thiếu số liệu và khi Prometheus không trả lời."""
import contextlib
import io
import json
import os
import sys
import threading
import unittest
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "files"))
import kiem_tra  # noqa: E402

GIA_TRI = {}


class Prom(BaseHTTPRequestHandler):
    def do_GET(self):
        q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)["query"][0]
        if q.startswith("loi("):
            body = {"status": "error", "error": "parse error"}
        else:
            body = {"status": "success", "data": {"resultType": "vector",
                    "result": [{"metric": {}, "value": [0, str(v)]} for v in GIA_TRI.get(q, [])]}}
        b = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b)

    def log_message(self, *a):
        pass


DS = [
    {"ten": "pg-core nhận WAL", "promql": "rec", "so": "==", "nguong": 1},
    {"ten": "pg-core trễ WAL", "promql": "lag", "so": "<", "nguong": 60},
    {"ten": "mm2 thông điệp mới", "promql": "mm2", "so": ">", "nguong": 0},
]


class KiemTra(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), Prom)
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        cls.url = f"http://127.0.0.1:{cls.srv.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()

    def setUp(self):
        GIA_TRI.clear()
        GIA_TRI.update({"rec": [1, 1, 1], "lag": [0, 4.5], "mm2": [1200]})

    def dat(self, ds=DS):
        return {d["ten"]: d["dat"] for d in kiem_tra.kiem(self.url, ds)}

    def test_xanh_khi_moi_dieu_kien_dat(self):
        self.assertTrue(all(self.dat().values()))

    def test_do_khi_tre_wal_vuot_nguong(self):
        GIA_TRI["lag"] = [0, 75]
        self.assertFalse(self.dat()["pg-core trễ WAL"])

    def test_do_khi_ngat_nhan_ban(self):
        GIA_TRI["mm2"] = [0]
        GIA_TRI["rec"] = [1, 0, 1]   # một instance đã thôi phục hồi (bị promote) cũng là đỏ
        d = self.dat()
        self.assertFalse(d["mm2 thông điệp mới"])
        self.assertFalse(d["pg-core nhận WAL"])

    def test_thieu_so_lieu_la_do(self):
        del GIA_TRI["lag"]
        kq = kiem_tra.kiem(self.url, DS)
        self.assertEqual([d["lyDo"] for d in kq if not d["dat"]], ["không có số liệu"])

    def test_prometheus_loi_hoac_khong_tra_loi_la_do(self):
        self.assertFalse(self.dat([{"ten": "x", "promql": "loi(", "so": "<", "nguong": 1}])["x"])
        kq = kiem_tra.kiem("http://127.0.0.1:9", DS[:1])
        self.assertFalse(kq[0]["dat"])

    def test_main_tra_ma_thoat_va_dong_tong(self):
        import tempfile
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            json.dump(DS, f)
        os.environ.update(PROMETHEUS_URL=self.url, KIEM_TRA_FILE=f.name, CELL="g4")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(kiem_tra.main(), 0)
        self.assertTrue(json.loads(out.getvalue().splitlines()[-1])["sanSang"])
        GIA_TRI["mm2"] = []
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(kiem_tra.main(), 1)
        os.unlink(f.name)


if __name__ == "__main__":
    unittest.main()
