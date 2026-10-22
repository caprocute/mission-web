#!/usr/bin/env bash
# Kiểm thử tầng sự kiện trên kind (INF-10; ADR-03, TL-03 "Transactional Outbox và Debezium", TL-04 mục 4.6, SEC-57):
#   - Strimzi 1.2.0 dựng cụm Kafka KRaft từ chart kafka (values-kind-kafka.yaml), mọi KafkaTopic, KafkaUser sẵn sàng.
#   - Chèn một dòng vào bảng outbox (DDL thật của NEN-032, phân vùng theo ngày) thì sự kiện tới đúng topic qua Debezium
#     EventRouter, khoá là event_key, header ce_type, ce_tenantid, aggregate_version lấy từ cột.
#   - Job đăng ký đưa đủ lược đồ Avro vào Apicurio; lược đồ không tương thích ngược bị từ chối.
#   - Kết nối không chứng chỉ client bị từ chối (mTLS); tài khoản không có ACL không đọc, không ghi được; chỉ
#     audit-writer ghi được sys.audit.v1, kafka-connect thì không.
# Cụm thu nhỏ: một broker trên node vt-cell-kind-worker (hai worker còn lại tạm cordon trong lúc chạy), PostgreSQL 17
# giả lập pg-core trong data-pg. Cần cụm kind dựng bằng tao-cum.sh, docker, helm, kubectl, gói Debezium (tải và kiểm
# SHA-512 ở dung-anh.sh; DEBEZIUM_TGZ trỏ tới gói đã tải để khỏi tải lại). Mật khẩu sinh ngẫu nhiên mỗi lần chạy.
# Dùng: deploy/environments/kind/kiem-thu-su-kien.sh
set -euo pipefail
cd "$(dirname "$0")"
GOC=$(cd ../../.. && pwd)
CHART=$GOC/deploy/charts/vt-platform/ha-tang/kafka
CLUSTER=${CLUSTER:-vt-cell-kind}
NODE=$CLUSTER-worker
STRIMZI_CHART=${STRIMZI_CHART:-oci://quay.io/strimzi-helm/strimzi-kafka-operator}
STRIMZI_VER=1.2.0
KAFKA_ANH=quay.io/strimzi/kafka@sha256:fef34b5438e8556cc08c01f3e254e47346f061b53a4e38d4289853777e0ea7f1
APICURIO_ANH=quay.io/apicurio/apicurio-registry@sha256:c9cae4c90ce46538abf673c68eb591345f23bcc6c2aa6bfd47189adcf609d8f1
PY_ANH=mirror.gcr.io/library/python@sha256:c50ccfb66e5cc97a610dcd0b9597de6ef5b52c96280b51997b353a0fc87a8361
PG_ANH=mirror.gcr.io/library/postgres:17-alpine
CONNECT=vt-kind/kafka-connect-debezium
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
W=$(mktemp -d)
KHAC=$(kind get nodes --name "$CLUSTER" | grep worker | grep -v "^$NODE$" | tr '\n' ' ')
don() {
  for n in $KHAC; do kubectl uncordon "$n" >/dev/null 2>&1 || true; done
  rm -rf "${W:?}"
}
trap don EXIT
cho() { # <số lần> <lệnh...>: thử lại mỗi 5 giây
  local n=$1; shift
  for _ in $(seq "$n"); do "$@" >/dev/null 2>&1 && return 0; sleep 5; done; return 1
}
mk() { openssl rand -hex 16; }

echo "== Chuẩn bị: ảnh trên $NODE, ảnh Connect có Debezium, Strimzi $STRIMZI_VER"
for n in $KHAC; do kubectl cordon "$n" >/dev/null; done
NAP_NODES=$NODE ./nap-anh.sh quay.io/strimzi/operator:$STRIMZI_VER $KAFKA_ANH $APICURIO_ANH $PY_ANH $PG_ANH >/dev/null
"$CHART/image/dung-anh.sh" $CONNECT:1 quay.io/strimzi/kafka:1.2.0-kafka-4.3.1 > "$W/build.log" 2>&1 || { cat "$W/build.log"; exit 1; }
DG=$(docker image inspect -f '{{.Id}}' $CONNECT:1)
docker save -o "$W/c.tar" $CONNECT:1
docker exec -i "$NODE" ctr -n k8s.io images import --snapshotter=overlayfs - < "$W/c.tar" >/dev/null
docker exec "$NODE" ctr -n k8s.io images tag --force "docker.io/$CONNECT:1" "docker.io/$CONNECT@$DG" >/dev/null
rm -f "$W/c.tar"
ok "ảnh Connect dựng từ Strimzi Kafka 4.3.1 + Debezium 3.7.0 (SHA-512 khớp), $DG"
kubectl create ns strimzi --dry-run=client -o yaml | kubectl apply -f - >/dev/null
for ns in data-kafka data-pg; do kubectl create ns $ns --dry-run=client -o yaml | kubectl apply -f - >/dev/null; done
helm upgrade --install strimzi "$STRIMZI_CHART" --version $STRIMZI_VER -n strimzi \
  --set 'watchNamespaces={data-kafka}' --set resources.requests.cpu=100m --wait --timeout 5m >/dev/null
ok "Strimzi operator $STRIMZI_VER theo dõi data-kafka"

# default-deny và allow-dns như cell-base: chính sách của chart phải tự đủ cho luồng cần thiết.
kubectl apply -f - >/dev/null <<EOF
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: default-deny-all, namespace: data-kafka }
spec: { podSelector: {}, policyTypes: [Ingress, Egress] }
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: allow-dns, namespace: data-kafka }
spec:
  podSelector: {}
  policyTypes: [Egress]
  egress:
    - to: [{ namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: kube-system } }, podSelector: { matchLabels: { k8s-app: kube-dns } } }]
      ports: [{ protocol: UDP, port: 53 }, { protocol: TCP, port: 53 }]
