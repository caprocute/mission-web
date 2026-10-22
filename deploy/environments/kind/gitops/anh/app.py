"""Ứng dụng thử cho kiểm thử GitOps và canary trên kind (INF-04).

Trả 500 với xác suất TI_LE_LOI (nướng vào ảnh lúc dựng), xuất metric cùng tên với Spring Boot
(http_server_requests_seconds_count/_bucket/_sum, nhãn method, status, uri) để AnalysisTemplate slo-api dùng nguyên truy vấn.
"""
import http.server, os, random, threading, time

TI_LE_LOI = float(os.environ.get("TI_LE_LOI", "0"))
PHIEN_BAN = os.environ.get("PHIEN_BAN", "?")
BUCKET = (0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0)
khoa = threading.Lock()
dem, tong, gio = {}, {}, {}


class XuLy(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def tra(self, ma, than, loai="text/plain"):
        b = than.encode()
        self.send_response(ma)
        self.send_header("Content-Type", loai)
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if self.path == "/healthz":
            return self.tra(200, "ok")
        if self.path == "/metrics":
            dong = []
            with khoa:
                for (m, s), n in sorted(dem.items()):
                    nhan = f'method="{m}",status="{s}",uri="/"'
                    dong.append(f"http_server_requests_seconds_count{{{nhan}}} {n}")
                    dong.append(f"http_server_requests_seconds_sum{{{nhan}}} {tong[(m, s)]}")
                    for le, c in zip(BUCKET + ("+Inf",), gio[(m, s)]):
                        dong.append(f'http_server_requests_seconds_bucket{{{nhan},le="{le}"}} {c}')
            return self.tra(200, "\n".join(dong) + "\n", "text/plain; version=0.0.4")
        bd = time.monotonic()
        ma = 500 if random.random() < TI_LE_LOI else 200
        self.tra(ma, f"phien-ban {PHIEN_BAN}\n")
        tg = time.monotonic() - bd
        with khoa:
            k = (self.command, str(ma))
            dem[k] = dem.get(k, 0) + 1
            tong[k] = tong.get(k, 0.0) + tg
            g = gio.setdefault(k, [0] * (len(BUCKET) + 1))
            for i, le in enumerate(BUCKET):
                if tg <= le:
                    g[i] += 1
            g[-1] += 1

    do_POST = do_GET


http.server.ThreadingHTTPServer(("", 8080), XuLy).serve_forever()
