#!/usr/bin/env bash
# Kiểm thử MirrorMaker 2 trên kind (INF-18, TL-04 mục 8.3 "Kafka: MirrorMaker 2"): hai cụm Kafka thu nhỏ trên hai
# namespace đóng vai hai TTDL, cài cùng chart kafka (values-kind-kafka.yaml, không Connect, không Apicurio):
#   - kafka-hl: vaiTro nguon, chart tạo KafkaUser chỉ đọc mm2-hl-user;
#   - kafka-sn: vaiTro dich, chart tạo CR KafkaMirrorMaker2 mm2-hl-to-sn, KafkaUser mm2-sn-user, NetworkPolicy mm2-ra.
# CA cụm nguồn và chứng thư mm2-hl-user chép sang kafka-sn bằng kubectl (cụm thật đi qua OpenBao, ESO). Kiểm:
#   - MM2 sẵn sàng, topic nhân bản giữ nguyên tên (IdentityReplicationPolicy), topic .retry. không bị nhân bản;
#   - sau khi dừng ghi phía nguồn, offset cuối từng phân vùng hai bên chênh 0, nội dung khớp;
#   - offset nhóm tiêu thụ được đồng bộ sang cụm đích (sync.group.offsets);
#   - mm2-hl-user không ghi được vào cụm nguồn (chỉ đọc).
# Cần cụm kind dựng bằng tao-cum.sh (cluster-gon.yaml đủ), docker, helm, kubectl. Dùng: deploy/environments/kind/kiem-thu-mm2.sh
# GIU_LAI=1 giữ lại hai cụm sau khi chạy.
set -euo pipefail
cd "$(dirname "$0")"
GOC=$(cd ../../.. && pwd)
CHART=$GOC/deploy/charts/vt-platform/ha-tang/kafka
CLUSTER=${CLUSTER:-vt-cell-kind}
NODE=$CLUSTER-worker
STRIMZI_CHART=${STRIMZI_CHART:-oci://quay.io/strimzi-helm/strimzi-kafka-operator}
STRIMZI_VER=1.2.0
KAFKA_ANH=quay.io/strimzi/kafka@sha256:fef34b5438e8556cc08c01f3e254e47346f061b53a4e38d4289853777e0ea7f1
TOPIC=okr.objective.approved.v1
TOPIC_LOAI=okr.objective.approved.v1.retry.search
SO_TIN=${SO_TIN:-2000}
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
cho() { # <số lần> <lệnh...>: thử lại mỗi 5 giây
  local n=$1; shift
  for _ in $(seq "$n"); do "$@" >/dev/null 2>&1 && return 0; sleep 5; done; return 1
}
san_sang() { # <ns> <loại> <tên>
  [ "$(kubectl -n "$1" get "$2" "$3" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}')" = True ]
}

echo "== Chuẩn bị: ảnh trên $NODE, Strimzi $STRIMZI_VER theo dõi kafka-hl và kafka-sn"
NAP_NODES=$NODE ./nap-anh.sh quay.io/strimzi/operator:$STRIMZI_VER $KAFKA_ANH >/dev/null
for ns in strimzi kafka-hl kafka-sn; do kubectl create ns $ns --dry-run=client -o yaml | kubectl apply -f - >/dev/null; done
helm upgrade --install strimzi "$STRIMZI_CHART" --version $STRIMZI_VER -n strimzi \
  --set 'watchNamespaces={kafka-hl,kafka-sn}' --set resources.requests.cpu=100m --wait --timeout 5m >/dev/null
# default-deny và allow-dns như cell-base, để NetworkPolicy mm2-ra của chart phải tự đủ cho luồng MM2.
for ns in kafka-hl kafka-sn; do
kubectl apply -f - >/dev/null <<EOF
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: default-deny-all, namespace: $ns }
spec: { podSelector: {}, policyTypes: [Ingress, Egress] }
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: allow-dns, namespace: $ns }
spec:
  podSelector: {}
  policyTypes: [Egress]
  egress:
    - to: [{ namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: kube-system } }, podSelector: { matchLabels: { k8s-app: kube-dns } } }]
      ports: [{ port: 53, protocol: UDP }, { port: 53, protocol: TCP }]
---
# Pod của cụm và Entity Operator gọi kube-apiserver (cụm thật: CiliumNetworkPolicy kafka-*-kube-apiserver).
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: kind-ra-kube-apiserver, namespace: $ns }
spec:
  podSelector: {}
  policyTypes: [Egress]
  egress:
    - to: [{ ipBlock: { cidr: 172.18.0.0/16 } }, { ipBlock: { cidr: 10.96.0.1/32 } }]
      ports: [{ port: 6443, protocol: TCP }, { port: 443, protocol: TCP }]
