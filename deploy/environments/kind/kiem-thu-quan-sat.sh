#!/usr/bin/env bash
# Kiểm thử quan sát trên kind (INF-14): request qua APISIX → dịch vụ mẫu → Kafka → consumer mẫu tạo một trace đủ chặng
# trên Tempo (B1); log tra được theo trace_id trên Loki (G3); target Prometheus up 100% (B3); chặn Loki 5 phút mà không
# mất dòng log nào (G4 rút gọn); LogQL tìm JWT và dãy 12 chữ số trên log thử trả 0 (G7).
# Cần: cụm từ tao-cum.sh, đã chạy kiểm thử cell-base và api-gateway (Keycloak giả lập, Valkey). CHAN_PHUT đổi thời gian chặn.
set -euo pipefail
cd "$(dirname "$0")"
CH=../../charts
CHAN_PHUT=${CHAN_PHUT:-5}
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
OTEL=ghcr.io/open-telemetry/opentelemetry-collector-releases/opentelemetry-collector-contrib@sha256:886722fe0f37af9d1fe24d29529253ec59fbf263b3b1df4facaf221373e19d23
FB=cr.fluentbit.io/fluent/fluent-bit@sha256:2a5cb41f99b7c5f3386bb34d42ce3b19eb47fbef6d93b64c0bd4afe9e6ec389c
KAFKA=docker.io/apache/kafka@sha256:bff074a5d0051dbc0bbbcd25b045bb1fe84833ec0d3c7c965d1797dd289ec88f
PY=docker.io/library/python@sha256:1b668429b3511ab407d8e00648891631b0b1a4d7e15e3ca70f38ab5b91ad4ab4
WORKERS=$(kind get nodes --name vt-cell-kind | grep -v control-plane | tr '\n' ' ')
KNODE=vt-cell-kind-worker

echo "== Ảnh"
./nap-anh.sh "$OTEL" "$FB" ghcr.io/prometheus-operator/prometheus-operator@sha256:8f132dc8c2e8a5c852e864231fc85bcb8edecea184dfff34c77593f8f454cb06 \
  ghcr.io/prometheus-operator/prometheus-config-reloader@sha256:bb94799c84286eea0aad22d83ba4889264ae9af26a7fb3023b7fae75e541e87e >/dev/null
NAP_NODES="$WORKERS" ./nap-anh.sh docker.io/grafana/tempo:2.9.0 docker.io/grafana/loki:3.5.7 docker.io/vt-local/prometheus:v3.7.2 >/dev/null
NAP_NODES="$KNODE" ./nap-anh.sh "$KAFKA" >/dev/null
(cd mau-otel && [ -f mau-otel ] || CGO_ENABLED=0 go build -o mau-otel . ; docker build -q -t docker.io/vt-local/mau-otel:0.1 . >/dev/null)
NAP_NODES="$WORKERS" ./nap-anh.sh docker.io/vt-local/mau-otel:0.1 >/dev/null

echo "== Cài các chart"
helm upgrade --install cell-base "$CH/cell-base" --set cell=kind --wait >/dev/null
for c in observability-cell observability-mgmt; do helm dependency build "$CH/$c" >/dev/null; done
helm upgrade --install om "$CH/observability-mgmt" -n observability -f "$CH/observability-mgmt/tests/values-kind.yaml" \
  --set tempo.tempo.image.tag=2.9.0 --set loki.loki.image.tag=3.5.7 --wait --timeout 5m >/dev/null && ok "Tempo và Loki Ready" || hong "Tempo/Loki chưa Ready"
helm upgrade --install oc "$CH/observability-cell" -n observability -f "$CH/observability-cell/tests/values-kind.yaml" --wait --timeout 5m >/dev/null \
  && ok "Prometheus Operator, Fluent Bit Ready" || hong "observability-cell chưa Ready"
helm upgrade --install otel "$CH/otel-collector" -n observability -f "$CH/otel-collector/tests/values-kind.yaml" --wait --timeout 4m >/dev/null \
  && ok "OTel agent (DaemonSet) và gateway (3 pod) Ready" || hong "OTel chưa Ready"
kubectl -n observability rollout status statefulset/prometheus-kps-prometheus --timeout=180s >/dev/null && ok "cặp Prometheus Ready" || hong "Prometheus chưa Ready"

