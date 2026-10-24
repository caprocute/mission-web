#!/usr/bin/env bash
# Kiểm thử bảo mật nền trên kind (INF-19; TL-04 mục 9.6, 9.7, SEC-27, SEC-29, SEC-36, SEC-43, SEC-50) trên cụm gọn
# (KIND_CONFIG=cluster-gon.yaml KHONG_CILIUM=1 ./tao-cum.sh, có nhật ký kiểm toán API) với cell-base đã cài:
#   1. Kafka của cell thu nhỏ (chart kafka, values-kind-kafka.yaml, không Connect, không Apicurio) trong data-kafka;
#      otel-siem của chart otel-collector (chứng thư OTLP từ ClusterIssuer openbao-pki-cell, trên kind là CA tự ký);
#      Fluent Bit của observability-cell chỉ luồng kiểm toán (values-kind-fluent-bit-audit.yaml); security-cell
#      (values-kind-an-ninh.yaml), chờ Falco (modern_ebpf) và Falcosidekick sẵn sàng.
#   2. kubectl exec mở shell trong pod của namespace core → Falco ghi quy tắc "VT shell trong container…" →
#      Falcosidekick → OTLP mTLS → otel-siem → topic sys.security.runtime.v1 → chuẩn hoá ECS → sys.siem.v1.
#   3. Nhật ký kiểm toán API có bản ghi RequestResponse cho pods/exec đó; Fluent Bit đưa nó vào sys.audit.v1 và
#      otel-siem chuẩn hoá sang sys.siem.v1.
#   4. kube-bench chạy một lần trên master và node, số FAIL thật đọc trên Pushgateway của security-cell.
#   5. Người dùng nhóm oidc:platform-ops-dev (view-app) không đọc được Secret, không exec được; đọc được pod.
#   6. Job thăm dò cổng ra ở mọi namespace vùng ứng dụng, dữ liệu, bảo mật, biên: vt_egress_probe_reachable bằng 0.
# Cần docker, helm, kubectl, curl, jq, openssl; ảnh kéo về máy (docker pull) rồi nạp vào node bằng nap-anh.sh.
# Dùng: deploy/environments/kind/kiem-thu-an-ninh.sh   (GIU_LAI=1 giữ lại các bản cài sau khi chạy)
# ok luôn trả 0, nên "A && ok || hong" đúng nghĩa if-then-else.
# shellcheck disable=SC2015,SC2016
set -euo pipefail
cd "$(dirname "$0")"
GOC=$(cd ../../.. && pwd)
CH=$GOC/deploy/charts
NS_CAO=platform-privileged
CLUSTER=${CLUSTER:-vt-cell-kind}
STRIMZI_CHART=${STRIMZI_CHART:-oci://quay.io/strimzi-helm/strimzi-kafka-operator}
STRIMZI_VER=1.2.0
KAFKA_ANH=quay.io/strimzi/kafka@sha256:fef34b5438e8556cc08c01f3e254e47346f061b53a4e38d4289853777e0ea7f1
PROM_CRD=https://github.com/prometheus-operator/prometheus-operator/releases/download/v0.86.1/stripped-down-crds.yaml
ANH=(docker.io/falcosecurity/falco@sha256:69e957f91b3a6beb5c82e2afae33ec3e4479dab2d4421475d288d0cd113cedf6
  docker.io/falcosecurity/falcosidekick@sha256:fc0b4b7e4741011018dbd8e1f5311363902e7ca26a601c59efee4ca400b61000
  docker.io/aquasec/kube-bench@sha256:d079de1fa7f757b367c39ee12eafc0e8729e94962df2c29808f4b602a615c142
  docker.io/library/busybox@sha256:bdf57e528e45e4433820e045b29b4597825a1c9e38353532d90a01445013f82e
  docker.io/prom/pushgateway@sha256:03738d278e082ee9821df730c741b3b465c251fc2b68a85883def301a55a6215
  ghcr.io/open-telemetry/opentelemetry-collector-releases/opentelemetry-collector-contrib@sha256:886722fe0f37af9d1fe24d29529253ec59fbf263b3b1df4facaf221373e19d23
  cr.fluentbit.io/fluent/fluent-bit:5.1.3@sha256:2a5cb41f99b7c5f3386bb34d42ce3b19eb47fbef6d93b64c0bd4afe9e6ec389c)
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
W=$(mktemp -d)
don() {
  kubectl -n core delete pod thu-shell --wait=false >/dev/null 2>&1 || true
  kubectl -n data-kafka delete pod thu-client --wait=false >/dev/null 2>&1 || true
  if [ "${GIU_LAI:-0}" != 1 ]; then
    helm uninstall security-cell >/dev/null 2>&1 || true
    kubectl delete -f "$W/siem.yaml" -f "$W/fluent-bit.yaml" --ignore-not-found >/dev/null 2>&1 || true
    kubectl -n data-kafka delete kafkatopic --all --timeout=300s >/dev/null 2>&1 || true
    helm uninstall kafka -n data-kafka --wait >/dev/null 2>&1 || true
    helm uninstall strimzi -n strimzi >/dev/null 2>&1 || true
  fi
  rm -rf "${W:?}"
}
trap don EXIT
cho() { # <số lần> <lệnh...>: thử lại mỗi 5 giây
  local n=$1; shift
  for _ in $(seq "$n"); do "$@" >/dev/null 2>&1 && return 0; sleep 5; done; return 1
}
san_sang() { [ "$(kubectl -n "$1" get "$2" "$3" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}')" = True ]; }
kubectl config use-context "kind-$CLUSTER" >/dev/null

echo "== 1. Chuẩn bị: ảnh, CRD giám sát, CA tự ký thay PKI OpenBao, Kafka của cell"
for a in "${ANH[@]}" $KAFKA_ANH quay.io/strimzi/operator:$STRIMZI_VER; do docker image inspect "$a" >/dev/null 2>&1 || docker pull -q "$a" >/dev/null; done
./nap-anh.sh "${ANH[@]}" >/dev/null
NAP_NODES=$CLUSTER-worker ./nap-anh.sh $KAFKA_ANH quay.io/strimzi/operator:$STRIMZI_VER >/dev/null
# CRD PodMonitor, ServiceMonitor, PrometheusRule (bản rút gọn của Prometheus Operator) để chart cài được; không có Prometheus.
curl -sSfL "$PROM_CRD" | kubectl apply --server-side -f - >/dev/null
kubectl apply -f - >/dev/null <<'EOF'
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata: { name: vt-kind-tu-ky }
spec: { selfSigned: {} }
---
apiVersion: cert-manager.io/v1
kind: Certificate
metadata: { name: vt-kind-pki-ca, namespace: cert-manager }
spec:
  isCA: true
  commonName: vt-kind-pki-cell
  secretName: vt-kind-pki-ca
  privateKey: { algorithm: ECDSA, size: 256 }
  issuerRef: { name: vt-kind-tu-ky, kind: ClusterIssuer }
---
# Cùng tên ClusterIssuer của PKI nội bộ cell (INF-13) mà security-cell và otel-collector tham chiếu.
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata: { name: openbao-pki-cell }
spec: { ca: { secretName: vt-kind-pki-ca } }
EOF
cho 24 san_sang "" clusterissuer openbao-pki-cell || { hong "ClusterIssuer openbao-pki-cell chưa sẵn sàng"; exit 1; }
kubectl create ns strimzi --dry-run=client -o yaml | kubectl apply -f - >/dev/null
helm upgrade --install strimzi "$STRIMZI_CHART" --version $STRIMZI_VER -n strimzi \
  --set 'watchNamespaces={data-kafka}' --set resources.requests.cpu=100m --wait --timeout 5m >/dev/null
# Pod của cụm Kafka gọi kube-apiserver (cụm thật: CiliumNetworkPolicy của chart; kindnet chỉ có NetworkPolicy chuẩn).
kubectl apply -f - >/dev/null <<'EOF'
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: kind-ra-kube-apiserver, namespace: data-kafka }
spec:
  podSelector: {}
  policyTypes: [Egress]
  egress:
    - to: [{ ipBlock: { cidr: 172.18.0.0/16 } }, { ipBlock: { cidr: 10.96.0.1/32 } }]
      ports: [{ port: 6443, protocol: TCP }, { port: 443, protocol: TCP }]
