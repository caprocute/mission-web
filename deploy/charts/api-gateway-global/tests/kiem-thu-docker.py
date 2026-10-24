#!/usr/bin/env python3
"""Các ca của tests/kiem-thu-docker.sh (DV-01-04). Đối số: URL cổng, URL điều khiển registry giả lập, tệp khoá cookie."""
import base64
import hashlib
import hmac
import json
import sys
import time
import urllib.error
import urllib.request

GW, REG, KEY = sys.argv[1], sys.argv[2], base64.b64decode(open(sys.argv[3]).read())
dat = hong = 0


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


OPENER = urllib.request.build_opener(NoRedirect)


def goi(path, host, headers=None, method="GET"):
    data = b"{}" if method in ("POST", "PUT", "PATCH") else None
    req = urllib.request.Request(GW + path, data=data, method=method, headers={"Host": host, **(headers or {})})
    t = time.perf_counter()
    try:
        with OPENER.open(req, timeout=10) as r:
            out = r.status, r.headers, r.read()
    except urllib.error.HTTPError as e:
        out = e.code, e.headers, e.read()
    return (*out, (time.perf_counter() - t) * 1000)


def ctl(**q):
    qs = "&".join(f"{k}={v}" for k, v in q.items())
    with urllib.request.urlopen(f"{REG}/_ctl?{qs}", timeout=5) as r:
        return json.loads(r.read())


def calls():
    return ctl()["calls"]


def ca(mota, ok, chi_tiet=""):
    global dat, hong
    if ok:
        dat += 1
        print(f"  [ĐẠT] {mota}")
    else:
        hong += 1
        print(f"  [HỎNG] {mota} {chi_tiet}")


def problem(s, h, b, want):
    try:
        j = json.loads(b)
    except ValueError:
        j = {}
    return s == want and (h.get("Content-Type") or "").startswith("application/problem+json") and j.get("status") == want


def cell(b):
    try:
        return json.loads(b).get("cell")
    except ValueError:
        return None


