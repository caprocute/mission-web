#!/usr/bin/env python3
"""Gọi API Mission từ pipeline theo quy ước baseline mục 13 (INF-06): `Idempotency-Key` cho mọi lệnh ghi,
`If-Match` lấy từ ETag của GET khi cập nhật, lỗi RFC 9457 in `title`/`detail`, tác vụ dài 202 + `Location` thì
hỏi lại tới khi job kết thúc. Token đọc từ biến môi trường VT_API_TOKEN (Jenkins nạp từ credential), không nhận
qua tham số dòng lệnh. Chỉ dùng thư viện chuẩn.

    vt-api.py <METHOD> <đường dẫn sau /api/v1> [--body tệp.json] [--if-match-from <đường dẫn GET>]
              [--key <Idempotency-Key>] [--doi-job] [--ra tệp.json]

Biến: VT_API_BASE (vd. https://api.vt.local/api/v1), VT_API_TOKEN, VT_API_CA (tệp CA, tuỳ chọn).
"""
import argparse, json, os, ssl, sys, time, urllib.error, urllib.request, uuid

ap = argparse.ArgumentParser()
ap.add_argument("method"); ap.add_argument("path")
ap.add_argument("--body"); ap.add_argument("--if-match-from"); ap.add_argument("--key")
ap.add_argument("--doi-job", action="store_true"); ap.add_argument("--ra")
ap.add_argument("--het-gio", type=int, default=3600, help="giây chờ tối đa job 202")
a = ap.parse_args()
BASE = os.environ["VT_API_BASE"].rstrip("/")
TOKEN = os.environ.get("VT_API_TOKEN", "")
ctx = ssl.create_default_context(cafile=os.environ.get("VT_API_CA") or None)


def goi(method, url, body=None, headers=None):
    h = {"Accept": "application/json, application/problem+json", **(headers or {})}
    if TOKEN:
        h["Authorization"] = "Bearer " + TOKEN
    data = None
    if body is not None:
        data = json.dumps(body).encode(); h["Content-Type"] = "application/json"
    req = urllib.request.Request(url if url.startswith("http") else BASE + url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, context=ctx, timeout=60) as r:
            raw = r.read()
            return r.status, dict(r.headers), (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            p = json.loads(raw)
        except ValueError:
            p = {"title": raw.decode(errors="replace")[:300]}
        # RFC 9457: in tiêu đề, chi tiết và mã lỗi nghiệp vụ, không in token hay thân yêu cầu
        print(f"LỖI {e.code} {method} {url}: {p.get('title')} — {p.get('detail', '')} {p.get('code', '')}".strip(),
              file=sys.stderr)
        sys.exit(2 if e.code == 409 else 1)


headers = {}
if a.method.upper() != "GET":
    # Khoá ổn định theo lần dựng: chạy lại cùng lần dựng không tạo bản ghi trùng
    headers["Idempotency-Key"] = a.key or os.environ.get("VT_IDEMPOTENCY_KEY") or str(uuid.uuid4())
if a.if_match_from:
    _, h, _ = goi("GET", a.if_match_from)
    etag = h.get("ETag") or h.get("Etag")
    if not etag:
        print(f"LỖI: {a.if_match_from} không trả ETag", file=sys.stderr); sys.exit(1)
    headers["If-Match"] = etag
body = json.load(open(a.body)) if a.body else ({} if a.method.upper() in ("POST", "PUT", "PATCH") else None)
st, h, kq = goi(a.method.upper(), a.path, body, headers)
if st == 202 and a.doi_job:
    loc = h.get("Location"); han = time.time() + a.het_gio; cho = 5
    while True:
        _, _, kq = goi("GET", loc)
        tt = (kq or {}).get("status", "")
        if tt in ("HOAN_TAT", "THAT_BAI", "DA_HUY"):  # JobStatus của common.yaml
            break
        if time.time() > han:
            print(f"LỖI: job {loc} quá {a.het_gio} s", file=sys.stderr); sys.exit(1)
        time.sleep(cho); cho = min(cho * 2, 60)
    if tt != "HOAN_TAT":
        print(f"LỖI: job {loc} kết thúc {tt}", file=sys.stderr)
        if a.ra:
            json.dump(kq, open(a.ra, "w"), ensure_ascii=False, indent=2)
        sys.exit(1)
if a.ra:
    json.dump(kq, open(a.ra, "w"), ensure_ascii=False, indent=2)
print(f"{a.method.upper()} {a.path}: {st}")
