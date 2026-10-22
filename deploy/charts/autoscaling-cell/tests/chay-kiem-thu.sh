#!/usr/bin/env bash
# Kiểm thử tĩnh chart autoscaling-cell (INF-16): lint, render, kubeconform -strict kèm schema CRD, khẳng định
# theo TL-04 chương 6 và các ca phải bị từ chối. Cần helm, kubeconform, python3 + PyYAML.
# Dùng: deploy/charts/autoscaling-cell/tests/chay-kiem-thu.sh
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT
CRD='https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }

[ -f charts/keda-2.21.0.tgz ] && [ -f charts/prometheus-adapter-5.3.0.tgz ] || helm dependency build . >/dev/null
helm lint . >/dev/null && ok "helm lint" || hong "helm lint"
# Chart Prometheus Adapter chọn apiregistration.k8s.io/v1 theo Capabilities của cụm; render ngoài cụm phải khai báo.
helm template a . -n keda --api-versions apiregistration.k8s.io/v1 > "$OUT/a.yaml" && ok "render" || hong "render"
kubeconform -strict -summary -kubernetes-version 1.33.0 -schema-location default -schema-location "$CRD" \
  -skip CustomResourceDefinition "$OUT/a.yaml" > "$OUT/kc" 2>&1 \
  && ok "kubeconform -strict ($(tail -1 "$OUT/kc"))" || { hong kubeconform; tail -20 "$OUT/kc"; }

python3 - "$OUT/a.yaml" > "$OUT/py" <<'PY' && { sed 's/^/  [ĐẠT] /' "$OUT/py"; dat=$((dat+$(wc -l < "$OUT/py"))); } || { cat "$OUT/py"; truot=$((truot+1)); }
import re, sys, yaml
docs = [d for d in yaml.safe_load_all(open(sys.argv[1])) if d]
by = lambda k: [d for d in docs if d["kind"] == k]
anh = [c["image"] for d in by("Deployment") for c in d["spec"]["template"]["spec"]["containers"]]
assert len(anh) == 6 and all(re.search(r"@sha256:[0-9a-f]{64}$", a) and a.startswith("harbor.vt.local/") for a in anh), anh
print("6 ảnh (KEDA ba thành phần, Prometheus Adapter, pause) kéo qua Harbor và ghim digest (SEC-55)")
api = {d["metadata"]["name"] for d in by("APIService")}
assert api == {"v1beta1.external.metrics.k8s.io", "v1beta1.custom.metrics.k8s.io"}, api
ext = [d for d in by("APIService") if d["metadata"]["name"].startswith("v1beta1.external")][0]
assert "keda" in ext["spec"]["service"]["name"], ext["spec"]["service"]
print("KEDA giữ external.metrics.k8s.io, Prometheus Adapter giữ custom.metrics.k8s.io, không tranh nhau")
cm = [d for d in by("ConfigMap") if "adapter" in d["metadata"]["name"]][0]
cfg = yaml.safe_load(cm["data"]["config.yaml"])
r = cfg["rules"][0]
assert r["name"] == {"matches": "^http_server_requests_seconds_count$", "as": "http_server_requests_per_second"}
assert 'uri!~"/actuator.*"' in r["metricsQuery"] and "[1m]" in r["metricsQuery"] and "externalRules" not in cfg
print("quy tắc http_server_requests_per_second (rate 1 phút, loại /actuator) đúng TL-04 mục 6.2")
pc = by("PriorityClass")[0]
assert (pc["metadata"]["name"], pc["value"], pc["preemptionPolicy"], pc["globalDefault"]) == ("capacity-reservation", -10, "Never", False)
gc = {d["metadata"]["name"]: d for d in by("Deployment") if d["metadata"]["name"].startswith("capacity-reservation")}
assert set(gc) == {"capacity-reservation-app-general", "capacity-reservation-app-worker"}
for ten, d in gc.items():
    s = d["spec"]["template"]["spec"]
    c = s["containers"][0]
    assert d["spec"]["replicas"] == 6 and s["priorityClassName"] == "capacity-reservation"
    assert c["resources"]["requests"] == c["resources"]["limits"] == {"cpu": "5", "memory": "20Gi"}
    assert s["securityContext"]["runAsNonRoot"] and c["securityContext"]["readOnlyRootFilesystem"]
nw = gc["capacity-reservation-app-worker"]["spec"]["template"]["spec"]
assert nw["nodeSelector"] == {"vt.io/pool": "worker"} and nw["tolerations"][0]["value"] == "worker"
print("PriorityClass -10 không trục xuất ai; pod giữ chỗ 6 × (5 CPU, 20 GiB) ≈ 2 node mỗi pool app-general, worker")
assert {d["metadata"]["name"] for d in by("CiliumNetworkPolicy")} >= {"prometheus-adapter"}
print("CiliumNetworkPolicy cho KEDA và Prometheus Adapter (apiserver vào 6443, ra Prometheus 9090)")
PY

phai_hong() { local mota=$1 mau=$2; shift 2
  if helm template a . "$@" >/dev/null 2>"$OUT/e"; then hong "$mota: lẽ ra phải thất bại"
  elif grep -q "$mau" "$OUT/e"; then ok "$mota bị từ chối"; else hong "$mota: thông báo không rõ"; cat "$OUT/e"; fi; }
phai_hong "ảnh pause không ghim digest" "SEC-55" --set giuCho.image.digest=3.10
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]
