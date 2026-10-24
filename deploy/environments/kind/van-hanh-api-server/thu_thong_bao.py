"""Kênh trực giả: ghi mỗi thông báo POST nhận được (thân JSON) thành một dòng của tệp đích. Chạy: <cổng> <tệp>."""
import json, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DICH = sys.argv[2]


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_POST(self):
        b = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        with open(DICH, "a", encoding="utf-8") as f:
            f.write(json.dumps(json.loads(b or b"{}"), ensure_ascii=False) + "\n")
        self.send_response(204)
        self.end_headers()


ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