EOF
done
CHUNG=(-f values-kind-kafka.yaml --set connect.enabled=false --set apicurio.enabled=false --set cilium=false)
helm upgrade --install kafka-hl "$CHART" -n kafka-hl "${CHUNG[@]}" --set namespace=kafka-hl \
  --set mirrorMaker2.vaiTro=nguon >/dev/null
helm upgrade --install kafka-sn "$CHART" -n kafka-sn "${CHUNG[@]}" --set namespace=kafka-sn \
  --set mirrorMaker2.vaiTro=dich --set mirrorMaker2.replicas=1 --set mirrorMaker2.heSoNhanBan=1 \
  --set mirrorMaker2.sourceTasksMax=2 --set mirrorMaker2.checkpointTasksMax=1 \
  --set mirrorMaker2.nguon.bootstrapServers=kafka-cell-kafka-bootstrap.kafka-hl.svc:9093 \
  --set mirrorMaker2.resources.requests.cpu=250m --set mirrorMaker2.resources.requests.memory=1Gi \
  --set mirrorMaker2.resources.limits.cpu=1 --set mirrorMaker2.resources.limits.memory=1Gi \
  --set-json 'mirrorMaker2.egressTo=[{"namespaceSelector":{"matchLabels":{"kubernetes.io/metadata.name":"kafka-hl"}},"podSelector":{"matchLabels":{"strimzi.io/broker-role":"true"}}}]' >/dev/null
for ns in kafka-hl kafka-sn; do
  if cho 120 san_sang $ns kafka kafka-cell; then ok "cụm Kafka $ns sẵn sàng"; else hong "cụm Kafka $ns chưa sẵn sàng"; exit 1; fi
done

# Topic thử ở nguồn: một topic nghiệp vụ (khớp topicsPattern) và một topic thử lại (khớp topicsExcludePattern); người
# dùng thử ghi và đọc với nhóm nhom-thu.
kubectl apply -f - >/dev/null <<EOF
apiVersion: kafka.strimzi.io/v1
kind: KafkaTopic
metadata: { name: thu-loai, namespace: kafka-hl, labels: { strimzi.io/cluster: kafka-cell } }
spec: { topicName: $TOPIC_LOAI, partitions: 1, replicas: 1 }
---
apiVersion: kafka.strimzi.io/v1
kind: KafkaUser
metadata: { name: thu-mm2, namespace: kafka-hl, labels: { strimzi.io/cluster: kafka-cell } }
spec:
  authentication: { type: tls }
  authorization:
    type: simple
    acls:
      - { resource: { type: topic, name: "*", patternType: literal }, operations: [Read, Write, Describe] }
      - { resource: { type: group, name: nhom-thu, patternType: prefix }, operations: [Read, Describe] }
      - { resource: { type: group, name: doc-thu, patternType: prefix }, operations: [Read, Describe] }
EOF
cho 60 san_sang kafka-hl kafkauser thu-mm2 || { hong "KafkaUser thu-mm2 chưa sẵn sàng"; exit 1; }
cho 60 san_sang kafka-hl kafkauser mm2-hl-user || { hong "KafkaUser mm2-hl-user (chỉ đọc) chưa sẵn sàng"; exit 1; }
ok "cụm nguồn có KafkaUser mm2-hl-user chỉ đọc do chart tạo"

# Chép CA cụm nguồn và chứng thư mm2-hl-user sang cụm đích đúng tên chart chờ.
chep() { # <secret nguồn> <tên đích>
  kubectl -n kafka-hl get secret "$1" -o json \
    | jq --arg t "$2" '{apiVersion, kind, type, data, metadata: {name: $t, namespace: "kafka-sn"}}' | kubectl apply -f - >/dev/null
}
chep kafka-cell-cluster-ca-cert kafka-hl-cluster-ca-cert
chep mm2-hl-user mm2-hl-user
if cho 120 san_sang kafka-sn kafkamirrormaker2 mm2-hl-to-sn; then ok "KafkaMirrorMaker2 mm2-hl-to-sn sẵn sàng (mTLS hai phía)"
else hong "MM2 chưa sẵn sàng"; kubectl -n kafka-sn get kafkamirrormaker2 mm2-hl-to-sn -o jsonpath='{.status}' | head -c 2000; echo; exit 1; fi

# Pod client hai phía: phía nguồn dùng thu-mm2 và mm2-hl-user, phía đích dùng mm2-sn-user (đọc mọi topic, nhóm).
for ns in kafka-hl kafka-sn; do
  u=thu-mm2; [ $ns = kafka-sn ] && u=mm2-sn-user
  kubectl apply -f - >/dev/null <<EOF
