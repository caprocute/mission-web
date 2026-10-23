#!/usr/bin/env bash
# Cài thử audit-archiver (INF-24; TL-04 "Kafka: MirrorMaker 2" đoạn audit-archiver, "Bảng lịch sao lưu"):
#   - Kafka (apache/kafka), Ceph RGW 19 một nút (quay.io/ceph/demo, vai kho sao lưu có Object Lock) và OpenBao dev
#     (khoá Transit ed25519 không xuất được) chạy bằng container trong mạng docker "kind" với IP cố định.
#   - Ảnh audit-archiver dựng từ services-go. CHE_DO=kind (mặc định): nạp ảnh vào cụm kind một node riêng, cài chart
#     deploy/charts/audit-archiver với audit-archiver/values-kind.yaml, pod phải sẵn sàng, job kiểm hằng ngày chạy từ
#     CronJob. CHE_DO=docker: cho máy không chạy được kind lồng trong container (runc không tạo được tiến trình con);
#     dựng manifest bằng helm template với cùng values, rồi chạy ảnh bằng docker với đúng biến môi trường của
#     ConfigMap, đúng tệp bí mật, người dùng 10001, hệ tệp gốc chỉ đọc, bỏ mọi capability như securityContext của chart.
#   - Phát sự kiện kiểm toán, đo độ trễ từ sự kiện tới tệp đã khoá qua chỉ số của dịch vụ (cửa sổ lô mặc định 4 phút,
#     phải ≤ 5 phút), kiểm hằng ngày phải đạt, audit-verify chạy ngoài dịch vụ phải đạt, xoá tệp đã khoá bị từ chối.
# Cần docker, helm, python3 + PyYAML; thêm kind và kubectl ở chế độ kind. Cụm kind dùng CNI mặc định: NetworkPolicy
# được cài nhưng không được thực thi (kindnet), phần mạng đã kiểm bằng kubeconform ở bước template.
# Dùng: deploy/environments/kind/kiem-thu-audit-archiver.sh   (CHE_DO=docker; DUNG_ANH=0; GIU_LAI=1 giữ cụm và container)
set -euo pipefail
cd "$(dirname "$0")"
GOC=$(cd ../../.. && pwd)
CHART=$GOC/deploy/charts/audit-archiver
CHE_DO=${CHE_DO:-kind}
CLUSTER=${CLUSTER:-vt-audit-kind}
KIND_IMAGE=${KIND_IMAGE:-kindest/node:v1.33.1}
KAFKA_ANH=${KAFKA_ANH:-apache/kafka:3.9.0}
CEPH_ANH=quay.io/ceph/demo:latest-squid
BAO_ANH=quay.io/openbao/openbao:2.1.0
AWS_ANH=mirror.gcr.io/amazon/aws-cli@sha256:1a491b258590cc66866cd550f592edff80082f44daf1d903820daeca2f4954de
KAFKA_IP=172.18.0.60; RGW_IP=172.18.0.61; BAO_IP=172.18.0.62; APP_IP=172.18.0.63
NS=secure
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
W=$(mktemp -d)
# Khoá thử sinh ngẫu nhiên mỗi lần chạy, chỉ nằm trong thư mục tạm và Secret của cụm thử.
S3_AK=$(openssl rand -hex 10); S3_SK=$(openssl rand -hex 20); BAO_TOK=$(openssl rand -hex 16)
don() {
  if [ "${GIU_LAI:-0}" != 1 ]; then
    docker rm -f aa-kafka aa-ceph aa-bao aa-app >/dev/null 2>&1 || true
    [ "$CHE_DO" = kind ] && { kind delete cluster --name "$CLUSTER" >/dev/null 2>&1 || true; }
    rm -rf "${W:?}"
  else echo "Giữ lại container aa-kafka, aa-ceph, aa-bao, aa-app, cụm $CLUSTER (nếu có) và thư mục $W"; fi
}
trap don EXIT
cho() { local n=$1; shift; for _ in $(seq "$n"); do "$@" >/dev/null 2>&1 && return 0; sleep 3; done; return 1; }