EOF
helm upgrade --install kafka "$CH/vt-platform/ha-tang/kafka" -n data-kafka -f values-kind-kafka.yaml \
  --set connect.enabled=false --set apicurio.enabled=false --set cilium=false >/dev/null
cho 120 san_sang data-kafka kafka kafka-cell || { hong "Kafka chưa sẵn sàng"; exit 1; }
for u in siem-forwarder audit-writer; do cho 60 san_sang data-kafka kafkauser $u || { hong "KafkaUser $u"; exit 1; }; done
ok "Kafka của cell sẵn sàng, KafkaUser siem-forwarder và audit-writer có chứng thư"
# Secret mTLS cho otel-siem (siem-forwarder) và Fluent Bit (audit-writer): ca.crt, user.crt, user.key (cụm thật: ESO).
CA=$(kubectl -n data-kafka get secret kafka-cell-cluster-ca-cert -o jsonpath='{.data.ca\.crt}')
for x in siem-forwarder:siem-forwarder-kafka audit-writer:fluent-bit-kafka; do
  kubectl -n data-kafka get secret "${x%%:*}" -o json | jq --arg t "${x#*:}" --arg ca "$CA" \
    '{apiVersion, kind, type: "Opaque", metadata: {name: $t, namespace: "observability"}, data: {"ca.crt": $ca, "user.crt": .data["user.crt"], "user.key": .data["user.key"]}}' \
    | kubectl apply -f - >/dev/null
