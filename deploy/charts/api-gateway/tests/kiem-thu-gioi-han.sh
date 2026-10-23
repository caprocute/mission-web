#!/usr/bin/env bash
# Kiểm tĩnh phần giới hạn tần suất, lớp ưu tiên, cắt tải và WAF của chart api-gateway (DV-01-02): dựng bằng
# helm template ở nhiều bộ giá trị rồi đối chiếu cấu hình với TL-04 và TL-01; chạy kiểm thử bộ điều khiển suy giảm.
# Cần helm, python3 có PyYAML. Kiểm thử chạy thật trên APISIX nằm ở kiem-thu-cuc-bo.sh (cần Docker).
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT
ENVS=../../environments
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
helm template g . -f tests/values-ci.yaml > "$OUT/m0.yaml"
helm template g . -f tests/values-ci.yaml --set degrade.level=2 > "$OUT/m2.yaml"
helm template g . -f tests/values-ci.yaml --set degrade.level=3 > "$OUT/m3.yaml"
helm template g . -f tests/values-ci.yaml --set-json 'rateLimit.tenantQuota.overrides=[{"tenant":"t-a","accounts":1200},{"tenant":"t-b","accounts":500},{"tenant":"t-c","rps":50}]' > "$OUT/qt.yaml"
helm template g . -f tests/values-kind.yaml -f "$ENVS/kind/api-gateway/values.yaml" > "$OUT/kind.yaml"
helm template g . -f tests/values-ci.yaml -f "$ENVS/staging/api-gateway/values.yaml" > "$OUT/staging.yaml"
helm template g . -f tests/values-ci.yaml -f "$ENVS/production/api-gateway/values.yaml" > "$OUT/production.yaml"

python3 - "$OUT" <<'PY' > "$OUT/kq.txt" || true
import json, sys, yaml
d = sys.argv[1]
def docs(n): return [x for x in yaml.safe_load_all(open(f"{d}/{n}.yaml")) if x]
def kq(c, m): print(("[ĐẠT] " if c else "[HỎNG] ") + m)
def plugins(obj): return {p["name"]: p["config"] for p in obj["spec"]["plugins"]}
def pcs(ds): return {(o["metadata"]["namespace"], o["metadata"]["name"]): o for o in ds if o["kind"] == "ApisixPluginConfig"}

m0 = docs("m0"); pc = pcs(m0)
# Tuyến công khai (DV-01-03, nhãn vt.io/public) không qua protected-api nên không thuộc lớp ưu tiên nào.
routes = {o["metadata"]["name"]: o for o in m0 if o["kind"] == "ApisixRoute" and "vt.io/public" not in o["metadata"].get("labels", {})}
nss = {o["metadata"]["namespace"] for o in routes.values()}
kq(all((ns, n) in pc for ns in nss for n in ("protected-api", "protected-api-p1", "protected-api-p3")),
   f"mỗi namespace có tuyến ({len(nss)}) có đủ ba ApisixPluginConfig theo lớp ưu tiên")
kq(all(o["metadata"]["labels"].get("vt.io/degrade-level") == "0" and o["metadata"]["labels"].get("vt.io/degrade-managed") == "true"
       for o in pc.values()), "ApisixPluginConfig mang nhãn vt.io/degrade-level=0, vt.io/degrade-managed=true")
p2 = plugins(pc[("core", "protected-api")])
lc = p2["limit-count"]
kq(lc["key"] == "$vt_tenant $vt_sub" and lc["count"] == 600 and lc["time_window"] == 60 and lc["group"] == "vt-user",
   "tầng 1: limit-count 600/60 s khoá $vt_tenant $vt_sub, group dùng chung giữa các tuyến (TL-04)")
kq(lc["policy"] == "redis-cluster" and lc["redis_cluster_ssl"] and lc["redis_cluster_ssl_verify"] and lc["allow_degradation"]
   and lc["show_limit_quota_header"], "tầng 1: Valkey Cluster TLS kiểm chứng chỉ, allow_degradation, header hạn mức")
lr = p2["limit-req"]
kq((lr["rate"], lr["burst"], lr["key"], lr["nodelay"]) == (400, 200, "vt_tenant", True), "tầng 2: limit-req 400 burst 200 theo tenant, nodelay")
rr = p2["response-rewrite"]
kq(rr["vars"] == [["status", "==", 429]] and rr["headers"]["set"]["Retry-After"] == "30"
   and rr["headers"]["set"]["Content-Type"] == "application/problem+json" and json.loads(rr["body"])["status"] == 429,
   "429: thân RFC 9457, Retry-After 30, Content-Type application/problem+json")
conn = [plugins(pc[("core", n)])["limit-conn"]["conn"] for n in ("protected-api-p1", "protected-api", "protected-api-p3")]
kq(conn[0] > conn[1] > conn[2] and plugins(pc[("core", "protected-api-p3")])["limit-conn"]["rejected_code"] == 503,
   f"limit-conn theo lớp giảm dần {conn}, từ chối 503 (lớp 3 bị từ chối trước)")
