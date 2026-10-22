#!/usr/bin/env bash
# Kiểm thử co giãn tự động trên kind (INF-16; TL-04 chương 6, OPS-06):
#   - KEDA 2.21 cài từ chart autoscaling-cell; một broker Kafka KRaft thu nhỏ (ảnh Kafka 4.3.1 của Strimzi, không
#     TLS) đóng vai cụm Kafka của cell.
#   - Worker thử dựng bằng thư viện vt-service với autoscaling.keda.kafka: đẩy 3.000 bản ghi (độ trễ vượt ngưỡng
#     500) thì tăng từ 1 lên 4 pod (bằng số phân vùng), dời offset nhóm tiêu thụ về cuối thì giảm về 1 sau
#     cooldownPeriod và cửa sổ ổn định (rút ngắn cho kiểm thử).
#   - Cửa sổ tăng trước theo kỳ của cell (global.cuaSoTangTruoc) đặt vào giờ hiện tại nâng số pod tối thiểu của
#     workload thứ hai lên 3; webhook KEDA từ chối ScaledObject thứ hai nhắm cùng workload.
#   - Pod giữ chỗ (PriorityClass -10) chiếm phần CPU còn trống của node; pod thật cần chỗ thì trục xuất nó, pod
#     giữ chỗ chuyển Pending (tín hiệu cho Cluster Autoscaler thêm node).
# Phần Cluster Autoscaler thêm node thật cần Cluster API provider Docker (CAPD), không kiểm ở đây.
# Cần cụm kind dựng bằng tao-cum.sh, docker, helm, kubectl. Dùng: deploy/environments/kind/kiem-thu-co-gian.sh
set -euo pipefail
cd "$(dirname "$0")"
GOC=$(cd ../../.. && pwd)
CHART=$GOC/deploy/charts/autoscaling-cell
VD=$GOC/deploy/charts/vt-service/tests/vt-service-example
CLUSTER=${CLUSTER:-vt-cell-kind}
NODE=$CLUSTER-worker
NS=vt-co-gian
TOPIC=product.evidence.accepted.v1
KAFKA_ANH=quay.io/strimzi/kafka@sha256:fef34b5438e8556cc08c01f3e254e47346f061b53a4e38d4289853777e0ea7f1
ECHO_ANH=docker.io/hashicorp/http-echo@sha256:fcb75f691c8b0414d670ae570240cbf95502cc18a9ba57e982ecac589760a186
PAUSE_ANH=registry.k8s.io/pause@sha256:ee6521f290b2168b6e0935a181d4cff9be1ac3f505666ef0e3c98fae8199917a
KEDA_ANH=(ghcr.io/kedacore/keda@sha256:81fe6547ce8d1cc29273f887b76507e9b853e5bc1f875ec23fe60061a38ed809
  ghcr.io/kedacore/keda-metrics-apiserver@sha256:255375037fe592732eeb554743aae360c2da88cf0b9b1258d0a987ef885f0881
  ghcr.io/kedacore/keda-admission-webhooks@sha256:e1969628cca6123e32eea13c47c75817091af2657fa68d9d0d58a8620d1b75b1)
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
W=$(mktemp -d)
KHAC=$(kind get nodes --name "$CLUSTER" | grep worker | grep -v "^$NODE$" | tr '\n' ' ')
don() {
  for n in $KHAC; do kubectl uncordon "$n" >/dev/null 2>&1 || true; done
  kubectl label node "$NODE" vt.io/pool- >/dev/null 2>&1 || true
  if [ "${GIU_LAI:-0}" != 1 ]; then
    kubectl delete ns "$NS" --wait=false >/dev/null 2>&1 || true
    helm uninstall co-gian -n keda >/dev/null 2>&1 || true
    kubectl delete priorityclass capacity-reservation >/dev/null 2>&1 || true
  fi
  rm -rf "${W:?}"
}
trap don EXIT
# so_pod <deployment>: số replica mong muốn hiện tại.
so_pod() { kubectl -n "$NS" get deploy "$1" -o jsonpath='{.spec.replicas}'; }
# cho_pod <deployment> <số> <giây>: chờ tới khi số replica mong muốn bằng <số>.
cho_pod() { local _; for _ in $(seq "$(( $3 / 5 ))"); do [ "$(so_pod "$1")" = "$2" ] && return 0; sleep 5; done; return 1; }
kafka() { kubectl -n "$NS" exec kafka-thu-0 -- "$@"; }

