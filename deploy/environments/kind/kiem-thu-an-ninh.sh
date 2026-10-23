#!/usr/bin/env bash
# Kiểm thử bảo mật nền trên kind (INF-19). CHƯA CHẠY trong INF-19 (máy agent không đủ đĩa cho cụm kind có Falco);
# chạy trên máy có ≥ 20 GB trống sau tao-cum.sh với KIND_CONFIG=cluster-an-ninh.yaml, cell-base, cell-egress,
# observability-cell, otel-collector, Kafka thử (values-kind-kafka.yaml). Các bước:
#   1. cài security-cell với values-kind-an-ninh.yaml, chờ Falco và Falcosidekick sẵn sàng;
#   2. kubectl exec mở shell trong pod của namespace core → Falco ghi "VT shell trong container", có thông điệp ở
#      topic sys.security.runtime.v1, nhật ký kiểm toán API có bản ghi RequestResponse cho pods/exec;
#   3. chạy kube-bench một lần, đọc kube_bench_checks{result="fail"} trên Pushgateway (ghi số FAIL thật);
#   4. người dùng nhóm oidc:platform-ops-dev (view-app) không đọc được Secret, không exec được;
#   5. chạy Job thăm dò cổng ra ở mọi namespace, vt_egress_probe_reachable phải bằng 0.
set -euo pipefail
cd "$(dirname "$0")"
GOC=$(cd ../../.. && pwd)
NS_CAO=platform-privileged
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
kubectl config use-context kind-vt-cell-kind >/dev/null

echo "== 1. Cài security-cell"
helm upgrade --install security-cell "$GOC/deploy/charts/security-cell" -f values-kind-an-ninh.yaml --wait --timeout 10m
kubectl -n "$NS_CAO" rollout status ds/falco --timeout=5m && ok "Falco chạy trên mọi node" || hong "Falco chưa sẵn sàng"

echo "== 2. Shell trong container → Falco → Kafka; nhật ký kiểm toán API"
POD=$(kubectl -n core get pod -o name | head -1)
kubectl -n core exec "$POD" -- sh -c 'echo thu-inf19' >/dev/null 2>&1 || true
sleep 10
kubectl -n "$NS_CAO" logs ds/falco --since=2m | grep -q 'Shell trong container' && ok "Falco ghi shell trong container" || hong "Falco không ghi sự kiện shell"
kubectl -n data-kafka exec kafka-cell-kafka-0 -- /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic sys.security.runtime.v1 --from-beginning --timeout-ms 15000 2>/dev/null | grep -q 'shell' \
  && ok "thông điệp tới sys.security.runtime.v1" || hong "không thấy thông điệp trên sys.security.runtime.v1"
docker exec vt-cell-kind-control-plane grep '"subresource":"exec"' /var/log/kubernetes/audit/audit.log | grep -q '"level":"RequestResponse"' \
  && ok "nhật ký kiểm toán API có RequestResponse cho pods/exec" || hong "thiếu bản ghi kiểm toán pods/exec"

echo "== 3. kube-bench"
for t in master node; do kubectl -n "$NS_CAO" create job "kb-$t-thu" --from="cronjob/kube-bench-$t" >/dev/null; done
kubectl -n "$NS_CAO" wait --for=condition=complete job/kb-master-thu job/kb-node-thu --timeout=10m && ok "kube-bench chạy xong" || hong "kube-bench"
kubectl -n observability port-forward svc/pushgateway 19091:9091 >/dev/null 2>&1 & PF=$!
sleep 3
curl -s localhost:19091/metrics | grep '^kube_bench_checks{.*result="fail"' || true
kill "$PF"

echo "== 4. view-app không đọc Secret"
[ "$(kubectl auth can-i get secrets -n core --as=u --as-group=oidc:platform-ops-dev)" = no ] && ok "view-app không đọc được Secret" || hong "view-app đọc được Secret"
[ "$(kubectl auth can-i create pods/exec -n core --as=u --as-group=oidc:platform-ops-dev)" = no ] && ok "view-app không exec được" || hong "view-app exec được"
[ "$(kubectl auth can-i list pods -n core --as=u --as-group=oidc:platform-ops-dev)" = yes ] && ok "view-app đọc được pod" || hong "view-app không đọc được pod"

echo "== 5. Thăm dò cổng ra"
for ns in core identity platform workers analytics data-pg data-kafka data-search secure secure-data edge; do
  kubectl -n "$ns" create job "tdcr-thu" --from=cronjob/tham-do-cong-ra >/dev/null
done
sleep 60
kubectl -n observability port-forward svc/pushgateway 19091:9091 >/dev/null 2>&1 & PF=$!
sleep 3
ro=$(curl -s localhost:19091/metrics | grep '^vt_egress_probe_reachable' | awk '$2 != 0' | wc -l)
kill "$PF"
[ "$ro" -eq 0 ] && ok "0 luồng ra ngoài danh sách" || hong "$ro luồng ra ngoài danh sách"
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]