kq(p2["client-control"]["max_body_size"] == 1048576, "thân JSON tối đa 1 MB (TL-01 F6)")
kq("fault-injection" not in p2 and p2["proxy-rewrite"]["headers"]["set"]["X-Vt-Degrade-Level"] == "0", "mức 0: không cắt tải")
kq(all("vt.io/priority-class" in o["metadata"]["labels"] for o in routes.values()), f"mọi ApisixRoute ({len(routes)}) có nhãn lớp ưu tiên")
cls = {n: o["metadata"]["labels"]["vt.io/priority-class"] for n, o in routes.items()}
kq(cls.get("core-submit") == "1" and cls.get("esign") == "1" and cls.get("core-export") == "3" and cls.get("ai") == "3"
   and cls.get("analytics") == "3" and cls.get("search") == "3" and cls.get("mission-core") == "2",
   f"nộp, ký ở lớp 1; nghiệp vụ lớp 2; xuất, AI, phân tích, tìm kiếm ở lớp 3: {cls}")
def rplug(n): return {p["name"]: p["config"] for p in routes[n]["spec"]["http"][0].get("plugins", [])}
ex = rplug("core-export")["limit-count"]
kq((ex["count"], ex["time_window"]) == (10, 60) and routes["core-export"]["spec"]["http"][0]["match"]["methods"] == ["POST"],
   "tầng 3: lệnh xuất 10 lần mỗi phút mỗi người (TL-04), chỉ POST")
kq(rplug("files-uploads")["client-control"]["max_body_size"] == 16 * 1024 * 1024, "tuyến tải lên: max_body_size 16 MiB theo hợp đồng phần tải lên")
# Tuyến chặn (deny, DV-01-03) khớp chính các thao tác serviceClient để trả 404, nên bỏ qua khi kiểm.
paths = [p for o in routes.values() for h in o["spec"]["http"] if not any(x["name"] == "fault-injection" and x["config"]["abort"]["http_status"] == 404 for x in h.get("plugins", []))
         for p in h["match"]["paths"]]
kq(not any(p.startswith(("/api/v1/ai/ocr", "/api/v1/esign/seals")) for p in paths), "không công bố thao tác serviceClient (POST /ai/ocr, /esign/seals) ngoài tuyến chặn 404")
gr = [o for o in m0 if o["kind"] == "ApisixGlobalRule"][0]; g = plugins(gr)
cz = json.loads(g["coraza-filter"]["conf"])["directives_map"]["default"]
kq("SecRuleEngine On" in cz and any("blocking_paranoia_level=2" in x for x in cz) and any("inbound_anomaly_score_threshold=5" in x for x in cz)
   and cz[-1] == "Include @owasp_crs/*.conf", "Coraza: chặn, CRS 4 paranoia 2, ngưỡng 5, loại trừ nạp trước CRS")
kq(any("tx.allowed_methods=GET HEAD POST OPTIONS PUT PATCH DELETE" in x for x in cz), "Coraza cho phép PUT, PATCH, DELETE của hợp đồng")
kq(all(any(f"id:{i}," in x for x in cz) for i in (10010, 10020, 10030, 10040)), "loại trừ tối thiểu cho tải lên nhị phân, profileId, BPMN, CEL")
kq("vt-waf-body" in g and "vt-problem" in g and json.loads(json.dumps(g["vt-problem"]))["problems"]["503"]["retry_after"] == 60,
   "quy tắc toàn cục có vt-waf-body (thân request vào Coraza) và vt-problem (503, 413 theo RFC 9457)")
cm = [o for o in m0 if o["kind"] == "ConfigMap" and o["metadata"]["name"] == "apisix-degrade-levels"]
kq(cm and len(cm[0]["data"]) == 12, "ConfigMap apisix-degrade-levels có 12 bộ plugin (3 lớp × 4 mức)")
lv0 = json.loads(cm[0]["data"]["p2-0.json"])
kq({p["name"]: p["config"] for p in lv0} == p2, "bộ plugin mức 0 trong ConfigMap trùng ApisixPluginConfig đã dựng")
cr = [o for o in m0 if o["kind"] == "ClusterRole" and o["metadata"]["name"] == "apisix-degrade-controller"][0]
kq(cr["rules"] == [{"apiGroups": ["apisix.apache.org"], "resources": ["apisixpluginconfigs"], "verbs": ["get", "list", "patch"]}],
   "bộ điều khiển suy giảm chỉ được get, list, patch ApisixPluginConfig")
dep = [o for o in m0 if o["kind"] == "Deployment" and o["metadata"]["name"] == "apisix-degrade-controller"]
kq(bool(dep) and "@sha256:" in dep[0]["spec"]["template"]["spec"]["containers"][0]["image"], "Deployment bộ điều khiển suy giảm, ảnh theo digest")