done
helm template otel "$CH/otel-collector" -n observability -f "$CH/otel-collector/tests/values-kind.yaml" \
  --set siem.openbao.enabled=false --set siem.pam.enabled=false --show-only templates/siem.yaml > "$W/siem.yaml"
kubectl apply -f "$W/siem.yaml" >/dev/null
helm template oc "$CH/observability-cell" -n observability -f values-kind-fluent-bit-audit.yaml > "$W/oc.yaml"
python3 - "$W" <<'PY'
import sys, yaml
w = sys.argv[1]
ds = [d for d in yaml.safe_load_all(open(f"{w}/oc.yaml")) if d]
giu = [d for d in ds if d.get("metadata", {}).get("labels", {}).get("app.kubernetes.io/name") == "fluent-bit"
       or d.get("metadata", {}).get("name") == "fluent-bit-ra-kafka"]
for d in giu:
    if d["kind"] not in ("ClusterRole", "ClusterRoleBinding"):
        d["metadata"]["namespace"] = "observability"
yaml.safe_dump_all([d for d in giu if d["kind"] != "ServiceMonitor"], open(f"{w}/fluent-bit.yaml", "w"))
PY
kubectl apply -f "$W/fluent-bit.yaml" >/dev/null
# libpman của Falco nâng RLIMIT_MEMLOCK lên vô hạn (cần CAP_SYS_RESOURCE); máy chạy kind trong sandbox có giới hạn
# cứng 8 MiB và không có CAP_SYS_RESOURCE ở tập giới hạn, nên Falco không thể chạy dù nhân có BTF. Khi đó tắt DaemonSet
# Falco, kiểm quy tắc bằng falco --validate và gửi một cảnh báo đúng định dạng Falco vào Falcosidekick để đi hết luồng.
FALCO=${FALCO:-$([ "$(docker exec "$CLUSTER-worker" sh -c 'ulimit -Hl')" = unlimited ] && echo 1 || echo 0)}
# Lần chạy trước bị ngắt giữa chừng để lại bản phát hành pending-install, helm upgrade sẽ từ chối.
if helm status security-cell 2>/dev/null | grep -q '^STATUS: pending'; then helm uninstall security-cell --no-hooks >/dev/null; fi
helm upgrade --install security-cell "$CH/security-cell" -f values-kind-an-ninh.yaml \
  --set falco.enabled="$([ "$FALCO" = 1 ] && echo true || echo false)" --wait --timeout 10m >/dev/null
