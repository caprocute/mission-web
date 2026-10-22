#!/usr/bin/env bash
# Kiểm tĩnh ba chart lớp mạng và lưu trữ của cell (INF-09): cell-mesh, cell-egress, cell-storage. helm lint, render,
# kubeconform -strict kèm schema CRD Istio, Cilium, Gateway API, Prometheus Operator, các khẳng định theo TL-04 và
# các ca phải bị từ chối khi render. Kiểm thử trên cụm: deploy/environments/kind/kiem-thu-mang-cell.sh.
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT
CRD_SCHEMA='https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
phai_loi() { # $1 mô tả, $2 chuỗi phải có trong lỗi, còn lại: lệnh helm template
  local mo=$1 chuoi=$2; shift 2
  if "$@" >/dev/null 2>"$OUT/e"; then hong "$mo lẽ ra bị từ chối"; else grep -q "$chuoi" "$OUT/e" && ok "$mo bị từ chối" || { hong "$mo: thông báo không rõ"; cat "$OUT/e"; }; fi
}
for c in cell-mesh cell-egress cell-storage; do
  echo "== $c"
  helm lint "$c" >/dev/null && ok "helm lint" || hong "helm lint $c"
  helm template t "$c" > "$OUT/$c.yaml" && ok "render" || { hong "render $c"; continue; }
  kubeconform -strict -summary -kubernetes-version 1.33.0 -schema-location default -schema-location "$CRD_SCHEMA" \
    "$OUT/$c.yaml" > "$OUT/kc" 2>&1 && ok "kubeconform -strict ($(tail -1 "$OUT/kc"))" || { hong "kubeconform $c"; cat "$OUT/kc"; }
done
python3 - "$OUT" <<'P' && ok "khẳng định TL-04 mục 3.7, 3.8, 9.5" || hong "khẳng định TL-04"
import sys, yaml
o = sys.argv[1]
doc = lambda c: [x for x in yaml.safe_load_all(open(f"{o}/{c}.yaml")) if x]
m, e, s = doc("cell-mesh"), doc("cell-egress"), doc("cell-storage")
pa = [x for x in m if x["kind"] == "PeerAuthentication" and x["metadata"]["name"] == "default"][0]
assert pa["metadata"]["namespace"] == "istio-system" and pa["spec"]["mtls"]["mode"] == "STRICT"
es = [x for x in m if x["kind"] == "AuthorizationPolicy" and x["metadata"]["name"] == "esign-allow-callers"][0]["spec"]
assert es["targetRefs"][0]["name"] == "esign-service"
assert es["rules"][0]["from"][0]["source"]["principals"] == ["cluster.local/ns/core/sa/mission-core", "cluster.local/ns/core/sa/workflow-executor"]
assert es["rules"][0]["to"][0]["operation"] == {"methods": ["POST"], "paths": ["/internal/v1/sign-requests", "/internal/v1/sign-batches"]}
assert sorted(x["metadata"]["namespace"] for x in m if x["kind"] == "Gateway") == ["egress", "secure"]
sc = {x["metadata"]["name"]: x for x in s}
n, c = sc["nvme-local"], sc["ceph-rbd"]
assert (n["provisioner"], n["volumeBindingMode"], n["reclaimPolicy"], n["allowVolumeExpansion"]) == ("topolvm.io", "WaitForFirstConsumer", "Retain", True)
assert n["parameters"] == {"topolvm.io/device-class": "nvme", "csi.storage.k8s.io/fstype": "xfs"}
assert (c["provisioner"], c["volumeBindingMode"], c["reclaimPolicy"]) == ("rbd.csi.ceph.com", "Immediate", "Delete")
assert c["parameters"]["imageFeatures"] == "layering,exclusive-lock,object-map,fast-diff" and c["parameters"]["encrypted"] == "true"
assert c["parameters"]["encryptionKMSID"] == "openbao-kms" and c["parameters"]["csi.storage.k8s.io/fstype"] == "ext4"
cnp = [x for x in e if x["kind"] == "CiliumNetworkPolicy"][0]
assert cnp["metadata"]["namespace"] == "egress" and len([r for r in cnp["spec"]["egress"] if "toFQDNs" in r]) == 8
ccnp = [x for x in e if x["kind"] == "CiliumClusterwideNetworkPolicy"][0]["spec"]
assert ccnp["egressDeny"] == [{"toEntities": ["world"]}]
dep = [x for x in e if x["kind"] == "Deployment"][0]["spec"]["template"]["spec"]
assert "@sha256:" in dep["containers"][0]["image"] and dep["containers"][0]["securityContext"]["readOnlyRootFilesystem"]
P
echo "== Ca phải bị từ chối"
phai_loi "mtlsMode PERMISSIVE" "STRICT" helm template t cell-mesh --set mtlsMode=PERMISSIVE
phai_loi "esign không có waypoint" "waypoint" helm template t cell-mesh --set waypoints.secure=null
phai_loi "ảnh egress gateway không ghim digest" "SEC-55" helm template t cell-egress --set gateway.image.digest=latest
phai_loi "ceph-rbd tắt mã hoá" "mã hoá" helm template t cell-storage --set cephRbd.encrypted=false
phai_loi "pool RBD không theo cell" "pool" helm template t cell-storage --set cephRbd.pool=rbd
phai_loi "hai StorageClass mặc định" "mặc định" helm template t cell-storage --set nvmeLocal.default=true
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]