pc2 = pcs(docs("m2"))
lr2 = plugins(pc2[("core", "protected-api")])["limit-req"]
kq((lr2["rate"], lr2["burst"]) == (280, 140), f"mức 2: giới hạn tenant giảm 30% → {lr2['rate']}/{lr2['burst']}")
fi = plugins(pc2[("core", "protected-api")]).get("fault-injection", {}).get("abort", {})
kq(fi.get("vars") == [[["uri", "~~", "^/api/v1/ai/"]]], "mức 2 giữ tắt AI của mức 1")
pc3 = pcs(docs("m3"))
for n in ("protected-api", "protected-api-p3"):
    a = plugins(pc3[("core", n)])["fault-injection"]["abort"]
    kq(a["http_status"] == 503 and "vars" not in a and a["headers"]["Retry-After"] == "60"
       and a["headers"]["Content-Type"] == "application/problem+json" and json.loads(a["body"])["status"] == 503,
       f"mức 3: {n} trả 503, Retry-After 60, thân RFC 9457")
a1 = plugins(pc3[("core", "protected-api-p1")])["fault-injection"]["abort"]
kq("vars" in a1, "mức 3: lớp 1 vẫn qua (chỉ tắt AI)")
w = plugins(pcs(docs("qt"))[("core", "protected-api")])["workflow"]["rules"]
counts = {r["case"][0][2]: r["actions"][0][1]["count"] for r in w}
kq(counts == {"t-a": 720, "t-b": 600, "t-c": 1500}, f"ghi đè tenant: 2 rps/100 tài khoản, tối thiểu 20 rps, cửa sổ 30 s → {counts}")
for env, mode in (("kind", "DetectionOnly"), ("staging", "On"), ("production", "On")):
    ds = docs(env)
    if env == "kind":
        cmk = [o for o in ds if o["kind"] == "ConfigMap" and o["metadata"]["name"] == "apisix"][0]
        a = yaml.safe_load(cmk["data"]["apisix.yaml"])
        conf = a["global_rules"][0]["plugins"]["coraza-filter"]["conf"]
        kq(sorted(p["id"] for p in a["plugin_configs"]) == ["protected-api", "protected-api-p1", "protected-api-p3"],
           "standalone (kind): ba plugin_configs theo lớp")
        plist = yaml.safe_load(cmk["data"]["config.yaml"])["plugins"]
        kq(all(x in plist for x in ("fault-injection", "client-control", "workflow", "limit-conn", "vt-problem", "vt-waf-body")),
           "config.yaml bật các plugin mới")
    else:
        conf = plugins([o for o in ds if o["kind"] == "ApisixGlobalRule"][0])["coraza-filter"]["conf"]
    kq(f"SecRuleEngine {mode}" in json.loads(conf)["directives_map"]["default"], f"Coraza ở {env}: {mode}")
PY
sed 's/^/  /' "$OUT/kq.txt"
dat=$((dat + $(grep -c '^\[ĐẠT\]' "$OUT/kq.txt" || true))); truot=$((truot + $(grep -c '^\[HỎNG\]' "$OUT/kq.txt" || true)))
grep -q '^\[' "$OUT/kq.txt" || { truot=$((truot+1)); cat "$OUT/kq.txt"; }

phai_hong() { local mota=$1 mau=$2; shift 2
  if helm template g . -f tests/values-ci.yaml "$@" >/dev/null 2>"$OUT/e"; then hong "$mota: lẽ ra phải thất bại"
  elif grep -q "$mau" "$OUT/e"; then ok "$mota bị từ chối"; else hong "$mota: thông báo không rõ"; cat "$OUT/e"; fi; }
tuyen() { printf 'classRoutes:\n  - { name: thu, namespace: core, service: s, port: 8080, hosts: ["*.danhgia.vn"], paths: ["/api/v1/thu"], pluginConfig: protected-api, timeout: { connect: 2, send: 10, read: 10 }, %s }\n' "$1" > "$OUT/tuyen.yaml"; }
tuyen 'priorityClass: 4'; phai_hong "lớp ưu tiên ngoài 1..3" "chỉ nhận 1, 2 hoặc 3" -f "$OUT/tuyen.yaml"
tuyen 'rateLimit: khong-co'; phai_hong "tầng 3 không khai trong routeTiers" "không có trong rateLimit.routeTiers" -f "$OUT/tuyen.yaml"
if command -v luac >/dev/null || command -v luac5.1 >/dev/null; then
  for f in files/vt-problem.lua files/vt-waf-body.lua; do
    $(command -v luac || command -v luac5.1) -p "$f" && ok "cú pháp Lua $f" || hong "cú pháp Lua $f"; done
fi
python3 -c "import ast; ast.parse(open('files/degrade-controller.py').read())" && ok "cú pháp Python bộ điều khiển suy giảm" || hong "cú pháp Python"
if python3 tests/kiem-thu-dieu-khien-suy-giam.py > "$OUT/dk" 2>&1; then ok "bộ điều khiển suy giảm: $(grep -o 'Ran [0-9]* tests' "$OUT/dk"), $(tail -1 "$OUT/dk")"
else hong "bộ điều khiển suy giảm"; tail -20 "$OUT/dk"; fi
echo "Tổng (giới hạn, cắt tải, WAF): $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]
