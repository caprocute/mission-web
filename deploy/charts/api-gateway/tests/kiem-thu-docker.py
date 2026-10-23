#!/usr/bin/env python3
"""Các ca của tests/kiem-thu-docker.sh (DV-01-01): gọi APISIX standalone đã khởi động, in [ĐẠT]/[HỎNG], thoát 1 khi hỏng."""
import json
import sys
import time
import urllib.error
import urllib.request

BASE, TOKS = sys.argv[1], json.load(open(sys.argv[2]))
HOST = "hn.danhgia.vn"
dat = hong = 0


def goi(method, path, token=None, headers=None):
    h = {"Host": HOST, **(headers or {})}
    if token:
        h["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, method=method, headers=h, data=b"{}" if method != "GET" else None)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def ca(mota, dieu_kien, chi_tiet=""):
    global dat, hong
    if dieu_kien:
        dat += 1
        print(f"  [ĐẠT] {mota}")
    else:
        hong += 1
        print(f"  [HỎNG] {mota} {chi_tiet}")


def problem(status, headers, body, want):
    try:
        b = json.loads(body)
    except ValueError:
        b = {}
    ct = {k.lower(): v for k, v in headers.items()}.get("content-type", "")
    return status == want and ct.startswith("application/problem+json") and b.get("status") == want


# Chờ cổng nạp JWKS lần đầu: token đúng phải tới được upstream.
for _ in range(30):
    s, h, b = goi("GET", "/api/v1/tasks", TOKS["dung"])
    if s == 200:
        break
    time.sleep(1)
ca("token ES256 đúng cell tới upstream mission-core", s == 200, f"(mã {s}: {b[:200]!r})")

s, h, b = goi("GET", "/api/v1/tasks")
ca("không có token: 401 Problem Details", problem(s, h, b, 401), f"(mã {s}, {h.get('Content-Type')}, {b[:200]!r})")

s, h, b = goi("GET", "/api/v1/tasks", TOKS["sai_chu_ky"])
ca("chữ ký sai: 401 Problem Details", problem(s, h, b, 401), f"(mã {s}, {b[:200]!r})")

s, h, b = goi("GET", "/api/v1/tasks", TOKS["rs256"])
ca("token ký RS256 (khác thuật toán của realm): 401", problem(s, h, b, 401), f"(mã {s}, {b[:200]!r})")

for ten, mota in (("het_han", "token hết hạn"), ("sai_iss", "token của issuer khác"), ("none", "token alg none")):
    s, h, b = goi("GET", "/api/v1/tasks", TOKS[ten])
    ca(f"{mota}: 401 Problem Details", problem(s, h, b, 401), f"(mã {s}, {b[:200]!r})")

s, h, b = goi("GET", "/api/v1/tasks", TOKS["sai_cell"])
ca("token của cell khác: 403 Problem Details", problem(s, h, b, 403), f"(mã {s}, {b[:200]!r})")

s, h, b = goi("GET", "/api/v1/tasks", TOKS["dung"], {"X-Tenant-Id": "tenant-gia-mao"})
seen = json.loads(b)["headers"].get("x-tenant-id") if s == 200 else None
ca("X-Tenant-Id client gửi bị ghi đè bằng claim tenant_id", seen == "t-thu", f"(upstream thấy {seen!r})")
ca("phản hồi mang X-Request-Id", any(k.lower() == "x-request-id" for k in h))

s, h, b = goi("POST", "/api/v1/esign/seals", TOKS["dung"])
ca("thao tác chỉ cho client nội bộ: 404 Problem Details", problem(s, h, b, 404), f"(mã {s}, {b[:200]!r})")

s, h, b = goi("GET", "/api/v1/appraisals/profiles/p1/score-trace", TOKS["dung"])
up = json.loads(b)["service"] if s == 200 else None
ca("tuyến hẹp score-trace tới scoring-service, thắng tuyến tiền tố appraisals", up == "scoring", f"(mã {s}, {up!r})")
s, h, b = goi("GET", "/api/v1/appraisals/profiles/p1", TOKS["dung"])
up = json.loads(b)["service"] if s == 200 else None
ca("hồ sơ đánh giá còn lại vẫn tới mission-core", up == "chung", f"(mã {s}, {up!r})")

s, h, b = goi("GET", "/.well-known/jwks.json")
ca("tuyến công khai /.well-known/jwks.json không cần token", s == 200, f"(mã {s})")
s, h, b = goi("POST", "/.well-known/jwks.json")
ca("tuyến công khai chỉ nhận đúng phương thức GET", s == 404, f"(mã {s})")
s, h, b = goi("GET", "/.well-known/openid-configuration")
ca("đường /.well-known khác không công khai", s == 404, f"(mã {s})")

print(f"Tổng: {dat} đạt, {hong} hỏng")
sys.exit(1 if hong else 0)
