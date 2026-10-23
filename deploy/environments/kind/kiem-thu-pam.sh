#!/usr/bin/env bash
# shellcheck disable=SC2015  # mẫu "lệnh && ok || hong" có chủ ý: ok và hong luôn trả 0.
# Kiểm thử PAM "không có cửa sau" trên kind (INF-27). CHƯA CHẠY trong INF-27: máy agent chỉ còn khoảng 2 GB đĩa trống,
# không đủ dựng cụm kind. Chạy trên máy có ≥ 10 GB trống sau tao-cum.sh (Kubernetes ≥ 1.30 cho
# ValidatingAdmissionPolicy v1), cell-base và security-cell (vai trò debug-app, platform-admin). Không có sản phẩm PAM
# thật: "PAM giả lập" là kubectl --as định danh broker (oidc:service-account-pam-broker) tạo binding đúng hợp đồng, nên
# phần ghi hình phiên và duyệt hai người phía sản phẩm KHÔNG được kiểm ở đây. Các bước:
#   1. cài pam-integration với values-kind-pam.yaml;
#   2. chính sách nhập: binding tạo tay tới platform-admin bị từ chối (kể cả admin của kind), broker thiếu người duyệt
#      hoặc thời hạn > 4 giờ bị từ chối;
#   3. PAM giả lập cấp debug-app 90 giây cho oidc:nam ở core → exec được; quá hạn → CronJob quét thu hồi, exec bị từ chối,
#      nhật ký Job có sự kiện pam.thu-hoi (đích nhật ký thử);
#   4. binding tạo tay tới edit (ngoài chính sách nhập) → CronJob quét báo pam.cua-sau-rbac;
#   5. (khi có pg-core và PG_ANH=<kho>@sha256:<digest> ảnh có psql) vai trò thừa quyền cua_sau_thu → quét CSDL báo.
set -euo pipefail
cd "$(dirname "$0")"
GOC=$(cd ../../.. && pwd)
NS=pam-kiem-soat
BROKER=oidc:service-account-pam-broker
OUT=$(mktemp -d); trap 'rm -rf "${OUT:?}"' EXIT
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
bay_gio() { date -u -d "@$(( $(date +%s) + ${1:-0} ))" +%Y-%m-%dT%H:%M:%SZ; }
chay_quet() { # <cronjob> <tên job>
  kubectl -n "$NS" delete job "$2" --ignore-not-found >/dev/null
  kubectl -n "$NS" create job "$2" --from="cronjob/$1" >/dev/null
  kubectl -n "$NS" wait --for=condition=complete "job/$2" --timeout=3m >/dev/null
  kubectl -n "$NS" logs "job/$2" --all-containers
}
binding_pam() { # <tên> <vai trò> <namespace|-> <người duyệt> <giây sống>
  local kind=rolebinding nsarg=(-n "$3")
  [ "$3" = - ] && { kind=clusterrolebinding; nsarg=(); }
  kubectl --as="$BROKER" apply "${nsarg[@]}" -f - <<YAML
apiVersion: rbac.authorization.k8s.io/v1
kind: $([ "$kind" = rolebinding ] && echo RoleBinding || echo ClusterRoleBinding)
metadata:
  name: $1
  labels: { vt.io/cap-phat: pam }
  annotations:
    vt.io/pam-yeu-cau: YC-THU-$RANDOM
    vt.io/nguoi-yeu-cau: nam
    vt.io/nguoi-duyet: "$4"
    vt.io/cap-luc: "$(bay_gio 0)"
    vt.io/het-han: "$(bay_gio "$5")"
roleRef: { apiGroup: rbac.authorization.k8s.io, kind: ClusterRole, name: $2 }
subjects:
  - { apiGroup: rbac.authorization.k8s.io, kind: User, name: "oidc:nam" }
YAML
}
kubectl config use-context kind-vt-cell-kind >/dev/null

echo "== 1. Cài pam-integration"
THEM=()
if [ -n "${PG_ANH:-}" ] && kubectl -n data-pg get cluster.postgresql.cnpg.io pg-core >/dev/null 2>&1; then
  THEM=(--set quetCsdl.enabled=true --set "images.postgresql.repository=${PG_ANH%@*}" --set "images.postgresql.digest=${PG_ANH#*@}")
fi
helm upgrade --install pam-integration "$GOC/deploy/charts/pam-integration" -f values-kind-pam.yaml "${THEM[@]}" --wait --timeout 5m
ok "đã cài"