EOF

echo "== PostgreSQL giả lập pg-core: CSDL td_kind có bảng outbox thật, publication, khe sao chép; CSDL apicurio"
P_CDC=$(mk); P_API=$(mk); P_PG=$(mk)
sed -e 's/\${flyway:defaultSchema}/task/g' \
    -e '/^SELECT partman.create_parent/,/^    p_jobmon/d' -e '/^UPDATE partman.part_config/,/^ WHERE parent_table/d' \
    -e '/fn_enable_tenant_rls\|fn_protect_partitions/d' \
    "$GOC/backend/libs/vt-db-migration/src/main/resources/db/common-business/V0_20__outbox_event.sql" > "$W/outbox.sql.in"
cat > "$W/10-khoi-tao.sh" <<EOF
#!/bin/sh
set -e
psql -v ON_ERROR_STOP=1 -U postgres <<SQL
CREATE ROLE cdc_td_kind LOGIN REPLICATION PASSWORD '\$P_CDC';
CREATE ROLE apicurio LOGIN PASSWORD '\$P_API';
CREATE DATABASE td_kind;
CREATE DATABASE apicurio OWNER apicurio;
SQL
psql -v ON_ERROR_STOP=1 -U postgres -d td_kind <<SQL
CREATE SCHEMA task;
\\i /docker-entrypoint-initdb.d/outbox.sql.in
CREATE TABLE task.outbox_event_mac_dinh PARTITION OF task.outbox_event DEFAULT;
CREATE TABLE public.dbz_heartbeat (id int PRIMARY KEY, ts timestamptz);
GRANT USAGE ON SCHEMA task TO cdc_td_kind;
GRANT SELECT ON ALL TABLES IN SCHEMA task TO cdc_td_kind;
-- Bảng phân vùng: publish_via_partition_root để sự kiện mang tên bảng cha (khớp table.include.list).
CREATE PUBLICATION pub_outbox FOR TABLE task.outbox_event WITH (publish_via_partition_root = true);
SELECT pg_create_logical_replication_slot('dbz_outbox_td_kind', 'pgoutput');
SQL
EOF
kubectl -n data-pg create secret generic pg-thu --from-literal=P_CDC="$P_CDC" --from-literal=P_API="$P_API" \
  --from-literal=POSTGRES_PASSWORD="$P_PG" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n data-pg create configmap pg-thu-khoi-tao --from-file="$W/10-khoi-tao.sh" --from-file="$W/outbox.sql.in" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl apply -f - >/dev/null <<EOF