if [ "$FALCO" = 1 ]; then
  if kubectl -n "$NS_CAO" rollout status ds/falco --timeout=5m >/dev/null; then ok "Falco (modern_ebpf, không privileged) chạy trên mọi node"
  else hong "Falco chưa sẵn sàng"; kubectl -n "$NS_CAO" logs ds/falco --tail=20 || true; exit 1; fi
else
  echo "  (bỏ DaemonSet Falco: giới hạn cứng RLIMIT_MEMLOCK của node là $(docker exec "$CLUSTER-worker" sh -c 'ulimit -Hl') KiB, không có CAP_SYS_RESOURCE)"
  helm template security-cell "$CH/security-cell" -f values-kind-an-ninh.yaml --show-only templates/falco.yaml \
    | python3 -c 'import sys, yaml
for d in yaml.safe_load_all(sys.stdin):
    if d and d["kind"] == "ConfigMap" and "vt-rules.yaml" in d.get("data", {}): open(sys.argv[1], "w").write(d["data"]["vt-rules.yaml"])' "$W/vt-rules.yaml"
  if kq=$(docker run --rm -v "$W/vt-rules.yaml:/r/vt-rules.yaml:ro" "${ANH[0]}" falco -V /etc/falco/falco_rules.yaml -V /r/vt-rules.yaml 2>&1) \
    && [[ "$kq" == *"vt-rules.yaml: Ok"* ]]; then ok "quy tắc VT của chart hợp lệ với Falco của chart (falco --validate cùng quy tắc mặc định)"
  else hong "falco --validate báo lỗi quy tắc"; echo "$kq" | tail -5; fi
fi
kubectl -n "$NS_CAO" rollout status deploy/falcosidekick --timeout=3m >/dev/null && ok "Falcosidekick sẵn sàng (chứng thư OTLP từ openbao-pki-cell)" || hong "Falcosidekick chưa sẵn sàng"
kubectl -n observability rollout status deploy/otel-siem --timeout=3m >/dev/null && ok "otel-siem sẵn sàng (mTLS tới Kafka bằng siem-forwarder)" || hong "otel-siem chưa sẵn sàng"
kubectl -n observability rollout status ds/oc-fluent-bit --timeout=3m >/dev/null && ok "Fluent Bit (luồng kiểm toán) chạy" || hong "Fluent Bit chưa chạy"

echo "== 2. Shell trong container → Falco → sys.security.runtime.v1 → sys.siem.v1"
kubectl -n core apply -f - >/dev/null <<EOF
apiVersion: v1
kind: Pod
metadata: { name: thu-shell, namespace: core, labels: { app.kubernetes.io/name: thu-shell } }
spec:
  securityContext: { runAsNonRoot: true, runAsUser: 65532, seccompProfile: { type: RuntimeDefault } }
  containers:
    - name: c
      image: ${ANH[3]}
      command: [sleep, "3600"]
      securityContext: { allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: { drop: [ALL] } }
EOF
kubectl -n core wait pod/thu-shell --for=condition=Ready --timeout=120s >/dev/null
DAU=$(date -u +%s)
kubectl -n core exec thu-shell -- sh -c 'echo thu-inf19' >/dev/null
if [ "$FALCO" = 1 ]; then
  co_falco() { local l; l=$(kubectl -n "$NS_CAO" logs ds/falco --all-containers --since=5m 2>/dev/null); [[ "$l" == *"Shell trong container"*"pod=thu-shell"* ]]; }
  if cho 12 co_falco; then ok "Falco ghi quy tắc VT shell trong container cho core/thu-shell"
  else hong "Falco không ghi sự kiện shell"; kubectl -n "$NS_CAO" logs ds/falco --since=5m --tail=10 || true; fi