apiVersion: v1
kind: Pod
metadata: { name: thu-client, namespace: $ns, labels: { strimzi.io/cluster: kafka-cell } }
spec:
  securityContext: { runAsNonRoot: true, runAsUser: 1001, seccompProfile: { type: RuntimeDefault } }
  containers:
    - name: c
      image: $KAFKA_ANH
      command: [sleep, "3600"]
      env: [{ name: KAFKA_HEAP_OPTS, value: "-Xmx256m" }]
      securityContext: { allowPrivilegeEscalation: false, capabilities: { drop: [ALL] } }
      volumeMounts: [{ name: ca, mountPath: /ca }, { name: u, mountPath: /u/u }, { name: ro, mountPath: /u/ro }]
  volumes:
    - { name: ca, secret: { secretName: kafka-cell-cluster-ca-cert } }
    - { name: u, secret: { secretName: $u } }
    - { name: ro, secret: { secretName: $([ $ns = kafka-hl ] && echo mm2-hl-user || echo mm2-sn-user) } }
EOF
done
for ns in kafka-hl kafka-sn; do
  kubectl -n $ns wait pod/thu-client --for=condition=Ready --timeout=180s >/dev/null
  kubectl -n $ns exec thu-client -- sh -c '
for u in u ro; do
  printf "security.protocol=SSL\nssl.truststore.type=PKCS12\nssl.truststore.location=/ca/ca.p12\nssl.truststore.password=%s\nssl.keystore.type=PKCS12\nssl.keystore.location=/u/%s/user.p12\nssl.keystore.password=%s\n" \
    "$(cat /ca/ca.password)" "$u" "$(cat /u/$u/user.password)" > /tmp/$u.properties
done'
done
BS=kafka-cell-kafka-bootstrap:9093
kc() { local ns=$1; shift; kubectl -n "$ns" exec -i thu-client -- "$@"; }
gui() { # <topic> <số tin> <tiền tố>
  seq 1 "$2" | sed "s/^/$3-/" | kc kafka-hl /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server $BS \
    --producer.config /tmp/u.properties --topic "$1" >/dev/null 2>&1
}
cuoi() { # <ns> <topic> <tệp properties>: "phân_vùng:offset_cuối" mỗi dòng, sắp theo phân vùng
  kc "$1" /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server $BS --command-config "$3" --topic "$2" --time -1 2>/dev/null \
    | awk -F: '{print $2":"$3}' | sort -n
}

echo "== Nhân bản"
# Chạy lại trên cụm đã giữ (GIU_LAI=1): tính phần tăng so với offset trước khi ghi, nhóm tiêu thụ mới mỗi lần.
TRUOC=$(cuoi kafka-hl $TOPIC /tmp/u.properties | awk -F: '{s+=$2} END {print s+0}')
NHOM=nhom-thu-$(date +%s)
gui $TOPIC "$SO_TIN" "a$TRUOC"
gui $TOPIC_LOAI 10 loai
# Nhóm tiêu thụ đọc một nửa ở nguồn rồi chốt offset để kiểm đồng bộ offset nhóm.
kc kafka-hl /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server $BS --consumer.config /tmp/u.properties \
  --group "$NHOM" --topic $TOPIC --from-beginning --max-messages $(((TRUOC + SO_TIN) / 2)) --timeout-ms 60000 >/dev/null 2>&1 || true
# "Dừng phía nguồn": không ghi gì thêm; chờ MM2 kéo hết và so offset cuối từng phân vùng.
NGUON=$(cuoi kafka-hl $TOPIC /tmp/u.properties)
khop() { [ "$(cuoi kafka-sn $TOPIC /tmp/u.properties)" = "$NGUON" ]; }
if cho 60 khop; then
  ok "offset cuối hai bên khớp sau khi dừng ghi phía nguồn, chênh 0: $(echo "$NGUON" | tr '\n' ' ')"
