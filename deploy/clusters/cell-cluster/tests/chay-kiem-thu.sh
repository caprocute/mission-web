#!/usr/bin/env bash
# Kiểm tĩnh khuôn Cluster API (INF-08): render cell-g4-hl với mẫu hạ tầng, kubeconform -strict theo schema CRD
# Cluster API và CAPO, đếm control plane, MachineDeployment, taint, nhãn; các ca phải thất bại; chính sách kiểm
# toán API và cờ API server (INF-19, tests/test_kiem_toan_api.py).
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT
CRD='https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
V=(-f cell-g4-hl/values.yaml -f cell-g4-hl/values-mau-ha-tang.yaml)
helm lint cell-cluster "${V[@]}" >/dev/null && ok "helm lint" || hong "helm lint"
helm template x cell-cluster "${V[@]}" > "$OUT/c.yaml" && ok "render cell-g4-hl" || hong render
kubeconform -strict -summary -schema-location default -schema-location "$CRD" "$OUT/c.yaml" > "$OUT/kc" 2>&1 \
  && ok "kubeconform -strict schema Cluster API ($(tail -1 "$OUT/kc"))" || { hong kubeconform; cat "$OUT/kc"; }
python3 - "$OUT/c.yaml" > "$OUT/py" <<'PY' && { sed 's/^/  [ĐẠT] /' "$OUT/py"; dat=$((dat+$(wc -l < "$OUT/py"))); } || { cat "$OUT/py"; truot=$((truot+1)); }
import sys, yaml
docs = [d for d in yaml.safe_load_all(open(sys.argv[1])) if d]
kcp = [d for d in docs if d["kind"] == "KubeadmControlPlane"][0]
assert kcp["spec"]["replicas"] == 3 and kcp["spec"]["version"] == "v1.36.0"
oc = [d for d in docs if d["kind"] == "OpenStackCluster"][0]
assert oc["spec"]["controlPlaneAvailabilityZones"] == ["hl-a", "hl-b", "hl-c"]
print("KubeadmControlPlane 3 master trên 3 vùng, Kubernetes v1.36.0")
md = [d for d in docs if d["kind"] == "MachineDeployment"]
kct = {d["metadata"]["name"]: d for d in docs if d["kind"] == "KubeadmConfigTemplate"}
pools = {d["metadata"]["labels"]["vt.io/pool"] for d in md}
assert len(pools) == 9 and "gpu" not in pools, pools
for d in md:
    assert d["spec"]["template"]["spec"]["failureDomain"] in ("hl-a", "hl-b", "hl-c")
print(f"{len(md)} MachineDeployment cho 9 pool (gpu tắt ở G4) × 3 vùng, mỗi cái gắn failureDomain")
taint = {"system": "CriticalAddonsOnly", "ingress": "vt.io/pool=ingress", "app-general": None, "app-worker": "vt.io/pool=worker",
         "data-postgres-large": "vt.io/pool=postgres", "data-postgres-small": "vt.io/pool=postgres", "data-kafka": "vt.io/pool=kafka",
         "data-search": "vt.io/pool=search", "security-zone": "vt.io/zone=secure"}
for d in md:
    pool = d["metadata"]["labels"]["vt.io/pool"]
    nr = kct[d["spec"]["template"]["spec"]["bootstrap"]["configRef"]["name"]]["spec"]["template"]["spec"]["joinConfiguration"]["nodeRegistration"]
    t = nr["taints"]
    want = taint[pool]
    got = None if not t else (t[0]["key"] if t[0]["key"] == "CriticalAddonsOnly" else f'{t[0]["key"]}={t[0]["value"]}')
    assert got == want and (not t or t[0]["effect"] == "NoSchedule"), (pool, got)
    lb = nr["kubeletExtraArgs"]["node-labels"]
    for k in ("topology.kubernetes.io/zone=", "vt.io/rack=", "vt.io/pool="):
        assert k in lb, (pool, lb)
    if pool == "security-zone": assert "vt.io/zone=secure" in lb
    if pool.startswith("data-postgres"): assert "vt.io/pg-size=" in lb
