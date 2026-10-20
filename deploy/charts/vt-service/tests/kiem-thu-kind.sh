#!/usr/bin/env bash
# Kiểm thử chart thử vt-service trên cụm kind ba vùng (INF-01, tiêu chí 4):
# pod Ready, rải đều ba vùng, PDB có hiệu lực, NetworkPolicy chặn pod không được phép,
# xoá một node giả lập thì pod xếp lại trên node khác trong ≤ 120 giây.
# Cần cụm từ deploy/environments/kind/tao-cum.sh. Dùng: deploy/charts/vt-service/tests/kiem-thu-kind.sh
set -euo pipefail
cd "$(dirname "$0")"
NS=vt-thu
KIND_DIR=../../../environments/kind
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }

"$KIND_DIR/nap-anh.sh" docker.io/hashicorp/http-echo@sha256:fcb75f691c8b0414d670ae570240cbf95502cc18a9ba57e982ecac589760a186 \
  docker.io/library/alpine:latest >/dev/null
helm dependency update vt-service-example >/dev/null
kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl label namespace "$NS" pod-security.kubernetes.io/enforce=restricted --overwrite >/dev/null
helm upgrade --install echo vt-service-example -n "$NS" -f vt-service-example/values-kind.yaml --wait --timeout 3m >/dev/null
echo "== Sẵn sàng và phân bố"
kubectl -n "$NS" rollout status deploy/echo-service --timeout=120s >/dev/null && ok "3/3 pod Ready dưới Pod Security restricted" || hong "pod chưa Ready"
vung=$(kubectl -n "$NS" get pod -l app.kubernetes.io/name=echo-service -o jsonpath='{range .items[*]}{.spec.nodeName}{"\n"}{end}' \
  | xargs -I{} kubectl get node {} -o jsonpath='{.metadata.labels.topology\.kubernetes\.io/zone}{"\n"}' | sort -u | wc -l)
[ "$vung" = 3 ] && ok "pod rải trên 3 vùng sự cố" || hong "pod chỉ nằm trên $vung vùng"
pdb=$(kubectl -n "$NS" get pdb echo-service -o jsonpath='{.status.disruptionsAllowed}')
[ "$pdb" = 1 ] && ok "PDB minAvailable 2: disruptionsAllowed=1" || hong "PDB disruptionsAllowed=$pdb"

echo "== NetworkPolicy"
chay_client() { # $1 tên pod, $2 nhãn vai trò
  kubectl -n "$NS" run "$1" --image=docker.io/library/alpine:latest --restart=Never --labels="vt.io/vai-tro=$2" \
    --overrides='{"spec":{"securityContext":{"runAsNonRoot":true,"runAsUser":10001,"seccompProfile":{"type":"RuntimeDefault"}},"containers":[{"name":"c","image":"docker.io/library/alpine:latest","command":["sh","-c","wget -qO- -T 5 http://echo-service.'"$NS"'.svc:8080/ && echo THANH_CONG || echo THAT_BAI"],"securityContext":{"allowPrivilegeEscalation":false,"capabilities":{"drop":["ALL"]}}}]}}' >/dev/null
  kubectl -n "$NS" wait --for=jsonpath='{.status.phase}'=Succeeded "pod/$1" --timeout=60s >/dev/null || true
  kubectl -n "$NS" logs "$1" | tail -1; kubectl -n "$NS" delete pod "$1" --wait=false >/dev/null
}
# Chính sách default-deny của namespace (sẽ do cell-base INF-08 tạo) áp tạm ở đây để thử.
kubectl -n "$NS" apply -f - >/dev/null <<'EOF'
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: default-deny-all }
spec: { podSelector: {}, policyTypes: ["Ingress", "Egress"] }
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: client-egress-thu }
spec:
  podSelector: { matchLabels: { vt.io/vai-tro: client } }
  policyTypes: ["Egress"]
  egress:
    - to: [{ namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: kube-system } }, podSelector: { matchLabels: { k8s-app: kube-dns } } }]
      ports: [{ protocol: UDP, port: 53 }, { protocol: TCP, port: 53 }]
    - to: [{ podSelector: { matchLabels: { app.kubernetes.io/name: echo-service } } }]
      ports: [{ protocol: TCP, port: 8080 }]
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: la-egress-thu }
spec:
  podSelector: { matchLabels: { vt.io/vai-tro: la } }
  policyTypes: ["Egress"]
  egress:
    - to: [{ namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: kube-system } }, podSelector: { matchLabels: { k8s-app: kube-dns } } }]
      ports: [{ protocol: UDP, port: 53 }, { protocol: TCP, port: 53 }]
    - to: [{ podSelector: { matchLabels: { app.kubernetes.io/name: echo-service } } }]
      ports: [{ protocol: TCP, port: 8080 }]
EOF
[ "$(chay_client thu-duoc-phep client)" = THANH_CONG ] && ok "pod có nhãn được phép gọi được service" || hong "pod được phép không gọi được"
[ "$(chay_client thu-la la)" = THAT_BAI ] && ok "pod không nằm trong ingressFrom bị NetworkPolicy chặn" || hong "pod lạ vẫn gọi được"

echo "== Mất một node"
nan=$(kubectl -n "$NS" get pod -l app.kubernetes.io/name=echo-service -o jsonpath='{.items[0].spec.nodeName}')
echo "  xoá node $nan (vùng $(kubectl get node "$nan" -o jsonpath='{.metadata.labels.topology\.kubernetes\.io/zone}'))"
bd=$(date +%s)
docker stop "$nan" >/dev/null
kubectl delete node "$nan" --wait=false >/dev/null
for _ in $(seq 60); do
  san=$(kubectl -n "$NS" get deploy echo-service -o jsonpath='{.status.readyReplicas}')
  tren_nan=$(kubectl -n "$NS" get pod -l app.kubernetes.io/name=echo-service --field-selector=status.phase=Running -o jsonpath='{range .items[*]}{.spec.nodeName}{"\n"}{end}' | grep -c "^$nan$" || true)
  [ "${san:-0}" = 3 ] && [ "$tren_nan" = 0 ] && break
  sleep 2
done
tg=$(( $(date +%s) - bd ))
if [ "${san:-0}" = 3 ] && [ "$tren_nan" = 0 ]; then ok "3/3 pod Ready lại trên node khác sau ${tg}s (≤ 120 s)"; else hong "sau ${tg}s mới có ${san:-0}/3 pod"; fi
[ "$tg" -le 120 ] || hong "vượt 120 s"
kubectl -n "$NS" get pod -l app.kubernetes.io/name=echo-service -o wide --no-headers | awk '{print "    "$1, $2, $7}'

echo "== Dọn: khởi động lại node đã dừng"
docker start "$nan" >/dev/null
for _ in $(seq 60); do kubectl get node "$nan" >/dev/null 2>&1 && break; sleep 2; done
echo
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]