def b64u(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def verify_cookie(set_cookie):
    """Kiểm cookie theo đúng định dạng routecookie của SVC-03 (Go): v1.<payload>.<HMAC>."""
    v = (set_cookie or "").split(";")[0].split("=", 1)[-1]
    parts = v.split(".")
    if len(parts) != 3 or parts[0] != "v1":
        return None
    want = b64u(hmac.new(KEY, f"{parts[0]}.{parts[1]}".encode(), hashlib.sha256).digest())
    if not hmac.compare_digest(want, parts[2]):
        return None
    return json.loads(base64.urlsafe_b64decode(parts[1] + "=" * (-len(parts[1]) % 4)))


def make_cookie(t, c, exp):
    head = "v1." + b64u(json.dumps({"t": t, "c": c, "exp": exp}).encode())
    return head + "." + b64u(hmac.new(KEY, head.encode(), hashlib.sha256).digest())


A = "kpi.a.danhgia.vn"
for _ in range(30):
    try:
        ctl()
        break
    except OSError:
        time.sleep(0.5)

n0 = calls()
s, h, b, _ = goi("/api/v1/tasks", A)
c = verify_cookie(h.get("Set-Cookie"))
ca("lần đầu: tra registry rồi chuyển tiếp tới cell của tenant", s == 200 and cell(b) == "cell-a" and calls() == n0 + 1,
   f"(mã {s}, {b[:120]!r}, gọi {calls() - n0})")
ca("upstream nhận X-Forwarded-Host là tên miền tenant", s == 200 and json.loads(b)["headers"].get("x-forwarded-host") == A)
ca("đặt cookie định tuyến ký HMAC đúng định dạng SVC-03, hạn 5 phút",
   c is not None and c["c"] == "CELL-A" and 290 <= c["exp"] - time.time() <= 300 and "HttpOnly" in h.get("Set-Cookie", ""),
   f"({h.get('Set-Cookie')!r})")

n1 = calls()
lat = []
for _ in range(200):
    s, h, b, ms = goi("/api/v1/tasks", A)
    lat.append(ms)
ca("trúng đệm: 200 lần không gọi registry", calls() == n1 and h.get("X-Vt-Cell-Cache") == "hit", f"(gọi {calls() - n1})")
lat.sort()
p95 = lat[int(len(lat) * 0.95) - 1]
ca(f"p95 khi trúng đệm ≤ 50 ms (đo cả chặng tới cell giả lập: p50 {lat[len(lat) // 2]:.1f} ms, p95 {p95:.1f} ms)", p95 <= 50)

time.sleep(2.3)
n2 = calls()
s, h, b, _ = goi("/api/v1/tasks", A)
ca("hết hạn đệm: gọi lại registry", s == 200 and calls() == n2 + 1 and h.get("X-Vt-Cell-Cache") == "miss", f"(gọi {calls() - n2})")

ctl(mode="down")
time.sleep(2.3)
s, h, b, _ = goi("/api/v1/tasks", A)
ca("registry ngừng: tenant đã đệm vẫn được phục vụ (mục đệm cũ)", s == 200 and cell(b) == "cell-a" and h.get("X-Vt-Cell-Cache") == "stale",
   f"(mã {s}, {h.get('X-Vt-Cell-Cache')})")
s, h, b, _ = goi("/api/v1/tasks", "kpi.b.danhgia.vn")
ca("registry ngừng, tenant chưa đệm: 503 + Retry-After: 30", problem(s, h, b, 503) and h.get("Retry-After") == "30", f"(mã {s})")
ctl(mode="normal")

ctl(move="a")
time.sleep(2.3)
s, h, b, _ = goi("/api/v1/tasks", A)
ca("tenant đã chuyển cell: 421 kèm Location, mã REGISTRY-421-TENANT-MOVED",
   problem(s, h, b, 421) and h.get("Location") == "http://cell-b.test:8080" and json.loads(b).get("code") == "REGISTRY-421-TENANT-MOVED",
   f"(mã {s}, {h.get('Location')})")
ctl(unmove="a")
n3 = calls()
s, h, b, _ = goi("/api/v1/tasks", A)
ca("sau 421 mục đệm đã xoá: request kế tiếp tra lại registry", s == 200 and calls() == n3 + 1, f"(gọi {calls() - n3})")

s, h, b, _ = goi("/api/v1/tasks", A)
s, h, b, _ = goi("/api/v1/move421", A)
n4 = calls()
ca("cell trả 421: chuyển nguyên vẹn mã và Location, xoá cookie định tuyến",
   s == 421 and h.get("Location") == "http://cell-b.test:8080" and "Max-Age=0" in (h.get("Set-Cookie") or ""), f"(mã {s})")
s, h, b, _ = goi("/api/v1/tasks", A)
ca("sau 421 của cell mục đệm đã xoá", calls() == n4 + 1, f"(gọi {calls() - n4})")

good = make_cookie("11111111-1111-4111-8111-111111111111", "CELL-A", int(time.time()) + 300)
n5 = calls()
s, h, b, _ = goi("/api/v1/tasks", "kpi.moi.danhgia.vn", {"Cookie": f"mission_route={good}"})
ca("cookie hợp lệ, cell đã biết: đi thẳng tới cell, không tra registry", s == 200 and cell(b) == "cell-a" and calls() == n5,
   f"(mã {s}, gọi {calls() - n5})")
bad = good[:-2] + ("A" if good[-2] != "A" else "B") + good[-1]
s, h, b, _ = goi("/api/v1/tasks", "kpi.moi.danhgia.vn", {"Cookie": f"mission_route={bad}"})
ca("cookie sửa một byte bị bỏ qua: tra registry theo tên miền (404 vì tên miền lạ)", problem(s, h, b, 404) and calls() == n5 + 1,
   f"(mã {s})")

s, h, b, _ = goi("/dang-nhap?tenant=b&next=%2F", "app.danhgia.vn")
c = verify_cookie(h.get("Set-Cookie"))
ca("tên miền chung: khám phá bằng mã đơn vị, 307 tới URL của cell, bỏ tham số tenant",
   s == 307 and h.get("Location") == "http://cell-b.test:8080/dang-nhap?next=%2F" and c and c["c"] == "CELL-B",
   f"(mã {s}, {h.get('Location')})")
s, h, b, _ = goi("/", "app.danhgia.vn")
ca("tên miền chung không có mã đơn vị, không có cookie: 400 Problem Details", problem(s, h, b, 400), f"(mã {s})")
s, h, b, _ = goi("/api/v1/registry/cells", "app.danhgia.vn")
ca("đường quản trị /api/v1/registry* ở lớp toàn cục tới SVC-03", s == 200 and json.loads(b).get("registry") is True, f"(mã {s})")
s, h, b, _ = goi("/api/v1/jobs/0192f1a2", "app.danhgia.vn")
ca("tác vụ dài trên tên miền chung tới SVC-03", s == 200 and json.loads(b).get("registry") is True, f"(mã {s})")
s, h, b, _ = goi("/api/v1/tasks", "khong-co.danhgia.vn")
ca("tên miền không có trong registry: 404 Problem Details", problem(s, h, b, 404), f"(mã {s})")

# TL-04 mục 11.4: tenant TAM_NGUNG vẫn đọc được, lệnh ghi bị chặn ở APISIX.
ctl(suspend="a")
time.sleep(2.3)
s, h, b, _ = goi("/api/v1/tasks", A)
ca("tenant tạm ngừng: GET vẫn tới cell", s == 200 and cell(b) == "cell-a", f"(mã {s})")
s, h, b, _ = goi("/api/v1/tasks", A, method="HEAD")
ca("tenant tạm ngừng: HEAD vẫn tới cell", s == 200, f"(mã {s})")
for m in ("POST", "PUT", "PATCH", "DELETE"):
    s, h, b, _ = goi("/api/v1/tasks/1", A, method=m)
    j = json.loads(b or b"{}") if s == 403 else {}
    ca(f"tenant tạm ngừng: {m} bị chặn 403, mã COMMON-403-FORBIDDEN, tenantStatus TAM_NGUNG",
       problem(s, h, b, 403) and j.get("code") == "COMMON-403-FORBIDDEN" and j.get("tenantStatus") == "TAM_NGUNG",
       f"(mã {s}, {b[:160]!r})")
s, h, b, _ = goi("/realms/okrkpi/login-actions/authenticate", A, method="POST")
ca("tenant tạm ngừng: biểu mẫu đăng nhập Keycloak không bị chặn ở cổng", s == 200 and cell(b) == "cell-a", f"(mã {s})")
ck = make_cookie("11111111-1111-4111-8111-111111111111", "CELL-A", int(time.time()) + 300)
s, h, b, _ = goi("/api/v1/tasks/1", A, {"Cookie": f"mission_route={ck}"}, method="POST")
ca("tenant tạm ngừng, đi thẳng bằng cookie: lệnh ghi vẫn bị chặn", problem(s, h, b, 403), f"(mã {s})")
s, h, b, _ = goi("/api/v1/tasks/1", "kpi.b.danhgia.vn", method="POST")
ca("tenant khác đang vận hành: lệnh ghi đi bình thường", s == 200 and cell(b) == "cell-b", f"(mã {s})")
ctl(unsuspend="a")
time.sleep(2.3)
s, h, b, _ = goi("/api/v1/tasks/1", A, method="POST")
ca("mở lại tenant: lệnh ghi đi bình thường sau hạn đệm", s == 200 and cell(b) == "cell-a", f"(mã {s})")

print(f"Tổng: {dat} đạt, {hong} hỏng")
sys.exit(1 if hong else 0)