else hong "offset lệch: nguồn [$(echo "$NGUON" | tr '\n' ' ')], đích [$(cuoi kafka-sn $TOPIC /tmp/u.properties | tr '\n' ' ')]"; fi
TONG=$(echo "$NGUON" | awk -F: '{s+=$2} END {print s}')
[ "$((TONG - TRUOC))" -eq "$SO_TIN" ] && ok "nguồn nhận đúng $SO_TIN tin mới trên $(echo "$NGUON" | wc -l) phân vùng" || hong "nguồn nhận $((TONG - TRUOC)) tin mới, mong $SO_TIN"
doc() { # <ns>: nội dung đã sắp của topic thử (nhóm doc-thu-<số> mới mỗi lần để đọc từ đầu)
  kc "$1" /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server $BS --consumer.config /tmp/u.properties \
    --group "doc-thu-$RANDOM" --topic $TOPIC --from-beginning --max-messages "$TONG" --timeout-ms 60000 2>/dev/null | sort | sha256sum
}
if [ "$(doc kafka-hl)" = "$(doc kafka-sn)" ]; then ok "nội dung $TONG tin hai bên khớp (SHA-256 của tập tin đã sắp)"
else hong "nội dung hai bên lệch"; fi
DS_DICH=$(kc kafka-sn /opt/kafka/bin/kafka-topics.sh --bootstrap-server $BS --command-config /tmp/u.properties --list 2>/dev/null)
if grep -qx "$TOPIC" <<<"$DS_DICH" && ! grep -q "^hl\.$TOPIC$" <<<"$DS_DICH"; then ok "topic giữ nguyên tên ở đích (IdentityReplicationPolicy)"
else hong "tên topic ở đích không đúng"; fi
if grep -qx "$TOPIC_LOAI" <<<"$DS_DICH"; then hong "topic thử lại $TOPIC_LOAI bị nhân bản"
else ok "topic thử lại $TOPIC_LOAI không nhân bản (topicsExcludePattern)"; fi
nhom_off() { # <ns> <properties>: "phân_vùng:offset" của nhóm thử
  kc "$1" /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server $BS --command-config "$2" --describe --group "$NHOM" 2>/dev/null \
    | awk -v t=$TOPIC '$2 == t && $4 ~ /^[0-9]+$/ {print $3":"$4}' | sort -n
}
NGUON_NHOM=$(nhom_off kafka-hl /tmp/u.properties)
dong_bo() { [ "$(nhom_off kafka-sn /tmp/u.properties)" = "$NGUON_NHOM" ]; }
# MM2 dịch offset nhóm theo các mốc offset-syncs nên offset ở đích có thể lùi hơn nguồn (đọc lại vài tin sau chuyển
# vùng, consumer khử trùng bằng inbox), không bao giờ vượt nguồn. Chờ khớp tuyệt đối; hết thời gian thì chấp nhận
# khi mọi phân vùng ở đích có offset và không vượt nguồn.
an_toan() {
  local d; d=$(nhom_off kafka-sn /tmp/u.properties)
  [ "$(wc -l <<<"$d")" = "$(wc -l <<<"$NGUON_NHOM")" ] && [ -n "$d" ] || return 1
  join -t: <(echo "$NGUON_NHOM") <(echo "$d") | awk -F: '$3 > $2 {bad=1} END {exit bad}'
}
if cho 36 dong_bo; then ok "offset nhóm $NHOM đồng bộ sang đích, khớp từng phân vùng: $(echo "$NGUON_NHOM" | tr '\n' ' ')"
elif an_toan; then ok "offset nhóm $NHOM đồng bộ sang đích, không vượt nguồn (nguồn $(echo "$NGUON_NHOM" | tr '\n' ' '), đích $(nhom_off kafka-sn /tmp/u.properties | tr '\n' ' '))"
else hong "offset nhóm chưa đồng bộ: nguồn [$(echo "$NGUON_NHOM" | tr '\n' ' ')], đích [$(nhom_off kafka-sn /tmp/u.properties | tr '\n' ' ')]"; fi
KQ=$(echo x | kc kafka-hl /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server $BS --producer.config /tmp/ro.properties \
  --topic $TOPIC --request-required-acks all 2>&1 || true)
if grep -q "AuthorizationException\|Not authorized" <<<"$KQ"; then ok "mm2-hl-user không ghi được vào cụm nguồn (chỉ đọc): $(grep -o "[A-Za-z]*AuthorizationException" <<<"$KQ" | head -1)"
else hong "mm2-hl-user ghi được vào cụm nguồn"; echo "$KQ" | tail -3; fi

echo "== Dọn"
kubectl -n kafka-hl delete pod thu-client --wait=false >/dev/null 2>&1 || true
kubectl -n kafka-sn delete pod thu-client --wait=false >/dev/null 2>&1 || true
if [ "${GIU_LAI:-0}" != 1 ]; then
  helm uninstall kafka-sn -n kafka-sn --wait >/dev/null 2>&1 || true
  for ns in kafka-hl kafka-sn; do kubectl -n $ns delete kafkatopic --all --timeout=300s >/dev/null 2>&1 || true; done
  helm uninstall kafka-hl -n kafka-hl --wait >/dev/null 2>&1 || true
  kubectl delete ns kafka-hl kafka-sn --wait=false >/dev/null 2>&1 || true
  helm uninstall strimzi -n strimzi >/dev/null 2>&1 || true
fi
echo
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]