else
  # Cảnh báo đúng định dạng JSON Falco gửi (http_output) cho quy tắc VT shell, gửi vào Falcosidekick tại chỗ.
  NOW=$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)
  BAO=$(printf '{"uuid":"%s","output":"%s Notice Shell trong container (ns=core pod=thu-shell container=c cmd=sh -c echo thu-inf19 user=65532)","priority":"Notice","rule":"VT shell trong container vùng ứng dụng hoặc dữ liệu","time":"%s","source":"syscall","hostname":"%s-worker","tags":["container","shell","vt"],"output_fields":{"k8s.ns.name":"core","k8s.pod.name":"thu-shell","container.name":"c","proc.cmdline":"sh -c echo thu-inf19","user.uid":65532,"evt.time":"%s"}}' \
    "$(cat /proc/sys/kernel/random/uuid)" "$NOW" "$NOW" "$CLUSTER" "$NOW")
  if kubectl -n "$NS_CAO" exec deploy/falcosidekick -- wget -qO- --header 'Content-Type: application/json' --post-data "$BAO" http://127.0.0.1:2801/ >/dev/null 2>&1
  then ok "Falcosidekick nhận cảnh báo định dạng Falco (thay cho Falco không chạy được trên máy này)"
  else hong "Falcosidekick không nhận cảnh báo"; fi
fi
# Pod client đọc Kafka bằng siem-forwarder (được đọc sys.security.runtime.v1, sys.siem.v1) và audit-archiver (sys.audit.v1).
kubectl apply -f - >/dev/null <<EOF
apiVersion: v1
kind: Pod
metadata: { name: thu-client, namespace: data-kafka, labels: { strimzi.io/cluster: kafka-cell } }
spec:
  securityContext: { runAsNonRoot: true, runAsUser: 1001, seccompProfile: { type: RuntimeDefault } }
  containers:
    - name: c
      image: $KAFKA_ANH
      command: [sleep, "3600"]
      env: [{ name: KAFKA_HEAP_OPTS, value: "-Xmx256m" }]
      securityContext: { allowPrivilegeEscalation: false, capabilities: { drop: [ALL] } }
      volumeMounts: [{ name: ca, mountPath: /ca }, { name: siem, mountPath: /u/siem }, { name: aa, mountPath: /u/aa }]
  volumes:
    - { name: ca, secret: { secretName: kafka-cell-cluster-ca-cert } }
    - { name: siem, secret: { secretName: siem-forwarder } }
    - { name: aa, secret: { secretName: audit-archiver } }
EOF
kubectl -n data-kafka wait pod/thu-client --for=condition=Ready --timeout=180s >/dev/null
kubectl -n data-kafka exec thu-client -- sh -c '
for u in siem aa; do
  printf "security.protocol=SSL\nssl.truststore.type=PKCS12\nssl.truststore.location=/ca/ca.p12\nssl.truststore.password=%s\nssl.keystore.type=PKCS12\nssl.keystore.location=/u/%s/user.p12\nssl.keystore.password=%s\n" \
    "$(cat /ca/ca.password)" "$u" "$(cat /u/$u/user.password)" > /tmp/$u.properties
done'
doc() { # <topic> <người dùng> <nhóm>
  kubectl -n data-kafka exec thu-client -- /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka-cell-kafka-bootstrap:9093 \
    --consumer.config "/tmp/$2.properties" --group "$3" --topic "$1" --from-beginning --timeout-ms 20000 2>/dev/null || true
}
co_tin() { local l; l=$(doc "$1" "$2" "$3-$RANDOM" | grep -F -- "$4" || true); [[ "$l" == *"$5"* ]]; }
if cho 12 co_tin sys.security.runtime.v1 siem siem-forwarder 'thu-shell' 'Shell trong container'; then
  ok "cảnh báo Falco tới sys.security.runtime.v1 (Falcosidekick → OTLP mTLS → otel-siem)"
else hong "không thấy cảnh báo Falco trên sys.security.runtime.v1"; kubectl -n "$NS_CAO" logs deploy/falcosidekick --tail=10 || true; fi
if cho 12 co_tin sys.siem.v1 siem siem-forwarder 'thu-shell' 'falco.alert'; then ok "sự kiện Falco chuẩn hoá ECS trên sys.siem.v1 (event.module falco)"
else hong "không thấy sự kiện Falco trên sys.siem.v1"; kubectl -n observability logs deploy/otel-siem --tail=10 || true; fi

