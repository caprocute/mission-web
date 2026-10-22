#!/usr/bin/env bash
# Cài thử vt-platform lên cụm kind (INF-02, tiêu chí 4): chỉ web-frontend (ảnh thử dựng từ deploy/web) và
# notification-service (ảnh http-echo) bật; kiểm pod Ready, probe đạt, /healthz và cấu hình chạy ghi vào
# /tmp/runtime khi hệ thống tệp gốc chỉ đọc. Cần cụm từ deploy/environments/kind/tao-cum.sh và namespace
# platform của cell-base. Dùng: deploy/charts/vt-platform/ci/kiem-thu-kind.sh
set -euo pipefail
cd "$(dirname "$0")/.."
GOC=$(git rev-parse --show-toplevel)
CLUSTER=${CLUSTER:-vt-cell-kind}
BASE_NGINX=${BASE_NGINX:-nginxinc/nginx-unprivileged:1.27-alpine}
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }

echo "== Dựng và nạp ảnh thử"
docker build -q --build-arg BASE="$BASE_NGINX" -t vt-local/web-frontend-thu:inf02 -f ci/anh-thu-web/Dockerfile "$GOC" >/dev/null
"$GOC/deploy/environments/kind/nap-anh.sh" docker.io/vt-local/web-frontend-thu:inf02 \
  docker.io/hashicorp/http-echo@sha256:fcb75f691c8b0414d670ae570240cbf95502cc18a9ba57e982ecac589760a186 >/dev/null
# Ảnh dựng tại chỗ chưa có digest registry: lấy digest manifest containerd tính khi nạp, gắn thêm tên theo digest.
DG=""
for n in $(kind get nodes --name "$CLUSTER"); do
  DG=$(docker exec "$n" ctr -n k8s.io images ls name==docker.io/vt-local/web-frontend-thu:inf02 | awk 'NR==2{print $3}')
  docker exec "$n" ctr -n k8s.io images tag --force docker.io/vt-local/web-frontend-thu:inf02 "docker.io/vt-local/web-frontend-thu@$DG" >/dev/null
done
[[ "$DG" == sha256:* ]] && ok "ảnh thử web-frontend theo digest $DG" || hong "không lấy được digest ảnh thử"

echo "== Cài vt-platform (web-frontend, notification-service)"
ci/cap-nhat-phu-thuoc.sh
sed "s/ANH_WEB/$DG/" ci/values-kind.yaml > /tmp/vtp-kind.yaml
helm upgrade --install vtp . -n observability -f values-saas.yaml -f /tmp/vtp-kind.yaml --wait --timeout 4m >/dev/null \
  && ok "helm upgrade --install vtp (--wait)" || hong "helm install vtp"
for d in web-frontend notification-service; do
  kubectl -n platform rollout status "deploy/$d" --timeout=120s >/dev/null && \
    ok "$d: $(kubectl -n platform get deploy "$d" -o jsonpath='{.status.readyReplicas}')/$(kubectl -n platform get deploy "$d" -o jsonpath='{.spec.replicas}') pod Ready, probe startup/readiness/liveness đạt" \
    || hong "$d chưa Ready"
done
khoi=$(kubectl -n platform get pod -l app.kubernetes.io/name=web-frontend -o jsonpath='{.items[*].status.containerStatuses[*].restartCount}')
[ -z "$(echo "$khoi" | tr -d ' 0')" ] && ok "web-frontend không khởi động lại (restartCount $khoi)" || hong "web-frontend khởi động lại: $khoi"

echo "== Gọi thử qua port-forward (ảnh APISIX không có curl; port-forward đi qua kubelet nên không phụ thuộc NetworkPolicy)"
kubectl -n platform port-forward svc/web-frontend 18080:8080 >/dev/null 2>&1 & PF1=$!
kubectl -n platform port-forward svc/notification-service 18081:8080 >/dev/null 2>&1 & PF2=$!
trap 'kill $PF1 $PF2 2>/dev/null || true' EXIT
sleep 3
goi() { curl -s -m 5 "$@"; }
[ "$(goi http://127.0.0.1:18080/healthz)" = ok ] && ok "/healthz trả ok" || hong "/healthz"
cfg=$(goi http://127.0.0.1:18080/config.json)
echo "$cfg" | python3 -c 'import json,sys; c=json.load(sys.stdin); assert c["auth"]["enabled"] is True and c["useMockApi"] is False' \
  && ok "config.json sinh lúc chạy ở /tmp/runtime (auth bật, không dùng API giả)" || hong "config.json: $cfg"
goi -o /dev/null -w '%{http_code}' http://127.0.0.1:18080/federation.manifest.json | grep -q 200 \
  && ok "federation.manifest.json phục vụ từ thư mục chạy" || hong "federation.manifest.json"
ro=$(kubectl -n platform get deploy web-frontend -o jsonpath='{.spec.template.spec.containers[0].securityContext.readOnlyRootFilesystem}')
[ "$ro" = true ] && ok "container web-frontend chạy với hệ thống tệp gốc chỉ đọc" || hong "readOnlyRootFilesystem=$ro"
[ "$(goi http://127.0.0.1:18081/)" = ok ] && ok "notification-service (ảnh mẫu) trả lời qua Service" || hong "notification-service"
echo
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]