echo "== Ảnh audit-archiver (chế độ $CHE_DO)"
docker network inspect kind >/dev/null 2>&1 || docker network create --subnet 172.18.0.0/16 kind >/dev/null
# DUNG_ANH=0 dùng lại ảnh audit-archiver:kind đã dựng (khi Docker Hub giới hạn số lần hỏi ảnh nền).
if [ "${DUNG_ANH:-1}" = 1 ]; then
# shellcheck disable=SC2086 # tách tham số --build-arg và --secret khi biến có giá trị
docker build -q --network host ${HTTPS_PROXY:+--build-arg HTTPS_PROXY=$HTTPS_PROXY --build-arg HTTP_PROXY=$HTTPS_PROXY} \
  ${CA_BUNDLE:+--secret id=ca_bundle,src=$CA_BUNDLE} -f "$GOC/services-go/audit-archiver/Dockerfile" \
  -t audit-archiver:kind "$GOC/services-go" >/dev/null
fi
ID=$(docker image inspect audit-archiver:kind --format '{{.Id}}'); ID=${ID#sha256:}
ok "dựng ảnh audit-archiver@sha256:${ID:0:12}"
helm dependency build "$CHART" >/dev/null
helm template audit-archiver "$CHART" -n $NS -f audit-archiver/values-kind.yaml --set image.digest="sha256:$ID" > "$W/manifest.yaml"
ok "helm template với values-kind ($(grep -c '^kind:' "$W/manifest.yaml") tài nguyên)"

echo "== Kafka, Ceph RGW, OpenBao trong mạng kind"
docker rm -f aa-kafka aa-ceph aa-bao aa-app >/dev/null 2>&1 || true
docker run -d --name aa-kafka --network kind --ip $KAFKA_IP \
  -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://$KAFKA_IP:9092 \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
  "$KAFKA_ANH" >/dev/null
docker run -d --name aa-ceph --network kind --ip $RGW_IP --tmpfs /var/lib/ceph:rw,size=3g -e MON_IP=$RGW_IP \
  -e CEPH_PUBLIC_NETWORK=172.18.0.0/16 -e DEMO_DAEMONS=osd,rgw -e RGW_FRONTEND_PORT=8080 -e OSD_SIZE=1024 \
  -e CEPH_DEMO_UID=audit-archiver -e CEPH_DEMO_ACCESS_KEY="$S3_AK" -e CEPH_DEMO_SECRET_KEY="$S3_SK" "$CEPH_ANH" demo >/dev/null
docker run -d --name aa-bao --network kind --ip $BAO_IP "$BAO_ANH" server -dev -dev-root-token-id="$BAO_TOK" \
  -dev-listen-address=0.0.0.0:8200 >/dev/null
bao() { curl -sf -H "X-Vault-Token: $BAO_TOK" "$@"; }
cho 40 bao "http://$BAO_IP:8200/v1/sys/health"
bao -X POST -d '{"type":"transit"}' "http://$BAO_IP:8200/v1/sys/mounts/transit" >/dev/null
bao -X POST -d '{"type":"ed25519","exportable":false}' "http://$BAO_IP:8200/v1/transit/keys/audit-archiver" >/dev/null
KT=/opt/kafka/bin
cho 60 docker exec aa-kafka $KT/kafka-topics.sh --bootstrap-server localhost:9092 --list
docker exec aa-kafka $KT/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic sys.audit.v1 --partitions 3 \
  --replication-factor 1 >/dev/null
ok "Kafka, OpenBao sẵn sàng; topic sys.audit.v1 có 3 phân vùng"
s3() { docker run --rm --network kind -e AWS_ACCESS_KEY_ID="$S3_AK" -e AWS_SECRET_ACCESS_KEY="$S3_SK" "$AWS_ANH" \
  --endpoint-url "http://$RGW_IP:8080" --region us-east-1 s3api "$@"; }
if cho 80 s3 create-bucket --bucket audit-cell-kind --object-lock-enabled-for-bucket; then
  ok "Ceph RGW tạo bucket audit-cell-kind có Object Lock"
else hong "tạo bucket có Object Lock"; exit 1; fi

# Tệp bí mật như Secret gắn vào pod; quyền đọc cho người dùng 10001 của container.
mkdir -p "$W/s3" "$W/openbao"
printf '%s' "$S3_AK" > "$W/s3/access-key"; printf '%s' "$S3_SK" > "$W/s3/secret-key"; printf '%s' "$BAO_TOK" > "$W/openbao/token"
chmod 755 "$W" "$W/s3" "$W/openbao"; chmod 644 "$W"/s3/* "$W"/openbao/*
# Biến môi trường của ConfigMap do chart sinh, dùng cho chế độ docker.
python3 - "$W/manifest.yaml" > "$W/env" <<'EOF'
import sys, yaml
for d in yaml.safe_load_all(open(sys.argv[1])):
    if d and d.get("kind") == "ConfigMap" and d["metadata"]["name"] == "audit-archiver":
        for k, v in d["data"].items():
            print(f"{k}={v}")
EOF
CHAY=(--network kind --user 10001:10001 --read-only --cap-drop ALL --security-opt no-new-privileges --tmpfs /tmp:size=64m
  --env-file "$W/env" -v "$W/s3:/etc/vt/s3:ro" -v "$W/openbao:/etc/vt/openbao:ro")

echo "== Chạy dịch vụ"
if [ "$CHE_DO" = kind ]; then
  kind get clusters 2>/dev/null | grep -qx "$CLUSTER" || kind create cluster --name "$CLUSTER" --image "$KIND_IMAGE" --wait 120s >/dev/null
  kubectl config use-context "kind-$CLUSTER" >/dev/null
  NODE=$CLUSTER-control-plane
  docker save -o "$W/anh.tar" audit-archiver:kind
  docker exec -i "$NODE" ctr --namespace=k8s.io images import - < "$W/anh.tar" >/dev/null
  docker exec "$NODE" ctr --namespace=k8s.io images rm "docker.io/library/audit-archiver@sha256:$ID" >/dev/null 2>&1 || true
  docker exec "$NODE" ctr --namespace=k8s.io images tag docker.io/library/audit-archiver:kind "docker.io/library/audit-archiver@sha256:$ID" >/dev/null
  kubectl create namespace $NS --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  kubectl -n $NS create secret generic audit-archiver-s3 --from-file="$W/s3" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  kubectl -n $NS create secret generic audit-archiver-openbao --from-file="$W/openbao" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  helm upgrade --install audit-archiver "$CHART" -n $NS -f audit-archiver/values-kind.yaml --set image.digest="sha256:$ID" --wait --timeout 5m >/dev/null
  if kubectl -n $NS wait --for=condition=Ready pod -l app.kubernetes.io/name=audit-archiver --timeout=180s >/dev/null; then
    ok "pod audit-archiver sẵn sàng"
  else hong "pod audit-archiver không sẵn sàng"; kubectl -n $NS logs deploy/audit-archiver --tail=50 || true; exit 1; fi
  POD=$(kubectl -n $NS get pod -l app.kubernetes.io/name=audit-archiver -o jsonpath='{.items[0].metadata.name}')
  chiso() { kubectl get --raw "/api/v1/namespaces/$NS/pods/$POD:8081/proxy/metrics"; }
  nhatky() { kubectl -n $NS logs "$POD" --tail=50; }
  kiem_tra() {
    kubectl -n $NS delete job kiem-tra-thu --ignore-not-found >/dev/null
    kubectl -n $NS create job --from=cronjob/audit-archiver-kiem-tra kiem-tra-thu >/dev/null
    kubectl -n $NS wait --for=condition=complete job/kiem-tra-thu --timeout=300s >/dev/null 2>&1
  }
  khoa_cong_khai() { kubectl -n $NS exec "$POD" -- /audit-archiver khoa-cong-khai; }
else
  docker run -d --name aa-app --ip $APP_IP "${CHAY[@]}" audit-archiver:kind >/dev/null
  if cho 60 curl -sf "http://$APP_IP:8081/readyz"; then
    ok "container audit-archiver sẵn sàng (/readyz), chạy người dùng 10001, gốc chỉ đọc, không capability"
  else hong "audit-archiver không sẵn sàng"; docker logs --tail 50 aa-app || true; exit 1; fi
  chiso() { curl -sf "http://$APP_IP:8081/metrics"; }
  nhatky() { docker logs --tail 50 aa-app; }
  ARGS=$(python3 - "$W/manifest.yaml" <<'EOF'
import sys, yaml
for d in yaml.safe_load_all(open(sys.argv[1])):
    if d and d.get("kind") == "CronJob":
        print(" ".join(d["spec"]["jobTemplate"]["spec"]["template"]["spec"]["containers"][0]["args"]))
EOF
)
  # shellcheck disable=SC2086 # tách tham số của CronJob
  kiem_tra() { docker run --rm "${CHAY[@]}" audit-archiver:kind $ARGS > "$W/kiem-tra.log" 2>&1; }
  khoa_cong_khai() { docker run --rm "${CHAY[@]}" audit-archiver:kind khoa-cong-khai; }
fi

echo "== Phát sự kiện và đo độ trễ"
T0=$(date +%s)
seq 1 3000 | sed 's/^/su-kien-kiem-toan-/' | docker exec -i aa-kafka $KT/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic sys.audit.v1 >/dev/null
da_luu() { chiso | awk '/^audit_archiver_ban_ghi_luu_total/ {s+=$2} END {exit !(s>=3000)}'; }
if cho 150 da_luu; then
  T1=$(date +%s)
  TRE=$(chiso | awk '/^audit_archiver_do_tre_lo_cuoi_giay/ {if ($2>m) m=$2} END {printf "%d", m}')
  echo "  3.000 sự kiện vào kho sau $((T1-T0)) s; độ trễ lô lớn nhất theo chỉ số: ${TRE} s"
  if [ "$TRE" -le 300 ]; then ok "độ trễ từ sự kiện tới tệp đã khoá ≤ 5 phút (${TRE} s)"; else hong "độ trễ ${TRE} s vượt 5 phút"; fi
else hong "chưa lưu đủ 3.000 sự kiện"; nhatky || true; fi

echo "== Kiểm hằng ngày, audit-verify, bất biến"
if kiem_tra; then ok "kiểm hằng ngày đạt"; else hong "kiểm hằng ngày không đạt"; cat "$W/kiem-tra.log" 2>/dev/null || true; fi
khoa_cong_khai > "$W/pub.json"
if docker run --rm --network kind -v "$W:/w:ro" -e AWS_ACCESS_KEY_ID="$S3_AK" -e AWS_SECRET_ACCESS_KEY="$S3_SK" \
     --entrypoint /audit-verify audit-archiver:kind -endpoint "$RGW_IP:8080" -khong-tls -region us-east-1 \
     -bucket audit-cell-kind -cell cell-kind -khoa-cong-khai /w/pub.json > "$W/verify.json" 2> "$W/verify.err"; then
  ok "audit-verify độc lập đạt: $(tail -1 "$W/verify.err")"
else hong "audit-verify không đạt"; cat "$W/verify.err"; fi
# shellcheck disable=SC2016 # dấu ` là cú pháp JMESPath của aws-cli, không phải lệnh shell
KEY=$(s3 list-objects-v2 --bucket audit-cell-kind --query 'Contents[?ends_with(Key, `.parquet`)].Key | [0]' --output text)
VID=$(s3 head-object --bucket audit-cell-kind --key "$KEY" --query VersionId --output text)
if s3 delete-object --bucket audit-cell-kind --key "$KEY" --version-id "$VID" >/dev/null 2>&1; then
  hong "xoá phiên bản tệp đã khoá lại thành công"
else ok "kho từ chối xoá phiên bản tệp đã khoá"; fi

echo "Kết quả: $dat đạt, $truot hỏng"
[ "$truot" = 0 ]
