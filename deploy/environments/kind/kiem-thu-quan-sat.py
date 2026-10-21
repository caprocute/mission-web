# Chạy trong pod tra-cuu (namespace observability): tra Tempo, Loki, Prometheus, Fluent Bit cho kiem-thu-quan-sat.sh.
import json, re, sys, time, urllib.parse, urllib.request

TEMPO, LOKI, PROM = "http://tempo.observability.svc:3200", "http://loki.observability.svc:3100", "http://prometheus-operated.observability.svc:9090"

def get(url):
    with urllib.request.urlopen(url, timeout=20) as r:
        return json.loads(r.read())

def kq(dat, mota):
    print(("[ĐẠT] " if dat else "[HỎNG] ") + mota, flush=True)

def loki(q, phut=30):
    now = time.time_ns()
    u = LOKI + "/loki/api/v1/query_range?" + urllib.parse.urlencode(
        {"query": q, "start": now - phut * 60 * 10**9, "end": now, "limit": 5000, "direction": "forward"})
    return [v for s in get(u)["data"]["result"] for v in s["values"]]

cmd = sys.argv[1]
if cmd == "trace":
    tid = sys.argv[2]
    spans = []
    for _ in range(18):
        try:
            t = get(f"{TEMPO}/api/v2/traces/{tid}")
            for rs in t["trace"]["resourceSpans"]:
                svc = next(a["value"]["stringValue"] for a in rs["resource"]["attributes"] if a["key"] == "service.name")
                for ss in rs["scopeSpans"]:
                    for sp in ss["spans"]:
                        spans.append((svc, sp["name"], sp["spanId"], sp.get("parentSpanId", "")))
            break
        except Exception:
            time.sleep(5)
    dv = sorted({s[0] for s in spans})
    kq({"mission-core", "consumer-mau"} <= set(dv) and any(d.startswith("apisix") for d in dv),
       f"Tempo có trace {tid} với {len(spans)} span của {dv}")
    ids = {s[2] for s in spans}
    mo_coi = [s[1] for s in spans if s[3] and s[3] not in ids]
    goc = [s for s in spans if not s[3]]
    kq(spans and not mo_coi and len(goc) == 1, f"mọi span nối về một gốc ({goc[0][0] if goc else '-'}), span mồ côi: {mo_coi}")
    dong = []
    for _ in range(12):
        dong = loki(f'{{namespace="core", cell="kind"}} | trace_id="{tid}"')
        if len(dong) >= 2:
            break
        time.sleep(5)
    kq(len(dong) >= 2, f"Loki trả {len(dong)} dòng log của trace (API và consumer) theo trace_id")
elif cmd == "targets":
    t = get(PROM + "/api/v1/targets?state=active")["data"]["activeTargets"]
    xuong = [(x["labels"].get("job"), x["scrapeUrl"], x.get("lastError")) for x in t if x["health"] != "up"]
    jobs = sorted({x["labels"].get("job") for x in t})
    kq(t and not xuong, f"{len(t) - len(xuong)}/{len(t)} target up; job: {jobs}" + (f"; xuống: {xuong}" if xuong else ""))
    ratio = get(PROM + "/api/v1/query?" + urllib.parse.urlencode({"query": "trace_completeness_ratio"}))["data"]["result"]
    kq(bool(ratio), f"trace_completeness_ratio = {ratio[0]['value'][1] if ratio else 'chưa có'}")
elif cmd == "dem":
    run, n = sys.argv[2], int(sys.argv[3])
    so = 0
    for _ in range(24):
        so = len(loki(f'{{namespace="core", service="sinh-log"}} |= "{run}"', 60))
        if so >= n:
            break
        time.sleep(5)
    kq(so == n, f"Loki nhận {so}/{n} dòng đã phát sau gián đoạn")
    r = get(PROM + "/api/v1/query?" + urllib.parse.urlencode({"query": "sum(fluentbit_output_dropped_records_total)"}))["data"]["result"]
    n_pod = get(PROM + "/api/v1/query?" + urllib.parse.urlencode({"query": "count(fluentbit_uptime)"}))["data"]["result"]
    drop = float(r[0]["value"][1]) if r else -1
    kq(drop == 0, f"fluentbit_output_dropped_records_total trên {n_pod[0]['value'][1] if n_pod else '?'} pod Fluent Bit = {drop:g}")
elif cmd == "pii":
    # Bỏ log của chính Loki: nhật ký truy vấn của Loki in lại câu LogQL có chuỗi "eyJ".
    jwt = loki('{cell="kind", service!="loki"} |= "eyJ"')
    so12 = loki('{namespace="core"} | json | debug != "" | line_format "{{.debug}}" |~ "[0-9]{12}"')
    da_che = loki('{namespace="core"} | json | debug =~ ".*[*]{3}.*"')
    kq(not jwt, f'LogQL tìm "eyJ" trả {len(jwt)} dòng')
    kq(not so12, f"LogQL tìm dãy 12 chữ số trong trường thử trả {len(so12)} dòng")
    kq(bool(da_che), f"{len(da_che)} dòng thử có dữ liệu nhạy cảm đã được che bằng ***")