apiVersion: apps/v1
kind: Deployment
metadata: { name: pg-core-thu, namespace: data-pg }
spec:
  replicas: 1
  selector: { matchLabels: { cnpg.io/cluster: pg-core } }
  template:
    metadata: { labels: { cnpg.io/cluster: pg-core } }
    spec:
      containers:
        - name: pg
          image: $PG_ANH
          args: [-c, wal_level=logical, -c, max_replication_slots=4, -c, max_wal_senders=4]
          envFrom: [{ secretRef: { name: pg-thu } }]
          readinessProbe: { exec: { command: [pg_isready, -U, postgres] }, periodSeconds: 3 }
          volumeMounts: [{ name: khoi-tao, mountPath: /docker-entrypoint-initdb.d }]
      volumes: [{ name: khoi-tao, configMap: { name: pg-thu-khoi-tao } }]
---
apiVersion: v1
kind: Service
metadata: { name: pg-core-rw, namespace: data-pg }
spec: { selector: { cnpg.io/cluster: pg-core }, ports: [{ port: 5432 }] }
EOF
kubectl -n data-pg rollout status deploy/pg-core-thu --timeout=180s >/dev/null
cho 24 kubectl -n data-pg exec deploy/pg-core-thu -- psql -U postgres -d td_kind -tAc "SELECT 1 FROM pg_publication WHERE pubname='pub_outbox'"
psql_td() { kubectl -n data-pg exec deploy/pg-core-thu -- psql -U postgres -d td_kind -v ON_ERROR_STOP=1 -tAc "$1"; }
if [ "$(psql_td "SELECT count(*) FROM pg_replication_slots WHERE slot_name='dbz_outbox_td_kind'")" = 1 ]; then
  ok "pg-core giả lập: bảng task.outbox_event phân vùng, pub_outbox, khe dbz_outbox_td_kind"
else hong "khởi tạo PostgreSQL"; fi

echo "== Cài chart kafka vào data-kafka"
kubectl -n data-kafka create secret generic cdc-pg-core-td-kind --type kubernetes.io/basic-auth \
  --from-literal=username=cdc_td_kind --from-literal=password="$P_CDC" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n data-kafka create secret generic apicurio-db --type kubernetes.io/basic-auth \
  --from-literal=username=apicurio --from-literal=password="$P_API" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
helm upgrade --install kafka "$CHART" -n data-kafka -f values-kind-kafka.yaml --set connect.image.digest="$DG" >/dev/null
SO_TOPIC=$(helm get manifest kafka -n data-kafka | grep -c '^kind: KafkaTopic')
SO_USER=$(helm get manifest kafka -n data-kafka | grep -c '^kind: KafkaUser')
if kubectl -n data-kafka wait kafka/kafka-cell --for=condition=Ready --timeout=600s >/dev/null; then
  ok "cụm Kafka KRaft kafka-cell sẵn sàng (Kafka 4.3.1, listener TLS 9093, authorization simple)"
