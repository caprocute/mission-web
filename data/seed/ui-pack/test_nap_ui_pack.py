"""Kiểm thử bộ nạp UI Pack qua API trên máy chủ giả (DL-06). Chạy:
python3 -I -m unittest discover -s data/seed/ui-pack -p 'test_*.py'
"""
import io
import json
import os
import sys
import threading
import unittest
from contextlib import redirect_stderr, redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import nap_ui_pack  # noqa: E402


class NapUiPackTest(unittest.TestCase):
    def setUp(self):
        self.goi = []
        test = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _tra(self, st, body):
                raw = json.dumps(body).encode()
                self.send_response(st)
                self.send_header("ETag", f'"{len(test.goi)}"')
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def _ghi(self, method):
                n = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(n)) if n else None
                test.goi.append((method, self.path, dict(self.headers), body))

            def do_GET(self):
                self._ghi("GET")
                if self.path.startswith("/api/v1/ui/packs?"):
                    self._tra(200, {"items": [{"id": "p1", "code": "default/ui", "name": "Mặc định"}], "nextCursor": None})
                else:
                    self._tra(200, {})

            def do_POST(self):
                self._ghi("POST")
                self._tra(201 if self.path.endswith("/versions") else 202, {})

            def do_PUT(self):
                self._ghi("PUT")
                self._tra(200, {})

        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.api = f"http://127.0.0.1:{self.srv.server_port}/api/v1"

    def tearDown(self):
        self.srv.shutdown()

    def chay(self, env):
        old = dict(os.environ)
        os.environ.update(env)
        try:
            with redirect_stdout(io.StringIO()) as out, redirect_stderr(io.StringIO()):
                rc = nap_ui_pack.main([])
        finally:
            os.environ.clear()
            os.environ.update(old)
        return rc, out.getvalue()

    def test_tao_ghi_gui_duyet_kem_if_match_khong_phat_hanh(self):
        rc, out = self.chay({"MISSION_API": self.api, "MISSION_TOKEN": "a"})
        self.assertEqual(rc, 0, out)
        buoc = [(g[0], g[1]) for g in self.goi]
        self.assertEqual(
            buoc,
            [
                ("GET", "/api/v1/ui/packs?limit=1"),
                ("POST", "/api/v1/ui/packs/p1/versions"),
                ("PUT", "/api/v1/ui/packs/p1/versions/1.0.0"),
                ("POST", "/api/v1/ui/packs/p1/versions/1.0.0/submit"),
            ],
        )
        self.assertEqual(self.goi[1][3], {"version": "1.0.0", "cloneFromVersion": None})
        self.assertIn("If-Match", self.goi[2][2])
        self.assertNotIn("version", self.goi[2][3])
        self.assertEqual(self.goi[2][3]["layouts"]["home"]["component"], "vt.layout.grid")
        self.assertIn("Chưa phát hành", out)

    def test_phat_hanh_bang_tai_khoan_thu_hai(self):
        rc, _ = self.chay({"MISSION_API": self.api, "MISSION_TOKEN": "a", "MISSION_TOKEN_DUYET": "b"})
        self.assertEqual(rc, 0)
        cuoi = self.goi[-1]
        self.assertEqual(cuoi[1], "/api/v1/ui/packs/p1/versions/1.0.0/publish")
        self.assertEqual(cuoi[2]["Authorization"], "Bearer b")
        self.assertIn("If-Match", cuoi[2])


if __name__ == "__main__":
    unittest.main()