echo "== Kafka, dịch vụ mẫu, APISIX gửi span"
kubectl -n data-kafka apply -f - >/dev/null <<YAML
apiVersion: v1
kind: Pod
metadata: { name: kafka-0, labels: { strimzi.io/cluster: kafka-cell, app.kubernetes.io/name: kafka } }
spec:
  nodeName: $KNODE
  securityContext: { runAsNonRoot: true, runAsUser: 1000, fsGroup: 1000, seccompProfile: { type: RuntimeDefault } }
  containers:
    - name: kafka
      image: $KAFKA
      imagePullPolicy: IfNotPresent
      env:
        - { name: KAFKA_NODE_ID, value: "1" }
        - { name: KAFKA_PROCESS_ROLES, value: "broker,controller" }
        - { name: KAFKA_LISTENERS, value: "PLAINTEXT://:9093,CONTROLLER://:9094" }
        - { name: KAFKA_ADVERTISED_LISTENERS, value: "PLAINTEXT://kafka-kafka-bootstrap.data-kafka.svc:9093" }
        - { name: KAFKA_CONTROLLER_LISTENER_NAMES, value: CONTROLLER }
        - { name: KAFKA_LISTENER_SECURITY_PROTOCOL_MAP, value: "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT" }
        - { name: KAFKA_CONTROLLER_QUORUM_VOTERS, value: "1@localhost:9094" }
        - { name: KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR, value: "1" }
        - { name: KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR, value: "1" }
        - { name: KAFKA_TRANSACTION_STATE_LOG_MIN_ISR, value: "1" }
        - { name: KAFKA_LOG_DIRS, value: /var/lib/kafka/data }
        - { name: KAFKA_HEAP_OPTS, value: "-Xmx512m -Xms512m" }
      securityContext: { allowPrivilegeEscalation: false, capabilities: { drop: ["ALL"] } }
      volumeMounts: [{ name: data, mountPath: /var/lib/kafka/data }]
  volumes: [{ name: data, emptyDir: {} }]
---
apiVersion: v1
kind: Service
metadata: { name: kafka-kafka-bootstrap }
spec: { selector: { strimzi.io/cluster: kafka-cell }, ports: [{ port: 9093 }] }
YAML
kubectl -n core delete pod mission-core --ignore-not-found >/dev/null
kubectl -n core apply -f - >/dev/null <<YAML
apiVersion: apps/v1
kind: Deployment
metadata: { name: mission-core }
spec:
  replicas: 1
  selector: { matchLabels: { app.kubernetes.io/name: mission-core } }
  template:
    metadata: { labels: { app.kubernetes.io/name: mission-core } }
    spec:
      securityContext: { runAsNonRoot: true, runAsUser: 10001, seccompProfile: { type: RuntimeDefault } }
      containers:
        - name: app
          image: docker.io/vt-local/mau-otel:0.1
          imagePullPolicy: IfNotPresent
          env:
            - { name: OTLP_ENDPOINT, value: "otel-agent.observability.svc:4317" }
            - { name: KAFKA_BROKERS, value: "kafka-kafka-bootstrap.data-kafka.svc:9093" }
            - { name: CELL, value: kind }
          ports: [{ name: http, containerPort: 8080 }, { name: mgmt, containerPort: 8081 }]
          securityContext: { allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: { drop: ["ALL"] } }
---
apiVersion: v1
kind: Service
metadata: { name: mission-core, labels: { app.kubernetes.io/name: mission-core } }
spec:
  selector: { app.kubernetes.io/name: mission-core }
  ports: [{ name: http, port: 8080 }, { name: mgmt, port: 8081 }]
---
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata: { name: mission-core }
spec:
  selector: { matchLabels: { app.kubernetes.io/name: mission-core } }
  endpoints: [{ port: mgmt, interval: 15s }]
YAML
kubectl -n data-kafka wait --for=condition=Ready pod/kafka-0 --timeout=120s >/dev/null && ok "Kafka Ready" || hong "Kafka chưa Ready"
kubectl -n core rollout status deploy/mission-core --timeout=120s >/dev/null && ok "dịch vụ mẫu Ready" || hong "dịch vụ mẫu chưa Ready"
helm upgrade --install api-gateway "$CH/api-gateway" -f "$CH/api-gateway/tests/values-kind.yaml" --wait --timeout 4m >/dev/null
kubectl -n edge rollout status deploy/apisix --timeout=180s >/dev/null && ok "APISIX gửi span tới OTel agent" || hong "APISIX chưa Ready"

echo "== Pod thử trong observability (gọi Tempo, Loki, Prometheus) và trong edge (gọi APISIX)"
chay_py() { # $1 namespace, $2 tên, $3 nhãn
  kubectl -n "$1" delete pod "$2" --ignore-not-found >/dev/null
  kubectl -n "$1" run "$2" --image="$PY" --restart=Never --labels="$3" --overrides='{"spec":{"securityContext":{"runAsNonRoot":true,"runAsUser":10001,"seccompProfile":{"type":"RuntimeDefault"}},"containers":[{"name":"c","image":"'"$PY"'","imagePullPolicy":"IfNotPresent","command":["sleep","3600"],"securityContext":{"allowPrivilegeEscalation":false,"capabilities":{"drop":["ALL"]}}}]}}' >/dev/null
  kubectl -n "$1" wait --for=condition=Ready "pod/$2" --timeout=60s >/dev/null
}
kubectl -n edge apply -f - >/dev/null <<'YAML'
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: client-thu-egress }
spec:
  podSelector: { matchLabels: { vt.io/vai-tro: client-thu } }
  policyTypes: ["Egress"]
  egress:
    - to: [{ namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: kube-system } }, podSelector: { matchLabels: { k8s-app: kube-dns } } }]
      ports: [{ protocol: UDP, port: 53 }]
    - to: [{ podSelector: { matchLabels: { app.kubernetes.io/name: apisix } } }]
      ports: [{ protocol: TCP, port: 9080 }]
