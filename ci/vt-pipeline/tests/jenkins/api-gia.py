#!/usr/bin/env python3
"""API Mission giả cho kiểm thử pipeline Policy Pack, UI Pack (INF-06). Chỉ phục vụ đúng các thao tác pipeline gọi
và kiểm quy ước baseline mục 13 như máy chủ thật: thiếu Authorization → 401, lệnh ghi thiếu Idempotency-Key → 400,
cập nhật thiếu If-Match → 428, If-Match sai → 409; tác vụ dài trả 202 + Location, job hoàn tất ở lần hỏi thứ hai.
Mọi yêu cầu ghi vào tệp nhật ký JSON Lines để kịch bản kiểm thử đối chiếu.
    api-gia.py <cổng> <nhật ký.jsonl>"""
import json, re, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CONG, NHAT_KY = int(sys.argv[1]), sys.argv[2]
ETAG = '"v1"'
JOB = {}
ROUTES = [  # (method, regex, thao tác, cần If-Match, kiểu trả)
    ("GET", r"/policies/packs/[^/]+/versions/[^/]+", "getPolicyPackVersion", False, "etag"),
    ("POST", r"/policies/packs/[^/]+/versions/[^/]+/test-runs", "runPolicyTests", False, "job"),
    ("POST", r"/policies/packs/[^/]+/versions/[^/]+/submit", "submitPolicyPackVersion", True, "ok"),
    ("POST", r"/ui/packs/[^/]+/versions", "createUiPackVersion", False, "created"),
    ("GET", r"/ui/packs/[^/]+/versions/[^/]+", "getUiPackVersion", False, "etag"),
    ("PUT", r"/ui/packs/[^/]+/versions/[^/]+", "updateUiPackVersion", True, "etag"),
    ("POST", r"/ui/packs/[^/]+/versions/[^/]+/submit", "submitUiPackVersion", True, "job"),
    ("POST", r"/ui/packs/[^/]+/versions/[^/]+/publish", "publishUiPackVersion", True, "job"),
]


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def tra(self, ma, than=None, them=None, kieu="application/json"):
        b = json.dumps(than or {}, ensure_ascii=False).encode()
        self.send_response(ma)
        for k, v in (them or {}).items():
            self.send_header(k, v)
        self.send_header("Content-Type", kieu); self.send_header("Content-Length", str(len(b))); self.end_headers()
        self.wfile.write(b)

    def loi(self, ma, tieu_de):
        self.tra(ma, {"type": "about:blank", "title": tieu_de, "status": ma}, kieu="application/problem+json")

    def xu_ly(self):
        duong = self.path.split("?")[0]
        n = int(self.headers.get("Content-Length") or 0)
        than = json.loads(self.rfile.read(n) or b"{}") if n else None
        ghi = {"method": self.command, "path": duong, "idempotencyKey": bool(self.headers.get("Idempotency-Key")),
               "ifMatch": self.headers.get("If-Match"), "auth": (self.headers.get("Authorization") or "").startswith("Bearer "),
               "body": than}
        m = re.match(r"^/api/v1/jobs/(\w+)$", duong)
        if not ghi["auth"]:
            ghi["ket_qua"] = 401; self.ghi_nhat_ky(ghi); return self.loi(401, "Thiếu token")
        if m and self.command == "GET":
            JOB[m.group(1)] = JOB.get(m.group(1), 0) + 1
            tt = "HOAN_TAT" if JOB[m.group(1)] >= 2 else "DANG_CHAY"
            ghi["op"] = "getJob"; ghi["ket_qua"] = 200; self.ghi_nhat_ky(ghi)
            return self.tra(200, {"id": m.group(1), "type": "KIEM_TRA", "status": tt,
                                  "result": {"chenhLechTheoTenant": [{"tenant": "t-thu", "soCanBoDoiXepLoai": 0}]}})
        for meth, mau, op, can_if, kieu in ROUTES:
            if self.command == meth and re.fullmatch("/api/v1" + mau, duong):
                ghi["op"] = op
                if meth != "GET" and not ghi["idempotencyKey"]:
                    ghi["ket_qua"] = 400; self.ghi_nhat_ky(ghi); return self.loi(400, "Thiếu Idempotency-Key")
                if can_if and not ghi["ifMatch"]:
                    ghi["ket_qua"] = 428; self.ghi_nhat_ky(ghi); return self.loi(428, "Thiếu If-Match")
                if can_if and ghi["ifMatch"] != ETAG:
                    ghi["ket_qua"] = 409; self.ghi_nhat_ky(ghi); return self.loi(409, "Phiên bản đã đổi")
                if kieu == "job":
                    jid = f"j{len(JOB) + 1}"; JOB[jid] = 0
                    ghi["ket_qua"] = 202; self.ghi_nhat_ky(ghi)
                    return self.tra(202, {"id": jid, "status": "CHO"}, {"Location": f"http://127.0.0.1:{CONG}/api/v1/jobs/{jid}"})
                ma = 201 if kieu == "created" else 200
                ghi["ket_qua"] = ma; self.ghi_nhat_ky(ghi)
                return self.tra(ma, {"ok": True}, {"ETag": ETAG})
        ghi["ket_qua"] = 404; self.ghi_nhat_ky(ghi); self.loi(404, "Không có thao tác")

    do_GET = do_POST = do_PUT = xu_ly

    def ghi_nhat_ky(self, d):
        with open(NHAT_KY, "a", encoding="utf-8") as f:
            f.write(json.dumps({k: v for k, v in d.items() if k != "body"} | {"coThan": d.get("body") is not None},
                               ensure_ascii=False) + "\n")


ThreadingHTTPServer(("127.0.0.1", CONG), H).serve_forever()
