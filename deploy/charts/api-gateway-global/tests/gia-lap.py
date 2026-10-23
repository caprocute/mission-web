"""Registry SVC-03 và cell giả lập cho kiểm thử DV-01-04. VAI=registry hoặc VAI=cell (TEN=tên cell)."""
import json, os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

VAI, TEN = os.environ.get("VAI", "cell"), os.environ.get("TEN", "")
TENANTS = {"kpi.a.danhgia.vn": ("a", "11111111-1111-4111-8111-111111111111", "CELL-A", "http://cell-a.test:8080"),
           "kpi.b.danhgia.vn": ("b", "22222222-2222-4222-8222-222222222222", "CELL-B", "http://cell-b.test:8080")}
state = {"mode": "normal", "calls": 0, "moved": set()}

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def send(self, code, body=None, headers=None, ct="application/json"):
        b = json.dumps(body).encode() if body is not None else b""
        self.send_response(code)
        for k, v in (headers or {}).items(): self.send_header(k, v)
        if body is not None: self.send_header("Content-Type", ct)
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_POST(self):
        if self.path == "/token":
            return self.send(200, {"access_token": "svc-tok", "expires_in": 300, "token_type": "Bearer"})
        self.do_GET()
    def do_GET(self):
        u = urlparse(self.path); q = {k: v[0] for k, v in parse_qs(u.query).items()}
        if VAI == "cell":
            if u.path.startswith("/api/v1/move421"):
                return self.send(421, {"type": "about:blank", "status": 421, "title": "moved"},
                                 {"Location": "http://cell-b.test:8080"}, "application/problem+json")
            return self.send(200, {"cell": TEN, "path": self.path, "headers": {k.lower(): v for k, v in self.headers.items()}})
        if u.path == "/_ctl":
            if "mode" in q: state["mode"] = q["mode"]
            if "move" in q: state["moved"].add(q["move"])
            if "unmove" in q: state["moved"].discard(q["unmove"])
            return self.send(200, {"calls": state["calls"], "mode": state["mode"]})
        if u.path == "/api/v1/registry/resolve":
            state["calls"] += 1
            if self.headers.get("Authorization") != "Bearer svc-tok":
                return self.send(401, {"status": 401}, ct="application/problem+json")
            if state["mode"] == "down":
                return self.send(503, {"status": 503}, {"Retry-After": "30"}, "application/problem+json")
            hit = TENANTS.get(q.get("host", "")) or next((t for t in TENANTS.values() if t[0] == q.get("tenant")), None)
            if not hit:
                return self.send(404, {"status": 404}, ct="application/problem+json")
            code, tid, cell, url = hit
            if code in state["moved"]:
                return self.send(421, {"type": "about:blank", "status": 421, "code": "REGISTRY-421-TENANT-MOVED"},
                                 {"Location": "http://cell-b.test:8080"}, "application/problem+json")
            return self.send(200, {"tenantId": tid, "tenantCode": code, "status": "DANG_VAN_HANH", "cacheTtlSeconds": 300,
                                   "cell": {"id": tid, "code": cell, "apiBaseUrl": url}})
        if u.path.startswith("/api/v1/registry/") or u.path.startswith("/api/v1/jobs/"):
            return self.send(200, {"registry": True, "path": u.path})
        self.send(404, {"status": 404})

ThreadingHTTPServer(("0.0.0.0", 8080), H).serve_forever()