echo "== 2. Chính sách nhập"
kubectl create clusterrolebinding tao-tay-thu --clusterrole=platform-admin --user=oidc:nam >/dev/null 2>&1 \
  && { hong "binding tạo tay tới platform-admin lọt qua"; kubectl delete clusterrolebinding tao-tay-thu >/dev/null; } \
  || ok "binding tạo tay tới platform-admin bị từ chối"
binding_pam pam-thu-mot-duyet platform-admin - "duyet1" 3600 >/dev/null 2>&1 \
  && hong "platform-admin một người duyệt lọt qua" || ok "platform-admin một người duyệt bị từ chối"
binding_pam pam-thu-dai debug-app core "duyet1" 18000 >/dev/null 2>&1 \
  && hong "thời hạn 5 giờ lọt qua" || ok "thời hạn quá 4 giờ bị từ chối"
binding_pam pam-thu-tu-duyet debug-app core "nam" 600 >/dev/null 2>&1 \
  && hong "tự duyệt lọt qua" || ok "người yêu cầu tự duyệt bị từ chối"

echo "== 3. PAM giả lập cấp debug-app 90 giây, tự thu hồi"
binding_pam pam-thu-1 debug-app core "duyet1" 90 >/dev/null && ok "broker tạo binding đúng hợp đồng" || hong "broker bị từ chối"
[ "$(kubectl auth can-i create pods/exec -n core --as=oidc:nam)" = yes ] && ok "oidc:nam exec được trong thời hạn" || hong "chưa có quyền exec"
sleep 130
chay_quet pam-quet-rbac quet-thu-1 > "$OUT"/pam-quet-1.log
grep -q '"pam.thu-hoi"' "$OUT"/pam-quet-1.log && ok "CronJob thu hồi binding quá hạn (pam.thu-hoi)" || hong "không thấy pam.thu-hoi"
kubectl -n core get rolebinding pam-thu-1 >/dev/null 2>&1 && hong "binding còn sau hạn" || ok "binding đã bị xoá"
[ "$(kubectl auth can-i create pods/exec -n core --as=oidc:nam)" = no ] && ok "hết quyền exec sau hạn" || hong "vẫn exec được"

echo "== 4. Cửa sau tạo tay ngoài chính sách nhập"
kubectl create clusterrolebinding cua-sau-thu --clusterrole=edit --user=oidc:nam >/dev/null
chay_quet pam-quet-rbac quet-thu-2 > "$OUT"/pam-quet-2.log
grep '"pam.cua-sau-rbac"' "$OUT"/pam-quet-2.log | grep -q cua-sau-thu && ok "quét RBAC báo cua-sau-thu" || hong "quét RBAC không thấy cửa sau"
kubectl delete clusterrolebinding cua-sau-thu >/dev/null

echo "== 5. Quét CSDL"
if [ ${#THEM[@]} -gt 0 ]; then
  P=$(kubectl -n data-pg get pod -l cnpg.io/cluster=pg-core,cnpg.io/instanceRole=primary -o name | head -1)
  kubectl -n data-pg exec -i "$P" -- psql -q -v ON_ERROR_STOP=1 < "$GOC/deploy/charts/pam-integration/files/sql/vai-tro-dba.sql"
  MK=$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')
  kubectl -n data-pg exec "$P" -- psql -q -c "ALTER ROLE pam_quet PASSWORD '$MK'; CREATE ROLE cua_sau_thu LOGIN IN ROLE ro_task;"
  kubectl -n "$NS" create secret generic pg-core-pam-quet --from-literal=username=pam_quet --from-literal="password=$MK" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  unset MK
  kubectl -n data-pg get secret pg-core-ca -o json | python3 -c 'import json,sys; d=json.load(sys.stdin); print(json.dumps({"apiVersion":"v1","kind":"Secret","metadata":{"name":"pg-core-ca","namespace":"pam-kiem-soat"},"data":{"ca.crt":d["data"]["ca.crt"]}}))' | kubectl apply -f - >/dev/null
  chay_quet pam-quet-csdl-pg-core quet-csdl-thu > "$OUT"/pam-quet-3.log
  grep '"pam.thua-ke-dich-vu-csdl"' "$OUT"/pam-quet-3.log | grep -q cua_sau_thu && ok "quét CSDL báo cua_sau_thu" || hong "quét CSDL không thấy vai trò thừa quyền"
  kubectl -n data-pg exec "$P" -- psql -q -c "DROP ROLE cua_sau_thu;"
else
  echo "  [BỎ QUA] chưa có pg-core trên kind hoặc chưa đặt PG_ANH"
fi
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]