else hong "Kafka chưa sẵn sàng"; kubectl -n data-kafka get pods; fi
san_sang() { # <loại> <số mong đợi>
  [ "$(kubectl -n data-kafka get "$1" -l strimzi.io/cluster=kafka-cell -o jsonpath='{range .items[*]}{.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}' | grep -c True)" -ge "$2" ]
}
if cho 60 san_sang kafkatopic "$SO_TOPIC"; then ok "$SO_TOPIC KafkaTopic sẵn sàng (EVT-01..26, topic kỹ thuật, retry/dlq, topic của Connect)"
else hong "KafkaTopic chưa đủ: $(kubectl -n data-kafka get kafkatopic --no-headers | grep -vc True) chưa sẵn sàng"; fi
if cho 36 san_sang kafkauser "$SO_USER"; then ok "$SO_USER KafkaUser mTLS có ACL sẵn sàng"; else hong "KafkaUser chưa đủ"; fi

echo "== Apicurio Registry và đăng ký lược đồ"
JOB=$(kubectl -n data-kafka get job -l app.kubernetes.io/name=apicurio-dang-ky -o name | head -1)
if kubectl -n data-kafka wait "$JOB" --for=condition=Complete --timeout=600s >/dev/null; then
  SO_AVRO=$(kubectl -n data-kafka logs "$JOB" | grep -c 'phiên bản')
  ok "Job đăng ký: $SO_AVRO lược đồ Avro, quy tắc BACKWARD_TRANSITIVE và VALIDITY FULL (OrgBootstrap có tham chiếu)"
else hong "Job đăng ký lược đồ"; kubectl -n data-kafka logs "$JOB" --tail=20 || true; fi
# Gọi API từ pod mang nhãn của Job đăng ký (chính sách mạng chỉ cho pod đó tới Apicurio trong data-kafka).
kubectl -n data-kafka run thu-registry --image=$PY_ANH --restart=Never -l app.kubernetes.io/name=apicurio-dang-ky \
  --overrides='{"spec":{"securityContext":{"runAsNonRoot":true,"runAsUser":65534,"seccompProfile":{"type":"RuntimeDefault"}}}}' \
  --command -- sleep 3600 >/dev/null