echo "== Chuẩn bị: ảnh trên $NODE, node khác tạm cordon"
NAP_NODES=$NODE ./nap-anh.sh "${KEDA_ANH[@]}" "$KAFKA_ANH" "$ECHO_ANH" "$PAUSE_ANH" >/dev/null
for n in $KHAC; do kubectl cordon "$n" >/dev/null; done
kubectl label node "$NODE" vt.io/pool=app-general --overwrite >/dev/null
helm dependency build "$CHART" >/dev/null 2>&1 || helm dependency update "$CHART" >/dev/null
helm dependency update "$VD" >/dev/null

echo "== KEDA từ chart autoscaling-cell"
if helm upgrade --install co-gian "$CHART" -n keda --create-namespace -f values-kind-co-gian.yaml \
     --set giuCho.enabled=false --wait --timeout 5m >/dev/null; then
  ok "KEDA operator, metrics apiserver, admission webhook sẵn sàng; APIService external.metrics.k8s.io: $(kubectl get apiservice v1beta1.external.metrics.k8s.io -o jsonpath='{.status.conditions[0].status}')"
else hong "cài KEDA"; kubectl -n keda get pods; exit 1; fi

echo "== Broker Kafka thu nhỏ trong $NS"
kubectl create ns "$NS" >/dev/null 2>&1 || true
kubectl apply -f - >/dev/null <<YAML
apiVersion: v1
kind: Service
metadata: { name: kafka-thu, namespace: $NS }
spec:
  clusterIP: None
  selector: { app: kafka-thu }
  ports: [{ name: kafka, port: 9092 }]
---
apiVersion: apps/v1
kind: StatefulSet
metadata: { name: kafka-thu, namespace: $NS }
spec:
  serviceName: kafka-thu
  replicas: 1
  selector: { matchLabels: { app: kafka-thu } }
  template:
    metadata: { labels: { app: kafka-thu } }
    spec:
      securityContext: { runAsNonRoot: true, runAsUser: 1001, seccompProfile: { type: RuntimeDefault } }
      containers:
        - name: kafka
          image: $KAFKA_ANH
          imagePullPolicy: IfNotPresent
          env: [{ name: KAFKA_HEAP_OPTS, value: "-Xms256m -Xmx512m" }]
          command: ["bash", "-c"]
          args:
            - |
              cat > /tmp/kraft.properties <<EOP
              process.roles=broker,controller
              node.id=1
              listeners=PLAINTEXT://:9092,CONTROLLER://:9093
              advertised.listeners=PLAINTEXT://kafka-thu-0.kafka-thu.$NS.svc:9092
              controller.listener.names=CONTROLLER
              listener.security.protocol.map=PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
              controller.quorum.bootstrap.servers=localhost:9093
              log.dirs=/tmp/kraft-logs
              num.partitions=4
              offsets.topic.replication.factor=1
              transaction.state.log.replication.factor=1
              transaction.state.log.min.isr=1
              EOP
              /opt/kafka/bin/kafka-storage.sh format --standalone -t "\$(/opt/kafka/bin/kafka-storage.sh random-uuid)" -c /tmp/kraft.properties >/dev/null
              exec /opt/kafka/bin/kafka-server-start.sh /tmp/kraft.properties
          readinessProbe: { tcpSocket: { port: 9092 }, periodSeconds: 5 }
          resources:
            requests: { cpu: 200m, memory: 768Mi }
            limits: { cpu: "1", memory: 768Mi }
          securityContext: { allowPrivilegeEscalation: false, capabilities: { drop: ["ALL"] } }
          volumeMounts: [{ name: tmp, mountPath: /tmp }]
      volumes: [{ name: tmp, emptyDir: {} }]
YAML
if kubectl -n "$NS" rollout status sts/kafka-thu --timeout=300s >/dev/null \
   && kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic "$TOPIC" --partitions 4 >/dev/null; then
  ok "broker KRaft một node, topic $TOPIC 4 phân vùng"
else hong "broker Kafka"; kubectl -n "$NS" logs kafka-thu-0 --tail=20 || true; exit 1; fi