print("taint NoSchedule và nhãn zone, rack, pool, pg-size, vt.io/zone=secure đúng bảng TL-04 mục 3.3")
auto = {d["metadata"]["labels"]["vt.io/pool"] for d in md if "cluster.x-k8s.io/cluster-api-autoscaler-node-group-min-size" in d["metadata"].get("annotations", {})}
assert auto == {"ingress", "app-general", "app-worker"}, auto
mn = sum(int(d["metadata"]["annotations"]["cluster.x-k8s.io/cluster-api-autoscaler-node-group-min-size"]) for d in md if d["metadata"]["labels"]["vt.io/pool"] == "app-general")
mx = sum(int(d["metadata"]["annotations"]["cluster.x-k8s.io/cluster-api-autoscaler-node-group-max-size"]) for d in md if d["metadata"]["labels"]["vt.io/pool"] == "app-general")
assert (mn, mx) == (9, 18)
print("chỉ ingress, app-general, app-worker co giãn; app-general 9/18 node")
cap = {d["metadata"]["labels"]["vt.io/pool"]: (d["metadata"]["annotations"].get("capacity.cluster-autoscaler.kubernetes.io/cpu"),
       d["metadata"]["annotations"].get("capacity.cluster-autoscaler.kubernetes.io/memory")) for d in md if d["metadata"].get("annotations")}
assert cap == {"ingress": ("16", "32Gi"), "app-general": ("16", "64Gi"), "app-worker": ("16", "64Gi")}, cap
print("MachineDeployment co giãn có chú thích dung lượng node cho Cluster Autoscaler (INF-16)")
ca = [d for d in docs if d["kind"] == "Deployment" and d["metadata"]["name"] == "cluster-autoscaler-cell-g4-hl"][0]
c = ca["spec"]["template"]["spec"]["containers"][0]
assert "--cloud-provider=clusterapi" in c["command"] and "--balance-similar-node-groups=true" in c["command"], c["command"]
assert "--node-group-auto-discovery=clusterapi:namespace=capi-cells,clusterName=cell-g4-hl" in c["command"]
assert "@sha256:" in c["image"]
assert ca["spec"]["template"]["spec"]["volumes"][0]["secret"]["secretName"] == "cell-g4-hl-kubeconfig"
print("Cluster Autoscaler provider Cluster API: tự phát hiện theo cụm, cân bằng ba vùng, kubeconfig cụm cell, ảnh theo digest")
PY
phai_hong() { local mota=$1 mau=$2; shift 2
  if helm template x cell-cluster "$@" >/dev/null 2>"$OUT/e"; then hong "$mota: lẽ ra phải thất bại"
  elif grep -q "$mau" "$OUT/e"; then ok "$mota bị từ chối"; else hong "$mota: thông báo không rõ"; cat "$OUT/e"; fi; }
phai_hong "thiếu thông số hạ tầng" "thông số riêng của môi trường" -f cell-g4-hl/values.yaml
phai_hong "pool dữ liệu co giãn tự động" "không được co giãn" "${V[@]}" --set-json 'pools.data-kafka.autoscale={"min":1,"max":3}'
phai_hong "pool lạ" "không có trong bảng" "${V[@]}" --set-json 'pools.abc={"enabled":true,"perZone":[1,1,1]}'
grep -rqiE 'viettel.*(password|token)|[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}:[0-9]+' cell-cluster/templates cell-cluster/values.yaml cell-g4-hl/values.yaml \
  && hong "khuôn chứa địa chỉ hoặc bí mật" || ok "khuôn và values cell không chứa địa chỉ máy chủ hay bí mật"
# INF-19: chính sách kiểm toán API đúng TL-04 và cờ API server (anonymous-auth, profiling, KMS v2, TLS ≥ 1.2).
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s cell-cluster/tests -p 'test_*.py' > "$OUT/ut" 2>&1 \
  && ok "kiểm toán API và cờ API server ($(grep -o 'Ran [0-9]* tests' "$OUT/ut"))" || { hong "test_kiem_toan_api"; tail -20 "$OUT/ut"; }
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]