echo "== 3. Nhật ký kiểm toán API, sys.audit.v1"
# Đếm trong node, không dùng grep -q cuối ống: với pipefail, grep trước nhận SIGPIPE và phép kiểm báo sai là thiếu.
co_audit() {
  local n
  n=$(docker exec "$CLUSTER-control-plane" sh -c "grep -F 'namespaces/core/pods/thu-shell/exec' /var/log/kubernetes/audit/audit.log \
    | grep -c '\"level\":\"RequestResponse\"'" || true)
  [ "${n:-0}" -gt 0 ]
}
cho 6 co_audit && ok "nhật ký kiểm toán API có RequestResponse cho pods/exec vào core/thu-shell" || hong "thiếu bản ghi kiểm toán pods/exec"
if cho 12 co_tin sys.audit.v1 aa audit-archiver '"subresource":"exec"' 'thu-shell'; then ok "Fluent Bit đưa bản ghi kiểm toán pods/exec vào sys.audit.v1 (mTLS bằng audit-writer)"
else hong "không thấy bản ghi pods/exec trên sys.audit.v1"; kubectl -n observability logs ds/oc-fluent-bit --tail=10 || true; fi
if cho 12 co_tin sys.siem.v1 siem siem-forwarder 'thu-shell' 'kubernetes.audit'; then ok "bản ghi kiểm toán pods/exec chuẩn hoá ECS trên sys.siem.v1"
else hong "không thấy bản ghi kiểm toán trên sys.siem.v1"; fi
echo "  (thời gian từ exec tới khi kiểm xong: $(( $(date -u +%s) - DAU )) s)"

echo "== 4. kube-bench"
kubectl -n "$NS_CAO" delete job kb-master-thu kb-node-thu --ignore-not-found >/dev/null
for t in master node; do kubectl -n "$NS_CAO" create job "kb-$t-thu" --from="cronjob/kube-bench-$t" >/dev/null; done
if kubectl -n "$NS_CAO" wait --for=condition=complete job/kb-master-thu job/kb-node-thu --timeout=10m >/dev/null; then ok "kube-bench master và node chạy xong"
else hong "kube-bench chưa xong"; fi
pgw() { kubectl -n observability exec deploy/pushgateway -- wget -qO- http://localhost:9091/metrics 2>/dev/null; }
pgw | grep '^kube_bench_checks{' | sed 's/^/    /' || true
[[ "$(pgw)" == *'kube_bench_checks{'*'result="fail"'* ]] && ok "kết quả kube-bench có trên Pushgateway (số FAIL thật ở trên)" || hong "không thấy kube_bench_checks trên Pushgateway"

echo "== 5. view-app"
ca_i() { kubectl auth can-i "$@" -n core --as=u --as-group=oidc:platform-ops-dev; }
[ "$(ca_i get secrets || true)" = no ] && ok "view-app không đọc được Secret" || hong "view-app đọc được Secret"
[ "$(ca_i create pods/exec || true)" = no ] && ok "view-app không exec được" || hong "view-app exec được"
[ "$(ca_i list pods || true)" = yes ] && ok "view-app đọc được pod" || hong "view-app không đọc được pod"

echo "== 6. Thăm dò cổng ra"
mapfile -t NSS < <(kubectl get cronjob -A -o jsonpath='{range .items[?(@.metadata.name=="tham-do-cong-ra")]}{.metadata.namespace}{"\n"}{end}')
for ns in "${NSS[@]}"; do
  kubectl -n "$ns" delete job tdcr-thu --ignore-not-found >/dev/null
  kubectl -n "$ns" create job tdcr-thu --from=cronjob/tham-do-cong-ra >/dev/null
done
for ns in "${NSS[@]}"; do kubectl -n "$ns" wait --for=condition=complete job/tdcr-thu --timeout=180s >/dev/null || hong "thăm dò ở $ns chưa xong"; done
so=$(pgw | grep -c '^vt_egress_probe_reachable{' || true)
ro=$(pgw | grep '^vt_egress_probe_reachable{' | awk '$2 != 0' | wc -l)
[ "$so" -gt 0 ] && [ "$ro" -eq 0 ] && ok "${#NSS[@]} namespace, $so phép thử: 0 luồng ra ngoài danh sách" || hong "$ro/$so luồng ra ngoài danh sách"
for ns in "${NSS[@]}"; do kubectl -n "$ns" delete job tdcr-thu --ignore-not-found >/dev/null; done

echo
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]
