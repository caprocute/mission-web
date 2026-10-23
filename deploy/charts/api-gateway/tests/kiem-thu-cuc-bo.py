# Bên gọi của kiem-thu-cuc-bo.sh: gọi APISIX cục bộ và in kết quả từng tiêu chí của DV-01-02.
# Tham số: <url cổng> <thư mục tạm có key.pem> <pha: muc0 | valkey-ngung | phat-hien | muc1 | muc3>
import glob, json, os, sys, threading, time, urllib.error, urllib.request, uuid
import jwt, yaml

url, wdir, pha = sys.argv[1:4]
HOST = "cell.danhgia.vn"
KEY = open(os.path.join(wdir, "key.pem"), "rb").read()
CONTRACTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../../../contracts/openapi/modules")


def token(tenant="t-lon", sub=None):
    now = int(time.time())
    return jwt.encode({"iss": "http://keycloak:8080/realms/mission", "sub": sub or "u-" + uuid.uuid4().hex[:12],
                       "aud": "apisix-gateway", "azp": "mission-web", "tenant_id": tenant, "cell": "kind",
                       "iat": now, "exp": now + 3600}, KEY, algorithm="RS256", headers={"kid": "thu"})


def goi(path, tok=None, method="GET", body=None, ctype="application/json"):
    data = None
    if body is not None:
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
    req = urllib.request.Request(url + path, data=data, method=method, headers={"Host": HOST})
    if data is not None:
        req.add_header("Content-Type", ctype)
    if tok:
        req.add_header("Authorization", "Bearer " + tok)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, dict(r.headers), r.read().decode(errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode(errors="replace")


def kq(dat, mota):
    print(("[ĐẠT] " if dat else "[HỎNG] ") + mota, flush=True)


def problem(s, h, b, status, retry):
    try:
        body = json.loads(b)
    except ValueError:
        body = {}
    return (s == status and h.get("Content-Type", "").startswith("application/problem+json")
            and body.get("status") == status and body.get("type", "").startswith("https://danhgia.vn/problems/")
            and h.get("Retry-After") == retry)


def lap_toi(path, tok, n, method="GET"):
    for i in range(1, n + 1):
        s, h, b = goi(path, tok, method)
        if s == 429:
            return i, h, b
    return None


def vi_du_hop_dong():
    """Ví dụ thân request JSON của hợp đồng: (phương thức, đường dẫn, thân)."""
    for f in sorted(glob.glob(os.path.join(CONTRACTS, "*.yaml"))):
        d = yaml.safe_load(open(f))
        for p, ops in (d.get("paths") or {}).items():
            for m, op in (ops or {}).items():
                if not isinstance(op, dict) or m not in ("post", "put", "patch"):
                    continue
                c = ((op.get("requestBody") or {}).get("content") or {}).get("application/json") or {}
                ex = []
                if "example" in c:
                    ex.append(c["example"])
                for v in (c.get("examples") or {}).values():
                    if isinstance(v, dict) and "value" in v:
                        ex.append(v["value"])
                path = "/api/v1" + "/".join(
                    "0190a1b2-0000-7000-8000-000000000001" if seg.startswith("{") else seg for seg in p.split("/"))
                for e in ex:
                    yield m.upper(), path, e


if pha == "muc0":
    s, h, b = goi("/api/v1/tasks")
    kq(s == 401, f"không có JWT → {s}")
    s, h, b = goi("/api/v1/tasks", token())
    up = json.loads(b) if s == 200 else {}
    kq(s == 200 and up.get("headers", {}).get("x-vt-degrade-level") == "0",
       f"JWT hợp lệ → {s}, upstream nhận X-Vt-Degrade-Level: {up.get('headers', {}).get('x-vt-degrade-level')}")
    # Tầng 1: 40 request mỗi 60 s mỗi người (values-cuc-bo), dùng chung giữa các tuyến nhờ group.
    t = token()
    for i in range(20):
        goi("/api/v1/plans", t)
    dau = lap_toi("/api/v1/tasks", t, 30)
    kq(dau is not None and dau[0] == 21, f"tầng 1: 429 ở request thứ {dau[0] if dau else '-'} trên tuyến thứ hai "
       "sau 20 request tuyến thứ nhất (hạn mức 40 dùng chung giữa các tuyến)")
    if dau:
        kq(problem(429, dau[1], dau[2], 429, "30"),
           f"tầng 1: thân RFC 9457, Retry-After {dau[1].get('Retry-After')}, {dau[2][:90]}")
    # Tầng 3: tạo tệp xuất 3 lần mỗi 60 s mỗi người.
    t = token()
    dau = lap_toi("/api/v1/tasks/export", t, 6, "POST")
    kq(dau is not None and dau[0] == 4, f"tầng 3: lệnh xuất bị 429 ở lần thứ {dau[0] if dau else '-'} (hạn mức 3)")
    s, h, b = goi("/api/v1/tasks/exports/0190a1b2", t)
    kq(s == 200, f"tầng 3 chỉ áp cho lệnh tạo: hỏi trạng thái job xuất vẫn qua → {s}")
    # Ghi đè theo tenant: t-nho 1 rps → 30 request mỗi cửa sổ 30 s, chung cho mọi người của tenant.
    dau = None
    for i in range(1, 40):
        s, h, b = goi("/api/v1/okr/objectives", token("t-nho"))
        if s == 429:
            dau = (i, h, b)
            break
    kq(dau is not None and dau[0] == 31, f"ghi đè tenant nhỏ (registry.tenant_quota): 429 ở request thứ "
       f"{dau[0] if dau else '-'} dù mỗi request một người khác")
    s, h, b = goi("/api/v1/okr/objectives", token("t-lon"))
    kq(s == 200, f"tenant khác không bị ảnh hưởng → {s}")
    # Lớp 3 một request đồng thời: request thứ hai trong lúc request đầu đang chờ upstream bị 503.
    kqs = {}
    def mot(k):
        kqs[k] = goi("/api/v1/analytics/slow", token())
    th = threading.Thread(target=mot, args=("a",)); th.start(); time.sleep(0.4)
    s, h, b = goi("/api/v1/analytics/slow", token())
    th.join()
    kq(kqs["a"][0] == 200 and problem(s, h, b, 503, "60"),
       f"limit-conn lớp 3: request song song thứ hai → {s} {h.get('Content-Type')} Retry-After {h.get('Retry-After')}")
    s, h, b = goi("/api/v1/tasks/slow", token())
    kq(s == 200, f"lớp 2 cùng tenant vẫn qua khi lớp 3 đầy → {s}")
    # Kích thước thân: JSON 1 MB, phần tải lên 16 MiB.
    s, h, b = goi("/api/v1/tasks", token(), "POST", b"[" + b"1," * (512 * 1024) + b"1]")
    kq(problem(s, h, b, 413, None),
       f"thân 1 MB + 10 byte trên tuyến JSON → {s} {h.get('Content-Type')}")
    s, h, b = goi("/api/v1/files/uploads/u1/parts/1", token(), "PUT", b"x" * (2 * 1024 * 1024),
                  "application/octet-stream")
    kq(s == 200, f"phần tải lên 2 MB qua tuyến tải lên → {s}")
    s, h, b = goi("/api/v1/files/uploads/u1/parts/2", token(), "PUT", b"x" * (16 * 1024 * 1024 + 1),
                  "application/octet-stream")
    kq(s == 413, f"phần tải lên 16 MiB + 1 byte → {s}")
    # WAF chế độ chặn.
    s, h, b = goi("/api/v1/tasks?id=1%27%20OR%20%271%27=%271%27--", token())
    kq(s == 403, f"Coraza chặn SQLi trong query → {s}")
    s, h, b = goi("/api/v1/catalog/items?q=%3Cscript%3Ealert(1)%3C/script%3E")
    kq(s == 403, f"Coraza chặn XSS trong query → {s}")
    s, h, b = goi("/api/v1/catalog/items", None, "POST", {"title": "<script>alert(document.cookie)</script>"})
    kq(s == 403, f"Coraza chặn XSS trong thân JSON → {s}")
    s, h, b = goi("/api/v1/catalog/items", None, "POST", {"note": "x' UNION SELECT password FROM users--"})
    kq(s == 403, f"Coraza chặn SQLi trong thân JSON → {s}")
    # Ví dụ request của hợp đồng không bị chặn nhầm.
    tong, chan = 0, []
    for m, p, e in vi_du_hop_dong():
        tong += 1
        s, h, b = goi(p, token(), m, e)
        if s == 403 and "problem+json" not in h.get("Content-Type", ""):
            chan.append(f"{m} {p}")
    kq(tong > 50 and not chan, f"{tong} ví dụ thân request của hợp đồng, bị WAF chặn nhầm: {len(chan)} "
       + (str(chan[:10]) if chan else ""))

elif pha == "valkey-ngung":
    t = token()
    ss = [goi("/api/v1/tasks", t)[0] for _ in range(3)]
    kq(all(s == 200 for s in ss), f"Valkey ngừng: request vẫn qua → {ss}")

elif pha == "phat-hien":
    s, h, b = goi("/api/v1/catalog/items?q=%3Cscript%3Ealert(1)%3C/script%3E")
    kq(s == 200, f"chế độ phát hiện (dev): payload XSS không bị chặn, chỉ ghi nhật ký → {s}")

elif pha == "muc1":
    s, h, b = goi("/api/v1/ai/suggestions", token(), "POST", {"kind": "okr"})
    kq(problem(s, h, b, 503, "60"), f"mức 1: trợ lý AI tắt → {s} Retry-After {h.get('Retry-After')}")
    s, h, b = goi("/api/v1/tasks", token())
    up = json.loads(b) if s == 200 else {}
    kq(s == 200 and up.get("headers", {}).get("x-vt-degrade-level") == "1",
       f"mức 1: nghiệp vụ vẫn qua, upstream nhận mức 1 → {s} {up.get('headers', {}).get('x-vt-degrade-level')}")
    s, h, b = goi("/api/v1/analytics/dashboards", token())
    kq(s == 200, f"mức 1: bảng điều hành vẫn đọc được (upstream đọc tổng hợp sẵn) → {s}")

elif pha == "muc3":
    for p, m in (("/api/v1/tasks", "GET"), ("/api/v1/analytics/dashboards", "GET"), ("/api/v1/tasks/export", "POST")):
        s, h, b = goi(p, token(), m)
        kq(problem(s, h, b, 503, "60"), f"mức 3: {m} {p} (lớp 2/3) → {s} Retry-After {h.get('Retry-After')} {b[:70]}")
    for p, m in (("/api/v1/okr/objectives/0190a1b2/submit", "POST"), ("/api/v1/esign/requests", "POST"),
                 ("/api/v1/dossiers/packages/0190a1b2/sign", "POST")):
        s, h, b = goi(p, token(), m, {})
        kq(s == 200, f"mức 3: {m} {p} (lớp 1, nộp và ký) vẫn qua → {s}")