YAML
chay_py edge client-thu vt.io/vai-tro=client-thu
chay_py observability tra-cuu vt.io/vai-tro=tra-cuu
"$CH/api-gateway/tests/gia-lap-keycloak.sh" "$TMP"
kubectl -n edge rollout restart deploy/apisix >/dev/null && kubectl -n edge rollout status deploy/apisix --timeout=3m >/dev/null
TOK=$(cat "$TMP/tok")
qs() { kubectl -n observability exec -i tra-cuu -- python - "$@" < kiem-thu-quan-sat.py; }
goi() { # gọi APISIX từ pod client trong edge; in trace_id do dịch vụ mẫu trả
  kubectl -n edge exec -i client-thu -- python - "$TOK" "$@" <<'PY'
import sys, urllib.request
tok, pii = sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else ""
req = urllib.request.Request("http://apisix-gateway.edge.svc/api/v1/tasks?id=42", headers={
    "Host": "cell.danhgia.vn", "Authorization": "Bearer " + tok, "X-Debug-Trace": "1", "X-Thu-Pii": pii})
print(urllib.request.urlopen(req, timeout=10).read().decode().split('"')[3])
PY
}

echo "== Trace đủ chặng (B1) và log theo trace_id (G3)"
PII="token eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1In0.c2lnbmF0dXJl CCCD 001203004567 sdt 0912345678"
TID=$(goi "$PII") && ok "gọi API qua APISIX, trace_id $TID" || hong "gọi API qua APISIX lỗi"
sleep 25
qs trace "$TID" > "$TMP/o" 2>&1 || true; cat "$TMP/o" | sed 's/^/  /'
dat=$((dat + $(grep -c '^\[ĐẠT\]' "$TMP/o" || true))); truot=$((truot + $(grep -c '^\[HỎNG\]' "$TMP/o" || true)))

echo "== Prometheus (B3)"
qs targets > "$TMP/o" 2>&1 || true; sed 's/^/  /' "$TMP/o"
dat=$((dat + $(grep -c '^\[ĐẠT\]' "$TMP/o" || true))); truot=$((truot + $(grep -c '^\[HỎNG\]' "$TMP/o" || true)))

echo "== Chặn Loki ${CHAN_PHUT} phút trong lúc sinh log đều (G4 rút gọn)"
RUN=chay$RANDOM
kubectl -n core delete pod sinh-log --ignore-not-found >/dev/null
kubectl -n core run sinh-log --image="$PY" --restart=Never --labels=app.kubernetes.io/name=sinh-log --overrides='{"spec":{"securityContext":{"runAsNonRoot":true,"runAsUser":10001,"seccompProfile":{"type":"RuntimeDefault"}},"containers":[{"name":"c","image":"'"$PY"'","imagePullPolicy":"IfNotPresent","command":["python","-u","-c","import json,time,sys\nn=int(sys.argv[1])\nfor i in range(n):\n print(json.dumps({\"message\":\"dong %d\" % i,\"run\":\"'"$RUN"'\",\"level\":\"info\"}))\n time.sleep(0.5)\ntime.sleep(3600)","'"$((CHAN_PHUT * 120 + 120))"'"],"securityContext":{"allowPrivilegeEscalation":false,"capabilities":{"drop":["ALL"]}}}]}}' >/dev/null
sleep 30
kubectl -n observability scale statefulset loki --replicas=0 >/dev/null
kubectl -n observability wait --for=delete pod/loki-0 --timeout=120s >/dev/null || true
echo "  Loki đã dừng lúc $(date +%T), chờ ${CHAN_PHUT} phút"
sleep $((CHAN_PHUT * 60))
kubectl -n observability scale statefulset loki --replicas=1 >/dev/null
kubectl -n observability rollout status statefulset/loki --timeout=180s >/dev/null
echo "  Loki chạy lại lúc $(date +%T); chờ sinh log xong và Fluent Bit gửi bù"
kubectl -n core wait --for=jsonpath='{.status.phase}'=Running pod/sinh-log >/dev/null
sleep $((120 + 30))
qs dem "$RUN" "$((CHAN_PHUT * 120 + 120))" > "$TMP/o" 2>&1 || true; sed 's/^/  /' "$TMP/o"
dat=$((dat + $(grep -c '^\[ĐẠT\]' "$TMP/o" || true))); truot=$((truot + $(grep -c '^\[HỎNG\]' "$TMP/o" || true)))

echo "== Không lộ dữ liệu nhạy cảm trên Loki (G7)"
qs pii > "$TMP/o" 2>&1 || true; sed 's/^/  /' "$TMP/o"
dat=$((dat + $(grep -c '^\[ĐẠT\]' "$TMP/o" || true))); truot=$((truot + $(grep -c '^\[HỎNG\]' "$TMP/o" || true)))

echo "== Dọn"
kubectl -n core delete pod sinh-log --wait=false >/dev/null
kubectl -n edge delete pod client-thu --wait=false >/dev/null; kubectl -n edge delete networkpolicy client-thu-egress >/dev/null
kubectl -n observability delete pod tra-cuu --wait=false >/dev/null
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]