# Values chung của workload thử (ảnh http-echo, không mesh, không giám sát).
cat > "$W/chung.yaml" <<YAML
global: { cell: kind, sizing: M }
namespace: $NS
runtime: custom
image: { repository: docker.io/hashicorp/http-echo, digest: sha256:fcb75f691c8b0414d670ae570240cbf95502cc18a9ba57e982ecac589760a186 }
args: ["-listen=:8080", "-text=ok"]
ports: { http: 8080, mgmt: 8080 }
probes:
  startup: { path: /health, port: http }
  liveness: { path: /health, port: http }
  readiness: { path: /health, port: http }
resources: { requests: { cpu: 10m, memory: 16Mi }, limits: { cpu: 50m, memory: 16Mi } }
pdb: { maxUnavailable: 1, minAvailable: "" }
serviceMonitor: { enabled: false }
networkPolicy: { enabled: false }
authorizationPolicy: { enabled: false }
autoscaling:
  hpa: { enabled: false }
YAML

echo "== KEDA theo độ trễ consumer Kafka (TL-04 mục 6.3)"
cat > "$W/kafka.yaml" <<YAML
name: scoring-thu
autoscaling:
  keda:
    enabled: true
    minReplicas: 1
    maxReplicas: 6
    pollingInterval: 5
    cooldownPeriod: 30
    behavior:
      scaleUp: { stabilizationWindowSeconds: 0, policies: [{ type: Pods, value: 6, periodSeconds: 15 }] }
      scaleDown: { stabilizationWindowSeconds: 20, policies: [{ type: Percent, value: 100, periodSeconds: 15 }] }
    kafka:
      bootstrapServers: kafka-thu-0.kafka-thu.$NS.svc:9092
      tls: false
      topics:
        - { topic: $TOPIC, lagThreshold: 500, activationLagThreshold: 10 }
YAML
helm upgrade --install scoring-thu "$VD" -n "$NS" -f "$W/chung.yaml" -f "$W/kafka.yaml" --wait --timeout 3m >/dev/null
if kubectl -n "$NS" wait scaledobject/scoring-thu --for=condition=Ready --timeout=120s >/dev/null && cho_pod scoring-thu 1 60; then
  ok "ScaledObject sẵn sàng, chưa có tồn đọng: 1 pod (minReplicas)"
else hong "ScaledObject chưa sẵn sàng"; kubectl -n "$NS" describe scaledobject scoring-thu | tail -20; fi
kafka /opt/kafka/bin/kafka-producer-perf-test.sh --topic "$TOPIC" --num-records 3000 --record-size 100 --throughput -1 \
  --producer-props bootstrap.servers=localhost:9092 >/dev/null
T0=$(date +%s)
if cho_pod scoring-thu 4 120; then
  ok "3.000 bản ghi tồn đọng (> 500 mỗi pod) → 4 pod sau $(( $(date +%s) - T0 )) giây, không vượt 4 phân vùng"
else hong "không tăng pod theo độ trễ: $(so_pod scoring-thu)"; kubectl -n "$NS" get hpa; fi
kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group scoring-thu --topic "$TOPIC" \
  --reset-offsets --to-latest --execute >/dev/null
T0=$(date +%s)
if cho_pod scoring-thu 1 180; then
  ok "hết tồn đọng → giảm về 1 pod sau $(( $(date +%s) - T0 )) giây (cooldown 30 s, ổn định 20 s)"
else hong "không giảm pod khi hết độ trễ: $(so_pod scoring-thu)"; fi

echo "== Cửa sổ tăng trước theo kỳ của cell (TL-04 mục 6.4, OPS-06)"
read -r GIO NGAY THANG < <(TZ=Asia/Ho_Chi_Minh date '+%-H %-d %-m')
if [ "$GIO" -ge 23 ]; then echo "  (sau 23 giờ giờ Việt Nam cửa sổ 06:00–23:00 không áp dụng; chạy lại sau 0 giờ)"; else
cat > "$W/cron.yaml" <<YAML
name: tang-truoc-thu
global:
  cuaSoTangTruoc:
    - ten: ky-thu
      gioBatDau: $GIO
      gioKetThuc: $((GIO + 1))
      lich: [{ ngay: "$NGAY", thang: $THANG }]
      soPod: { tang-truoc-thu: 3, khac: 9 }
autoscaling:
  keda:
    enabled: true
    minReplicas: 1
    maxReplicas: 5
    pollingInterval: 5
    cpuUtilization: 90
    preScale: { cuoiQuy: 0 }
