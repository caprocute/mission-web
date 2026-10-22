#!/usr/bin/env bash
# Kiểm thử cell-base trên cụm kind (INF-08 tiêu chí 2): cài 13 namespace và NetworkPolicy, rồi thử
# DNS chạy, edge/apisix gọi được core/mission-core, core không gọi được secure, không ra được Internet,
# pod không có nhãn được phép trong edge bị chặn. Cần cụm từ deploy/environments/kind/tao-cum.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
KIND_DIR=../../environments/kind
ECHO=docker.io/hashicorp/http-echo@sha256:fcb75f691c8b0414d670ae570240cbf95502cc18a9ba57e982ecac589760a186
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
SC='"securityContext":{"allowPrivilegeEscalation":false,"capabilities":{"drop":["ALL"]}}'
PSC='"securityContext":{"runAsNonRoot":true,"runAsUser":10001,"seccompProfile":{"type":"RuntimeDefault"}}'

"$KIND_DIR/nap-anh.sh" "$ECHO" docker.io/library/alpine:latest >/dev/null
helm upgrade --install cell-base . --set cell=kind --wait >/dev/null && ok "cài chart cell-base (13 namespace)" || hong "cài chart"
[ "$(kubectl get ns core -o jsonpath='{.metadata.labels.pod-security\.kubernetes\.io/enforce}')" = restricted ] \
  && ok "namespace core gắn Pod Security restricted" || hong "thiếu nhãn Pod Security"

may_chu() { # $1 namespace, $2 tên app
  kubectl -n "$1" run "$2" --image="$ECHO" --restart=Never --labels="app.kubernetes.io/name=$2" \
    --overrides='{"spec":{'"$PSC"',"containers":[{"name":"c","image":"'"$ECHO"'","imagePullPolicy":"IfNotPresent","args":["-listen=:8080","-text=ok"],'"$SC"'}]}}' >/dev/null
  kubectl -n "$1" expose pod "$2" --port=8080 >/dev/null
  kubectl -n "$1" wait --for=condition=Ready "pod/$2" --timeout=60s >/dev/null
}
thu() { # $1 namespace, $2 nhãn app của pod gọi, $3 lệnh sh; in THANH_CONG hoặc THAT_BAI
  local p="thu-$RANDOM"
  kubectl -n "$1" run "$p" --image=docker.io/library/alpine:latest --restart=Never --labels="app.kubernetes.io/name=$2" \
    --overrides='{"spec":{'"$PSC"',"containers":[{"name":"c","image":"docker.io/library/alpine:latest","imagePullPolicy":"IfNotPresent","command":["sh","-c","'"$3"' >/dev/null 2>&1 && echo THANH_CONG || echo THAT_BAI"],'"$SC"'}]}}' >/dev/null
  kubectl -n "$1" wait --for=jsonpath='{.status.phase}'=Succeeded "pod/$p" --timeout=60s >/dev/null || true
  kubectl -n "$1" logs "$p" | tail -1; kubectl -n "$1" delete pod "$p" --wait=false >/dev/null
}
may_chu core mission-core
may_chu secure ledger-service

echo "== Luồng mạng"
[ "$(thu core mission-core 'nslookup kubernetes.default.svc.cluster.local')" = THANH_CONG ] && ok "DNS chạy trong core" || hong "DNS hỏng"
[ "$(thu edge apisix 'wget -qO- -T 5 http://mission-core.core.svc:8080/')" = THANH_CONG ] \
  && ok "edge/apisix gọi được core/mission-core:8080" || hong "apisix không gọi được mission-core"
[ "$(thu edge khac 'wget -qO- -T 5 http://mission-core.core.svc:8080/')" = THAT_BAI ] \
  && ok "pod khác trong edge bị chặn tới mission-core" || hong "pod lạ trong edge vẫn gọi được"
[ "$(thu core mission-core 'wget -qO- -T 5 http://ledger-service.secure.svc:8080/')" = THAT_BAI ] \
  && ok "core không gọi được secure (ADR-09)" || hong "core vẫn gọi được secure"
[ "$(thu core mission-core 'wget -qO- -T 5 http://1.1.1.1/')" = THAT_BAI ] \
  && ok "core không ra được Internet" || hong "core vẫn ra được Internet"

echo "== Dọn"
kubectl -n core delete pod,svc mission-core --wait=false >/dev/null
kubectl -n secure delete pod,svc ledger-service --wait=false >/dev/null
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]