kubectl -n data-kafka wait pod/thu-registry --for=condition=Ready --timeout=120s >/dev/null
cat > "$W/thu_registry.py" <<'PY'
import json, sys, urllib.request, urllib.error
API = "http://apicurio-registry:8080/apis/registry/v3/groups/default/artifacts/task.task.assigned.v1-value"
def goi(m, url, body=None):
    r = urllib.request.Request(url, method=m, data=None if body is None else json.dumps(body).encode(),
                               headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(r) as x: return x.status, json.loads(x.read() or b"null")
    except urllib.error.HTTPError as e: return e.code, e.read().decode()
_, v = goi("GET", API + "/versions/branch=latest/content")
s = v if isinstance(v, dict) else json.loads(v)
moi = dict(s, fields=s["fields"] + [{"name": sys.argv[1], "type": "string"}])
if sys.argv[2] == "mac-dinh":
    moi["fields"][-1]["default"] = ""
ma, kq = goi("POST", API + "/versions", {"content": {"content": json.dumps(moi), "contentType": "application/json"}})
print(ma)
PY
kubectl -n data-kafka cp "$W/thu_registry.py" thu-registry:/tmp/t.py >/dev/null
MA=$(kubectl -n data-kafka exec thu-registry -- python3 -I /tmp/t.py truongBatBuoc khong 2>/dev/null || true)
# Apicurio 3 trả 400 RuleViolationException (bản 2 trả 409) khi vi phạm quy tắc tương thích.
if [ "$MA" = 400 ] || [ "$MA" = 409 ]; then ok "thêm trường bắt buộc không mặc định vào task.task.assigned.v1-value bị từ chối (HTTP $MA, vi phạm quy tắc)"
else hong "lược đồ không tương thích được nhận (HTTP $MA)"; fi
MA=$(kubectl -n data-kafka exec thu-registry -- python3 -I /tmp/t.py truongTuyChon mac-dinh 2>/dev/null || true)
if [ "$MA" = 200 ]; then ok "thêm trường có mặc định được nhận (tương thích ngược)"; else hong "lược đồ tương thích bị từ chối (HTTP $MA)"; fi

echo "== Outbox → Debezium → topic"
if kubectl -n data-kafka wait kafkaconnect/outbox --for=condition=Ready --timeout=600s >/dev/null \
   && cho 36 sh -c "kubectl -n data-kafka get kafkaconnector outbox-core-td-kind -o jsonpath='{.status.connectorStatus.tasks[0].state}' | grep -q RUNNING"; then
  ok "KafkaConnect outbox và connector outbox-core-td-kind RUNNING (pgoutput, khe dbz_outbox_td_kind)"
else hong "connector chưa chạy"; kubectl -n data-kafka get kafkaconnector outbox-core-td-kind -o jsonpath='{.status}' | head -c 1500; echo; fi
# Pod client mang nhãn cụm để chính sách kafka-noi-bo cho ra broker; chứng chỉ của bốn tài khoản và CA cụm.
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
      volumeMounts:
        - { name: ca, mountPath: /ca }
        - { name: search, mountPath: /u/search }
        - { name: mission-core, mountPath: /u/mission-core }
        - { name: audit-writer, mountPath: /u/audit-writer }
        - { name: kafka-connect, mountPath: /u/kafka-connect }
  volumes:
    - { name: ca, secret: { secretName: kafka-cell-cluster-ca-cert } }
    - { name: search, secret: { secretName: search } }
    - { name: mission-core, secret: { secretName: mission-core } }
    - { name: audit-writer, secret: { secretName: audit-writer } }
    - { name: kafka-connect, secret: { secretName: kafka-connect } }
EOF
kubectl -n data-kafka wait pod/thu-client --for=condition=Ready --timeout=180s >/dev/null
kubectl -n data-kafka exec thu-client -- sh -c '
for u in search mission-core audit-writer kafka-connect; do
  printf "security.protocol=SSL\nssl.truststore.type=PKCS12\nssl.truststore.location=/ca/ca.p12\nssl.truststore.password=%s\nssl.keystore.type=PKCS12\nssl.keystore.location=/u/%s/user.p12\nssl.keystore.password=%s\n" \
    "$(cat /ca/ca.password)" "$u" "$(cat /u/$u/user.password)" > /tmp/$u.properties
done
printf "security.protocol=SSL\nssl.truststore.type=PKCS12\nssl.truststore.location=/ca/ca.p12\nssl.truststore.password=%s\n" "$(cat /ca/ca.password)" > /tmp/khong-cert.properties'
BS=kafka-cell-kafka-bootstrap:9093
kc() { kubectl -n data-kafka exec thu-client -- "$@"; }
TENANT=0193a1b2-0000-7000-8000-000000000001
AGG=0193a1b2-0000-7000-8000-0000000000a1
EID=$(cat /proc/sys/kernel/random/uuid)
psql_td "INSERT INTO task.outbox_event (id, tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type, topic, event_key, payload, schema_version, headers)
  VALUES ('$EID', '$TENANT', 'Task', '$AGG', 3, 'NhiemVuDaGiao', 'task.task.assigned.v1', '$TENANT:$AGG', convert_to('ban-ghi-thu', 'UTF8'), 1,
          '{\"traceparent\":\"00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01\",\"classification\":\"C1\"}')" >/dev/null
kc /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server $BS --consumer.config /tmp/search.properties \
  --group search-thu --topic task.task.assigned.v1 --from-beginning --max-messages 1 --timeout-ms 90000 \
  --property print.key=true --property print.headers=true > "$W/msg.txt" 2>"$W/msg.err" || true
if grep -q "ce_type:NhiemVuDaGiao" "$W/msg.txt" && grep -q "ce_tenantid:$TENANT" "$W/msg.txt" \
   && grep -q "aggregate_version:3" "$W/msg.txt" && grep -q "$TENANT:$AGG" "$W/msg.txt" && grep -q "ban-ghi-thu" "$W/msg.txt"; then
  ok "dòng outbox tới task.task.assigned.v1: khoá event_key, payload nguyên byte, header ce_type, ce_tenantid, aggregate_version"
else hong "không nhận được sự kiện outbox đúng dạng"; cat "$W/msg.txt" "$W/msg.err" | head -20; fi
if grep -q "traceparent" "$W/msg.txt"; then ok "traceparent, classification đi kèm trong header ce_headers (cột jsonb headers)"
else hong "thiếu header ce_headers"; fi

echo "== mTLS và ACL (SEC-57)"
if kc /opt/kafka/bin/kafka-topics.sh --bootstrap-server $BS --command-config /tmp/khong-cert.properties --list >/dev/null 2>&1; then
  hong "client không chứng chỉ vẫn kết nối được"; else ok "client không có chứng chỉ bị từ chối ở bắt tay TLS"; fi
# Kết quả lưu vào biến rồi mới grep: grep -q đóng ống sớm làm lệnh trước nhận SIGPIPE, pipefail đếm là hỏng.
KQ=$(kc /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server $BS --consumer.config /tmp/mission-core.properties \
     --group mission-core-thu --topic task.task.assigned.v1 --from-beginning --max-messages 1 --timeout-ms 30000 2>&1 || true)
if grep -q TopicAuthorizationException <<<"$KQ"; then
  ok "mission-core không đọc được task.task.assigned.v1 (không thuộc bên tiêu thụ trong danh mục)"
else hong "mission-core đọc được topic không có ACL"; fi
gui() { # <tài khoản> <topic>: in kết quả gửi một bản ghi
  echo "thu" | kubectl -n data-kafka exec -i thu-client -- /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server $BS --producer.config /tmp/$1.properties \
    --topic "$2" --producer-property acks=all --producer-property delivery.timeout.ms=20000 --producer-property request.timeout.ms=10000 2>&1 || true
}
KQ=$(gui mission-core task.task.assigned.v1); if grep -q TopicAuthorizationException <<<"$KQ"; then
  ok "mission-core không ghi thẳng được topic outbox (chỉ kafka-connect ghi)"
else hong "mission-core ghi thẳng được topic outbox"; fi
KQ=$(gui kafka-connect sys.audit.v1); if grep -q TopicAuthorizationException <<<"$KQ"; then ok "kafka-connect không ghi được sys.audit.v1"
else hong "kafka-connect ghi được sys.audit.v1"; fi
KQ=$(gui audit-writer sys.audit.v1); if grep -q "Exception\|ERROR" <<<"$KQ"; then hong "audit-writer không ghi được sys.audit.v1"
else ok "audit-writer ghi được sys.audit.v1"; fi
KQ=$(gui search task.task.assigned.v1.retry.search); if grep -q "Exception\|ERROR" <<<"$KQ"; then hong "search không ghi được hàng đợi thử lại của mình"
else ok "search ghi được task.task.assigned.v1.retry.search của mình"; fi

echo "== Dọn"
kubectl -n data-kafka delete pod thu-client thu-registry --wait=false >/dev/null 2>&1 || true
if [ "${GIU_LAI:-0}" != 1 ]; then
  # Xoá KafkaTopic trước khi gỡ cụm: Topic Operator phải còn chạy để gỡ finalizer, nếu không namespace kẹt Terminating.
  kubectl -n data-kafka delete kafkatopic --all --timeout=300s >/dev/null 2>&1 || true
  helm uninstall kafka -n data-kafka --wait >/dev/null 2>&1 || true
  kubectl delete ns data-kafka data-pg --wait=false >/dev/null 2>&1 || true
  helm uninstall strimzi -n strimzi >/dev/null 2>&1 || true
fi
echo
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]
