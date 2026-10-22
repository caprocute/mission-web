# Chạy trong pod client ở namespace edge (python:3.12-alpine): gọi APISIX và in kết quả từng tiêu chí.
# Tham số: <url cổng> <token đúng cell> <token sai cell>
import json, sys, urllib.request, urllib.error
url, tok, tok_sai = sys.argv[1:4]
HOST = "cell.danhgia.vn"

def goi(path, token=None):
    req = urllib.request.Request(url + path, headers={"Host": HOST})
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, dict(r.headers), r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode()

def kq(dat, mota):
    print(("[ĐẠT] " if dat else "[HỎNG] ") + mota, flush=True)

s, h, b = goi("/api/v1/tasks")
kq(s == 401, f"không có JWT → {s}")
s, h, b = goi("/api/v1/tasks", tok_sai)
kq(s in (401, 403), f"JWT hợp lệ nhưng claim cell khác → {s} {b[:80]}")
s, h, b = goi("/api/v1/tasks", tok)
kq(s == 200, f"JWT đúng cell → {s}")
up = json.loads(b) if s == 200 else {}
kq("traceparent" in up, f"upstream nhận traceparent W3C: {up.get('traceparent', '-')}")
thieu = [k for k in ("Strict-Transport-Security", "X-Content-Type-Options", "Referrer-Policy",
                     "Permissions-Policy", "Cross-Origin-Opener-Policy") if k not in h]
kq(not thieu, "header bảo mật SEC-24 có đủ" + (f" (thiếu {thieu})" if thieu else ""))
s, h, b = goi("/api/v1/tasks?id=1%27%20OR%20%271%27=%271%27--", tok)
kq(s == 403, f"payload SQLi mẫu bị Coraza chặn → {s}")
s, h, b = goi("/api/v1/tasks?id=7&q=%3Cscript%3Ealert(1)%3C/script%3E")
kq(s == 403, f"payload XSS mẫu bị Coraza chặn (trước bước xác thực) → {s}")

dau = None
for i in range(1, 651):
    s, h, b = goi("/api/v1/tasks", tok)
    if s == 429:
        dau = (i, h, b)
        break
if not dau:
    kq(False, "650 request trong một phút không có 429")
else:
    i, h, b = dau
    kq(True, f"429 xuất hiện ở request thứ {i} trong cửa sổ 60 s (cộng 1 request hợp lệ trước đó: đúng ngưỡng 600)")
    try:
        body = json.loads(b)
    except ValueError:
        body = {}
    kq(body.get("type") == "https://danhgia.vn/problems/rate-limited" and body.get("status") == 429,
       f"thân RFC 9457: {b}")
    kq(h.get("Content-Type", "").startswith("application/problem+json"), f"Content-Type: {h.get('Content-Type')}")
    kq(h.get("Retry-After") == "30", f"Retry-After: {h.get('Retry-After')}")