YAML
helm upgrade --install tang-truoc-thu "$VD" -n "$NS" -f "$W/chung.yaml" -f "$W/cron.yaml" --wait --timeout 3m >/dev/null
if cho_pod tang-truoc-thu 3 120; then
  ok "trigger cron ky-thu-0 (ngày $NGAY/$THANG, $GIO:00–$((GIO + 1)):00) nâng workload từ 1 lên 3 pod"
else hong "cửa sổ tăng trước không nâng pod: $(so_pod tang-truoc-thu)"; kubectl -n "$NS" get scaledobject tang-truoc-thu -o yaml | tail -20; fi
fi
if kubectl apply -f - >/dev/null 2>"$W/err" <<YAML
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata: { name: thu-trung, namespace: $NS }
spec:
  scaleTargetRef: { name: scoring-thu }
  triggers: [{ type: cpu, metricType: Utilization, metadata: { value: "50" } }]
YAML
then hong "ScaledObject thứ hai cho cùng workload lẽ ra bị từ chối"; kubectl -n "$NS" delete scaledobject thu-trung >/dev/null
else ok "webhook KEDA từ chối bộ co giãn thứ hai cho cùng workload (TL-04 mục 6.1): $(grep -o 'workload.*' "$W/err" | head -c 90)"; fi

echo "== Pod giữ chỗ và PriorityClass capacity-reservation (TL-04 mục 6.5)"
# Pod giữ chỗ chiếm gần hết CPU còn trống của node thử (chừa 300m), để pod thật 400m phải trục xuất nó.
CAP=$(kubectl get node "$NODE" -o jsonpath='{.status.allocatable.cpu}')
case "$CAP" in *m) CAP=${CAP%m} ;; *) CAP=$((CAP * 1000)) ;; esac
DUNG=$(kubectl get pods -A --field-selector "spec.nodeName=$NODE,status.phase!=Succeeded,status.phase!=Failed" \
  -o jsonpath='{range .items[*]}{range .spec.containers[*]}{.resources.requests.cpu}{" "}{end}{end}' \
  | tr ' ' '\n' | awk '/m$/{s+=substr($0,1,length($0)-1);next} /./{s+=$0*1000} END{print s+0}')
GC=$((CAP - DUNG - 300))
helm upgrade co-gian "$CHART" -n keda -f values-kind-co-gian.yaml --set giuCho.enabled=true \
  --set "giuCho.pools.app-general.cpu=${GC}m" --wait --timeout 3m >/dev/null
if kubectl -n kube-system wait pod -l app=capacity-reservation,pool=app-general --for=condition=Ready --timeout=120s >/dev/null; then
  ok "pod giữ chỗ chạy trên pool app-general, giữ ${GC}m CPU"
else hong "pod giữ chỗ không chạy"; fi
kubectl -n "$NS" run pod-that --image="$ECHO_ANH" --restart=Never --overrides "{\"spec\":{\"nodeSelector\":{\"kubernetes.io/hostname\":\"$NODE\"},\"securityContext\":{\"runAsNonRoot\":true,\"runAsUser\":65532,\"seccompProfile\":{\"type\":\"RuntimeDefault\"}},\"containers\":[{\"name\":\"pod-that\",\"image\":\"$ECHO_ANH\",\"args\":[\"-listen=:8080\"],\"resources\":{\"requests\":{\"cpu\":\"400m\",\"memory\":\"16Mi\"},\"limits\":{\"cpu\":\"400m\",\"memory\":\"16Mi\"}},\"securityContext\":{\"allowPrivilegeEscalation\":false,\"capabilities\":{\"drop\":[\"ALL\"]}}}]}}" >/dev/null
if kubectl -n "$NS" wait pod/pod-that --for=condition=Ready --timeout=120s >/dev/null; then
  sleep 5
  PEND=$(kubectl -n kube-system get pods -l app=capacity-reservation,pool=app-general --field-selector=status.phase=Pending -o name | wc -l)
  if [ "$PEND" -ge 1 ]; then ok "pod thật 400m trục xuất pod giữ chỗ; pod giữ chỗ chuyển Pending (tín hiệu thêm node)"
  else hong "pod giữ chỗ không chuyển Pending"; kubectl -n kube-system get pods -l app=capacity-reservation -o wide; fi
else hong "pod thật không chạy được: không trục xuất pod giữ chỗ"; kubectl -n "$NS" describe pod pod-that | tail -8; fi

echo
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]
